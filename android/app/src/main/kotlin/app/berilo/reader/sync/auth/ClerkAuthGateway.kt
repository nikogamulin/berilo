package app.berilo.reader.sync.auth

import android.content.Context
import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.fetchToken
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.attemptFirstFactor
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.attemptVerification
import com.clerk.api.signup.prepareVerification
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The only class in the app that touches the Clerk SDK ([AuthGateway] is the seam).
 *
 * Clerk is a process-wide singleton, so this type keeps no session of its own: it reads
 * `Clerk.userFlow` / `Clerk.isInitialized` and delegates. The one piece of state it does hold is
 * [pending] — the sign-in or sign-up awaiting its emailed code, which the caller returns to via
 * [verifyCode].
 *
 * Only email-code and password strategies are wired. OAuth, Google One Tap and passkeys are
 * deliberately left out: they need Play Services or a working Custom Tab, neither of which can
 * be assumed on the Boox target, and their Play-side dependencies are excluded in
 * `app/build.gradle.kts`.
 *
 * @param publishableKey The build's Clerk publishable key; blank in a build made without
 *   `android/berilo.properties`, which pins [AccountState.Unconfigured] and skips
 *   `Clerk.initialize` entirely.
 */
class ClerkAuthGateway(
    context: Context,
    private val publishableKey: String,
) : AuthGateway {

    /** The verification awaiting a code, or null when no code-based flow is in flight. */
    private sealed interface Pending {
        val email: String

        data class Verification(val signIn: SignIn, override val email: String) : Pending

        data class Registration(val signUp: SignUp, override val email: String) : Pending
    }

    private val pending = AtomicReference<Pending?>(null)

    override val isConfigured: Boolean = publishableKey.isNotBlank()

    init {
        if (isConfigured) {
            Clerk.initialize(context.applicationContext, publishableKey)
        }
    }

    override val state: Flow<AccountState> =
        combine(Clerk.isInitialized, Clerk.userFlow) { initialized, user ->
            when {
                !isConfigured -> AccountState.Unconfigured
                !initialized -> AccountState.Loading
                user == null -> AccountState.SignedOut
                else ->
                    AccountState.SignedIn(
                        userId = user.id,
                        email = user.primaryEmailAddress?.emailAddress,
                    )
            }
        }

    /**
     * Creating a sign-in with the `email_code` strategy both starts the attempt and sends the
     * code, so this is a single round trip.
     */
    override suspend fun startEmailCodeSignIn(email: String): Result<AuthStep> =
        guarded {
            val signIn =
                SignIn.create(SignIn.CreateParams.Strategy.EmailCode(identifier = email))
                    .valueOrThrow()
            signIn.asStep(email) { pending.set(Pending.Verification(signIn, email)) }
        }

    /**
     * Sign-up is two round trips: create the account shell, then ask Clerk to email the code.
     * Password is left unset — the account is code-verified, and a password can be added later
     * from the web app if the user wants one.
     */
    override suspend fun startEmailCodeSignUp(email: String): Result<AuthStep> =
        guarded {
            val created =
                SignUp.create(SignUp.CreateParams.Standard(emailAddress = email)).valueOrThrow()
            if (created.status == SignUp.Status.COMPLETE) {
                pending.set(null)
                return@guarded AuthStep.Complete
            }
            val prepared =
                created
                    .prepareVerification(SignUp.PrepareVerificationParams.Strategy.EmailCode())
                    .valueOrThrow()
            prepared.asStep(email) { pending.set(Pending.Registration(prepared, email)) }
        }

    override suspend fun signInWithPassword(email: String, password: String): Result<AuthStep> =
        guarded {
            val signIn =
                SignIn.create(
                        SignIn.CreateParams.Strategy.Password(
                            identifier = email,
                            password = password,
                        ),
                    )
                    .valueOrThrow()
            signIn.asStep(email) { pending.set(Pending.Verification(signIn, email)) }
        }

    override suspend fun verifyCode(code: String): Result<AuthStep> =
        guarded {
            when (val inFlight = pending.get()) {
                null -> throw AuthException("That code has expired. Request a new one.")
                is Pending.Verification -> {
                    val signIn =
                        inFlight.signIn
                            .attemptFirstFactor(
                                SignIn.AttemptFirstFactorParams.EmailCode(code = code),
                            )
                            .valueOrThrow()
                    signIn.asStep(inFlight.email) {
                        pending.set(inFlight.copy(signIn = signIn))
                    }
                }
                is Pending.Registration -> {
                    val signUp =
                        inFlight.signUp
                            .attemptVerification(
                                SignUp.AttemptVerificationParams.EmailCode(code = code),
                            )
                            .valueOrThrow()
                    signUp.asStep(inFlight.email) {
                        pending.set(inFlight.copy(signUp = signUp))
                    }
                }
            }
        }

    override suspend fun signOut(): Result<Unit> =
        guarded {
            Clerk.signOut().valueOrThrow()
            pending.set(null)
        }

    override suspend fun token(): String? {
        if (!isConfigured) return null
        val session = Clerk.session ?: return null
        return when (val result = session.fetchToken()) {
            is ClerkResult.Success -> result.value.jwt
            is ClerkResult.Failure -> {
                // Not fatal and not shown: sync treats a missing token as "not signed in yet"
                // and retries on its own schedule rather than interrupting reading.
                Log.w(TAG, "Clerk token unavailable: ${result.userMessage()}")
                null
            }
        }
    }

    /**
     * Maps a completed Clerk resource onto an [AuthStep], clearing [pending] once a session
     * exists and otherwise running [onPending] to record what the code should be submitted
     * against.
     */
    private inline fun SignIn.asStep(email: String, onPending: () -> Unit): AuthStep =
        when (status) {
            SignIn.Status.COMPLETE -> {
                pending.set(null)
                AuthStep.Complete
            }
            SignIn.Status.NEEDS_FIRST_FACTOR -> {
                onPending()
                AuthStep.NeedsCode(email)
            }
            else -> AuthStep.Unsupported(status.name)
        }

    private inline fun SignUp.asStep(email: String, onPending: () -> Unit): AuthStep =
        when (status) {
            SignUp.Status.COMPLETE -> {
                pending.set(null)
                AuthStep.Complete
            }
            SignUp.Status.MISSING_REQUIREMENTS -> {
                onPending()
                AuthStep.NeedsCode(email)
            }
            else -> AuthStep.Unsupported(status.name)
        }

    /** Unwraps a Clerk call, converting a failure into a displayable [AuthException]. */
    private fun <T : Any> ClerkResult<T, ClerkErrorResponse>.valueOrThrow(): T =
        when (this) {
            is ClerkResult.Success -> value
            is ClerkResult.Failure -> throw AuthException(userMessage())
        }

    /**
     * Runs [block], converting Clerk failures and any transport exception into a failed
     * [Result] carrying a displayable [AuthException]. Cancellation is never swallowed.
     */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        if (!isConfigured) {
            Result.failure(AuthException("This build has no cloud account configured."))
        } else {
            try {
                Result.success(block())
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: AuthException) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(AuthException(NETWORK_MESSAGE, error))
            }
        }

    /**
     * Clerk's own wording for the first error, which is written for end users ("Incorrect
     * code", "Couldn't find your account") and is better than anything this app would invent.
     * Falls back to a generic message when the failure carried no API error — typically a
     * transport failure, where Clerk's `throwable` is a stack trace, not a sentence.
     */
    private fun ClerkResult.Failure<ClerkErrorResponse>.userMessage(): String =
        error?.errors?.firstOrNull()?.let { it.longMessage ?: it.message } ?: NETWORK_MESSAGE

    private companion object {
        const val TAG = "BeriloAuth"
        const val NETWORK_MESSAGE = "Could not reach the account service. Check your connection."
    }
}

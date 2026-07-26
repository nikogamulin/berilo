package app.berilo.reader.sync.auth

import kotlinx.coroutines.flow.Flow

/**
 * The app's whole view of the auth provider (S3.2).
 *
 * Clerk's SDK is a process-wide singleton that requires a real `Application`, which would drag
 * every consumer onto Robolectric. This interface is the seam: [ClerkAuthGateway] is the one
 * class that touches Clerk, and view models, the sync engine and their tests depend only on
 * this. It also keeps the provider swappable, matching the same no-lock-in rule the LLM
 * provider interface follows (CLAUDE.md §2).
 *
 * Every method returns [Result]; failures carry an [AuthException] whose message is safe to
 * show. Nothing here ever throws for an expected outcome such as a wrong code.
 */
interface AuthGateway {

    /** The current account state, emitting on sign-in, sign-out and session restore. */
    val state: Flow<AccountState>

    /** True when this build has a Clerk publishable key at all. */
    val isConfigured: Boolean

    /**
     * Starts email-code sign-in for an existing account: Clerk emails a one-time code, which
     * the caller then passes to [verifyCode].
     *
     * This is the primary path on e-ink. It needs no password typed on a slow-refresh
     * keyboard, and unlike OAuth or passkeys it requires neither Play Services nor a usable
     * Custom Tab — neither of which can be assumed on a Boox device.
     */
    suspend fun startEmailCodeSignIn(email: String): Result<AuthStep>

    /** Starts email-code sign-up, creating a new account for [email]. Verify with [verifyCode]. */
    suspend fun startEmailCodeSignUp(email: String): Result<AuthStep>

    /**
     * Submits the one-time code for whichever of [startEmailCodeSignIn] / [startEmailCodeSignUp]
     * is in flight.
     */
    suspend fun verifyCode(code: String): Result<AuthStep>

    /** Signs in with a password, for users who prefer it over waiting on an email. */
    suspend fun signInWithPassword(email: String, password: String): Result<AuthStep>

    /** Signs out and clears the session from this device. Local books and notes are untouched. */
    suspend fun signOut(): Result<Unit>

    /**
     * A current Clerk session JWT for the `Authorization: Bearer` header on every sync request
     * (`docs/sync_api.md` §3), or null when signed out. Clerk caches and refreshes the token,
     * so this is safe to call before each request rather than holding one.
     */
    suspend fun token(): String?
}

package app.berilo.reader.sync.auth

/**
 * Whether this device has a cloud account attached, and what the sync surface may show (S3.2).
 *
 * [Unconfigured] is a *build* property, not a user state: a build made without a Clerk
 * publishable key (`android/berilo.properties`, see `app/build.gradle.kts`) has no service to
 * talk to at all. The app stays fully usable — reading, dictionary, notes and export are
 * local-first and never required an account — so this collapses the whole account surface to a
 * stated explanation rather than a dead sign-in form.
 */
sealed interface AccountState {

    /** No Clerk publishable key was baked into this build; cloud sync is unavailable. */
    data object Unconfigured : AccountState

    /** Clerk is still restoring any persisted session; state is not yet known. */
    data object Loading : AccountState

    /** Clerk is ready and no user is signed in. */
    data object SignedOut : AccountState

    /**
     * A user is signed in and sync may run.
     *
     * @property userId The Clerk user id (`user_...`), which is also the `user_id` every synced
     *   row is owned by server-side (`docs/sync_api.md` §2).
     * @property email The primary email address, for display only; null if Clerk has none.
     */
    data class SignedIn(val userId: String, val email: String?) : AccountState
}

/**
 * Where a sign-in attempt currently stands. Email-code sign-in is two-legged (request a code,
 * then submit it), so the caller needs to know which leg it is on.
 */
sealed interface AuthStep {

    /** The credential was accepted and a session now exists. */
    data object Complete : AuthStep

    /** A one-time code was sent to [email]; submit it with [AuthGateway.verifyCode]. */
    data class NeedsCode(val email: String) : AuthStep

    /**
     * Clerk needs a step this app does not implement (a second factor, a new password, an
     * SSO redirect). Surfaced verbatim rather than silently failing, with the web app as the
     * escape hatch — it runs the full Clerk component set.
     *
     * @property status The Clerk status string, for the message shown to the user.
     */
    data class Unsupported(val status: String) : AuthStep
}

/** A failed auth call, carrying a message fit to show the user. */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

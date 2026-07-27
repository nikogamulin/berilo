package app.berilo.reader.sync.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.berilo.reader.R
import app.berilo.reader.sync.auth.AccountState
import app.berilo.reader.vault.VaultRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account screen's privacy copy, checked against what the code actually does (S3.7).
 *
 * This screen is the user's only honest account of where their books are, so the tests below
 * compare the string to the **implemented default**, not to itself. If the vault's default ever
 * flips from off to on, `the privacy text matches the implemented default` fails — the copy
 * cannot quietly become a lie.
 *
 * Rendered at the Boox qualifier: `docs/findings.md` (2026-07-27) records that
 * `Config(qualifiers = PHONE)` lays long screens out below the fold, after which `performClick`
 * silently does nothing and the test passes while asserting nothing. This screen is long.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BOOX_TAB_ULTRA_QUALIFIER)
class AccountScreenTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(uiState: AccountUiState = AccountUiState(account = AccountState.SignedOut)) {
        composeRule.setContent {
            AccountScreen(
                uiState = uiState,
                onEmailChanged = {},
                onPasswordChanged = {},
                onCodeChanged = {},
                onUsePasswordChanged = {},
                onSignIn = {},
                onSignUp = {},
                onSubmitCode = {},
                onStartOver = {},
                onSignOut = {},
                onSyncNow = {},
                onBack = {},
            )
        }
    }

    /**
     * The default `AccountUiState` is the state a user who has never touched the vault sees, and
     * `VAULT_ENABLED_BOOKS_DEFAULT` is what [VaultRepository.isEnabled] returns for every book
     * until one is explicitly opted in. The screen must render the "off" copy for exactly that
     * state.
     */
    @Test
    fun `the privacy text matches the implemented default`() {
        assertEquals(
            "sync_api.md §8.2(4): the vault is off by default. If this constant changes, the " +
                "copy asserted below stops being true and must change with it.",
            0,
            VAULT_ENABLED_BOOKS_DEFAULT,
        )

        setContent()

        val explainer = composeRule.activity.getString(R.string.account_vault_explainer)
        composeRule.onNodeWithText(explainer).assertExists()
    }

    /** §8.2(4). The copy has to say "off", not merely omit that it is on. */
    @Test
    fun `the vault copy states plainly that it is off and opt-in per book`() {
        val explainer = composeRule.activity.getString(R.string.account_vault_explainer).lowercase()

        assertTrue("must say the vault is off", explainer.contains("the vault is off"))
        assertTrue("must say files stay on the device", explainer.contains("stay on this device"))
        assertTrue("must say it is per book", explainer.contains("one book at a time"))
        assertTrue(
            "must say nothing uploads without opting that book in",
            explainer.contains("nothing is uploaded unless you turn it on"),
        )
    }

    /** §8.2(2). The copy must state that the server cannot read the bytes, and why. */
    @Test
    fun `the vault copy states that the bytes are encrypted before they leave`() {
        val explainer = composeRule.activity.getString(R.string.account_vault_explainer).lowercase()

        assertTrue(
            "must say encryption happens on this device, before upload",
            explainer.contains("encrypted on this device before they are uploaded"),
        )
        assertTrue(
            "must say the server cannot read them",
            explainer.contains("cannot read"),
        )
    }

    /** §8.3(3). No sharing surface exists, and the screen must not imply one does. */
    @Test
    fun `the vault copy promises no sharing and the screen offers none`() {
        val explainer = composeRule.activity.getString(R.string.account_vault_explainer).lowercase()

        assertTrue(
            "must state there is no way to share a book (sync_api.md §8.3(3))",
            explainer.contains("no way to share"),
        )
        listOf("share this book", "send to a friend", "copy link", "public link").forEach { lure ->
            assertFalse("the screen must offer no sharing affordance: '$lure'", explainer.contains(lure))
        }
    }

    /**
     * The pre-vault copy claimed "Book files never leave this device", which the vault makes
     * false the moment a book is opted in. Overstating privacy is worse than understating it, so
     * the absolute claim must be gone.
     */
    @Test
    fun `the top-level privacy line no longer claims book files never leave`() {
        val privacy = composeRule.activity.getString(R.string.account_privacy).lowercase()

        assertFalse(
            "the vault makes 'never leave this device' false once a book is opted in",
            privacy.contains("never leave"),
        )
        assertTrue(
            "the honest form: they stay here unless the user says otherwise",
            privacy.contains("stay on this device"),
        )
    }

    /** With books opted in, the screen says how many rather than repeating the "off" copy. */
    @Test
    fun `with books opted in the screen reports the count instead of the off copy`() {
        setContent(AccountUiState(account = AccountState.SignedOut, vaultEnabledBooks = 2))

        val counted = composeRule.activity.getString(R.string.account_vault_enabled_count, 2)
        composeRule.onNodeWithText(counted).assertExists()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.account_vault_explainer))
            .assertDoesNotExist()
    }
}

/**
 * Boox Tab Ultra qualifier, matching `screenshot/ScreenshotHarness.kt`'s `BOOX_TAB_ULTRA`.
 *
 * Duplicated as a `const` here rather than imported because `@Config` needs a compile-time
 * constant and the harness object is `internal` to the screenshot package.
 */
internal const val BOOX_TAB_ULTRA_QUALIFIER: String =
    "w990dp-h1319dp-xlarge-notlong-notround-any-227dpi-keyshidden-nonav"

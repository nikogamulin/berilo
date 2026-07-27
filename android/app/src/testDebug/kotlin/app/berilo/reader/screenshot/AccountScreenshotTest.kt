package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.sync.auth.AccountState
import app.berilo.reader.sync.ui.AccountScreen
import app.berilo.reader.sync.ui.AccountUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S3.7: renders [AccountScreen] with the vault statement, at both device qualifiers and both
 * themes.
 *
 * The vault copy is the longest single block of text in the app and the one the user is most
 * likely to actually read, so it is worth seeing laid out at 227 dpi on a 10.3" e-ink panel as
 * well as on a phone — a wall of body text that wraps badly is a wall nobody reads, and this is
 * the screen where not reading it has consequences.
 *
 * Signed-out state deliberately: that is what a new user sees, and it is the state in which the
 * "the vault is off" claim has to be both true and legible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    AccountScreen(
                        uiState = AccountUiState(account = AccountState.SignedOut),
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
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file("account_vault", width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
    }

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = render("phone", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = render("phone", useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = render("boox", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = render("boox", useDarkTheme = true, theme = "dark")
}

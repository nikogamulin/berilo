package app.berilo.reader.reader

import android.app.Activity
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * S2.12: the callback that replaces the platform's text-selection popup. The defect it fixes
 * was invisible in code review because nothing *failed* — Readium simply fell through to
 * `WebView.startActionMode`, which is why the device showed Copy/Share/Zotero instead of
 * Berilo's actions. These tests assert the menu Berilo actually builds, and that a tap
 * dispatches the matching action, against a real `Menu` under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SelectionActionModeCallbackTest {

    private lateinit var activity: Activity
    private lateinit var menu: Menu
    private val dispatched = mutableListOf<SelectionAction>()
    private lateinit var callback: SelectionActionModeCallback

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        menu = PopupMenu(activity, View(activity)).menu
        dispatched.clear()
        callback = SelectionActionModeCallback { action, _ -> dispatched += action }
    }

    private fun createMode(): Boolean = callback.onCreateActionMode(RecordingActionMode(), menu)

    @Test
    fun `the platform menu is replaced wholesale`() {
        menu.add(Menu.NONE, android.R.id.shareText, 0, "Share")
        menu.add(Menu.NONE, android.R.id.copy, 1, "Copy")

        assertTrue(createMode())

        // Nothing inherited: no leftover platform entry, and in particular no PROCESS_TEXT
        // app (Zotero, Quick Share) survives into Berilo's bar.
        assertEquals(SelectionAction.entries.size, menu.size())
        assertEquals(
            SelectionAction.entries.map { it.menuItemId },
            (0 until menu.size()).map { menu.getItem(it).itemId },
        )
    }

    @Test
    fun `every action is offered, labelled and ordered`() {
        createMode()

        val expected = SelectionAction.entries.map { activity.getString(it.labelRes) }
        val actual = (0 until menu.size()).map { menu.getItem(it).title.toString() }
        assertEquals(expected, actual)
    }

    @Test
    fun `the menu is built once, not rebuilt on every prepare`() {
        createMode()
        // Returning false from onPrepareActionMode means "menu unchanged"; returning true
        // would make the toolbar re-lay-out on every selection nudge — a full-screen e-ink
        // flash per drag on the Boox.
        assertFalse(callback.onPrepareActionMode(RecordingActionMode(), menu))
        assertEquals(SelectionAction.entries.size, menu.size())
    }

    @Test
    fun `tapping an item dispatches its action`() {
        createMode()

        for (action in SelectionAction.entries) {
            dispatched.clear()
            assertTrue(callback.onActionItemClicked(RecordingActionMode(), item(action)))
            assertEquals(listOf(action), dispatched)
        }
    }

    @Test
    fun `an unrecognised item is declined, not misrouted`() {
        createMode()
        val foreign = menu.add(Menu.NONE, android.R.id.shareText, 99, "Share")

        assertFalse(callback.onActionItemClicked(RecordingActionMode(), foreign))
        assertEquals(emptyList<SelectionAction>(), dispatched)
    }

    @Test
    fun `the navigator configuration installs the callback`() {
        // The S2.12 defect in one assertion. Leaving selectionActionModeCallback null is not
        // an error anywhere in the build — Readium just uses the platform's popup — so this
        // is the only thing standing between a refactor and the bug coming straight back.
        val configuration = beriloNavigatorConfiguration { _, _ -> }
        assertTrue(configuration.selectionActionModeCallback is SelectionActionModeCallback)
    }

    @Test
    fun `the action mode is handed to the dispatcher, not finished by the callback`() {
        // The selection must survive until the handler has read it off the navigator:
        // ActionMode.finish() clears the WebView selection, so finishing here would make
        // currentSelection() return null in exactly the way S2.6's chrome buttons did.
        val mode = RecordingActionMode()
        var received: ActionMode? = null
        callback = SelectionActionModeCallback { _, m -> received = m }
        callback.onCreateActionMode(mode, menu)

        callback.onActionItemClicked(mode, item(SelectionAction.HIGHLIGHT))

        assertEquals(mode, received)
        assertEquals(0, mode.finishCount)
    }

    /** Minimal [ActionMode] double — the platform class is abstract and none of the
     * behaviour under test needs a real one, only the identity and `finish()` count. */
    private class RecordingActionMode : ActionMode() {
        var finishCount = 0
            private set

        private var modeTitle: CharSequence? = null
        private var modeSubtitle: CharSequence? = null

        override fun setTitle(title: CharSequence?) { modeTitle = title }
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) { modeSubtitle = subtitle }
        override fun setSubtitle(resId: Int) = Unit
        override fun getTitle(): CharSequence? = modeTitle
        override fun getSubtitle(): CharSequence? = modeSubtitle
        override fun setCustomView(view: View?) = Unit
        override fun getCustomView(): View? = null
        override fun invalidate() = Unit
        override fun finish() { finishCount++ }
        override fun getMenu(): Menu = throw UnsupportedOperationException()
        override fun getMenuInflater(): MenuInflater = throw UnsupportedOperationException()
    }

    private fun item(action: SelectionAction): MenuItem =
        requireNotNull(menu.findItem(action.menuItemId)) { "menu has no item for $action" }
}

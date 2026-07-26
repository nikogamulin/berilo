package app.berilo.reader.reader

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Replaces the platform's text-selection popup with Berilo's [SelectionAction] set (S2.12).
 *
 * Handed to Readium through `EpubNavigatorFragment.Configuration.selectionActionModeCallback`;
 * `R2BasicWebView.startActionMode` substitutes it for the callback the WebView would otherwise
 * use. Without it the reader shows the stock Android bar — Copy, Share, Web search, and every
 * installed `PROCESS_TEXT` handler (Zotero, Quick Share) — and none of Berilo's own actions.
 *
 * The callback deliberately does **not** finish the [ActionMode]: it hands it to [onAction]
 * instead. `ActionMode.finish()` clears the WebView selection, and every action needs to read
 * that selection off the navigator first (a suspending round trip through the JS bridge), so
 * ordering the two is the caller's business, not this class's.
 */
class SelectionActionModeCallback(
    private val onAction: (SelectionAction, ActionMode) -> Unit,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        // Clear first: the menu arrives carrying the platform's own entries, and anything
        // left behind reappears on the bar next to Berilo's.
        menu.clear()
        for (action in SelectionAction.entries) {
            menu.add(Menu.NONE, action.menuItemId, action.ordinal, action.labelRes)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    /** `false` = menu unchanged. Rebuilding it here would re-lay-out the floating toolbar on
     * every selection-handle nudge, which on e-ink is a visible flash per drag. */
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = SelectionAction.forMenuItemId(item.itemId) ?: return false
        onAction(action, mode)
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

/**
 * The navigator configuration Berilo's reader opens with.
 *
 * Exists as a named function so the one thing S2.12 turned on can be asserted off-device:
 * an omitted `selectionActionModeCallback` is not an error anywhere — Readium simply falls
 * back to the platform's popup — so nothing else in the build would notice its removal.
 */
@OptIn(ExperimentalReadiumApi::class)
fun beriloNavigatorConfiguration(
    onSelectionAction: (SelectionAction, ActionMode) -> Unit,
): EpubNavigatorFragment.Configuration =
    EpubNavigatorFragment.Configuration(
        selectionActionModeCallback = SelectionActionModeCallback(onSelectionAction),
    )

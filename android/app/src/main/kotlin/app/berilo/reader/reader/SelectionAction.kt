package app.berilo.reader.reader

import androidx.annotation.StringRes
import app.berilo.reader.R

/**
 * What Berilo offers on a live text selection (S2.12).
 *
 * These used to be `TextButton`s in the reader's top bar, which made them unreachable: the
 * chrome is hidden while reading, and the tap that reveals it drops the WebView selection the
 * actions depend on. They now populate Readium's `selectionActionModeCallback` instead, so
 * they appear *on* the selection and the selection is still alive when they fire.
 *
 * Declaration order is the order they appear in the floating toolbar — [HIGHLIGHT] and [NOTE]
 * lead because they are the ones that must survive an overflow on a narrow bar.
 */
enum class SelectionAction(@StringRes val labelRes: Int) {
    HIGHLIGHT(R.string.reader_highlight),
    NOTE(R.string.reader_note),
    DEFINE(R.string.reader_define),
    INTERPRET(R.string.reader_interpret),
    COPY(R.string.reader_copy),
    ;

    /**
     * Id this action's `MenuItem` carries. Deliberately the ordinal rather than an `R.id`:
     * the menu is built in code (not inflated), the ids only have to be unique within the
     * menu Berilo itself populates, and [forMenuItemId] can then reject anything else —
     * including a platform id that would otherwise collide with a hand-picked constant.
     */
    val menuItemId: Int get() = ordinal

    companion object {
        /** The action [menuItemId] belongs to, or `null` if the item is not one of Berilo's. */
        fun forMenuItemId(menuItemId: Int): SelectionAction? = entries.getOrNull(menuItemId)
    }
}

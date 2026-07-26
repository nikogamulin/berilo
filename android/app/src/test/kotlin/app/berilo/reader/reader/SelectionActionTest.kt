package app.berilo.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S2.12: the selection action set is the contract between the Readium `ActionMode.Callback`
 * (which owns menu ids) and [ReaderActivity]'s dispatch (which owns behaviour). Pure enum,
 * so it is testable without a WebView.
 */
class SelectionActionTest {

    @Test
    fun `annotation actions come first`() {
        // The two the S2.12 report was about must be the ones that fit before the overflow
        // on a narrow floating toolbar.
        assertEquals(
            listOf(SelectionAction.HIGHLIGHT, SelectionAction.NOTE),
            SelectionAction.entries.take(2),
        )
    }

    @Test
    fun `every action is offered exactly once`() {
        assertEquals(
            listOf(
                SelectionAction.HIGHLIGHT,
                SelectionAction.NOTE,
                SelectionAction.DEFINE,
                SelectionAction.INTERPRET,
                SelectionAction.COPY,
            ),
            SelectionAction.entries.toList(),
        )
    }

    @Test
    fun `menu ids are unique`() {
        val ids = SelectionAction.entries.map { it.menuItemId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `menu ids round-trip back to their action`() {
        for (action in SelectionAction.entries) {
            assertEquals(action, SelectionAction.forMenuItemId(action.menuItemId))
        }
    }

    @Test
    fun `an unknown menu id resolves to no action`() {
        // A menu item Berilo did not add (the platform's own, or a stale id after a
        // reorder) must be declined, not mapped onto whatever action shares its index.
        assertNull(SelectionAction.forMenuItemId(SelectionAction.entries.size))
        assertNull(SelectionAction.forMenuItemId(-1))
        assertNull(SelectionAction.forMenuItemId(android.R.id.copy))
    }

    @Test
    fun `every action carries a distinct label`() {
        val labels = SelectionAction.entries.map { it.labelRes }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(emptyList<SelectionAction>(), SelectionAction.entries.filter { it.labelRes == 0 })
    }
}

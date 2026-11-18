package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionStateTest {
    @Test
    fun creatingSessionMakesItActiveAndRetainsOtherSessions() {
        val initial = newLocalSessionsState("one")
        val next = createLocalSession(initial, "two", " Second ")

        assertEquals(listOf("one", "two"), next.sessions.map { it.id })
        assertEquals("two", next.activeId)
        assertEquals("Second", next.active.title)
    }

    @Test
    fun duplicateOrBlankIdsAreRejected() {
        val initial = newLocalSessionsState("one")
        assertSame(initial, createLocalSession(initial, "one"))
        assertSame(initial, createLocalSession(initial, " "))
    }

    @Test
    fun switchingSessionChangesActiveIdOnlyForKnownId() {
        val state = createLocalSession(newLocalSessionsState("one"), "two")
        assertEquals("one", selectLocalSession(state, "missing").activeId)
        assertEquals("two", selectLocalSession(state, "two").activeId)
    }

    @Test
    fun activeChatUpdateIsIsolatedToSelectedSession() {
        val state = createLocalSession(newLocalSessionsState("one"), "two")
        val changed = updateActiveLocalChat(
            state,
            ChatUiState(messages = listOf(ChatMessage("You", "session two")), draft = "draft")
        )
        val first = selectLocalSession(changed, "one").active
        val second = selectLocalSession(changed, "two").active
        assertNotEquals(first.chat.messages, second.chat.messages)
        assertEquals("draft", second.chat.draft)
        assertEquals("", first.chat.draft)
    }

    @Test
    fun renameTrimsAndLimitsTitleAndIgnoresBlank() {
        val state = createLocalSession(newLocalSessionsState("one"), "two")
        val renamed = renameLocalSession(state, "two", "  Named  ")
        assertEquals("Named", renamed.sessions.last().title)
        assertEquals(renamed, renameLocalSession(renamed, "two", "   "))
        assertEquals(MAX_SESSION_TITLE_LENGTH, renameLocalSession(renamed, "two", "x".repeat(100)).sessions.last().title.length)
    }

    @Test
    fun deletingActiveSessionSelectsAnotherAndLastDeletionCreatesFreshSession() {
        val two = createLocalSession(newLocalSessionsState("one"), "two")
        val deleted = deleteLocalSession(two, "two", "replacement")
        assertEquals("one", deleted.activeId)
        assertEquals(listOf("one"), deleted.sessions.map { it.id })

        val last = deleteLocalSession(newLocalSessionsState("one"), "one", "fresh")
        assertEquals("fresh", last.activeId)
        assertEquals(1, last.sessions.size)
    }

    @Test
    fun sessionCountIsBounded() {
        var state = newLocalSessionsState("s0")
        for (i in 1..MAX_LOCAL_SESSIONS + 5) state = createLocalSession(state, "s$i")
        assertEquals(MAX_LOCAL_SESSIONS, state.sessions.size)
        assertTrue(state.sessions.any { it.id == state.activeId })
    }
}

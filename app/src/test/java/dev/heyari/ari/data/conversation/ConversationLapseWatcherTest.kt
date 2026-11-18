package dev.heyari.ari.data.conversation

import dev.heyari.ari.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ari_ffi.FfiConversationMemory

class ConversationLapseWatcherTest {

    private val question = Message(text = "what are the fastest animals", isFromUser = true)
    private val answer = Message(text = "The cheetah and the peregrine falcon.", isFromUser = false)

    /** Each call to the engine gets the next of [answers]. */
    private fun engine(vararg answers: FfiConversationMemory): () -> FfiConversationMemory {
        val queue = ArrayDeque(answers.toList())
        return { queue.removeFirst() }
    }

    private fun repoWithChat() = ConversationLogRepository().apply {
        append(question)
        append(answer)
    }

    @Test
    fun `the note follows the chat once the wait runs out and the engine has forgotten`() {
        val repo = repoWithChat()
        runBlocking {
            ConversationLapseWatcher(repo, { "forgotten" }, this)
                .turnFinished(engine(FfiConversationMemory.Live(20uL), FfiConversationMemory.Lapsed))
        }
        val messages = repo.messages.value
        assertEquals(3, messages.size)
        assertEquals(listOf(question, answer), messages.take(2))
        assertEquals("forgotten", messages[2].text)
        assertTrue(messages[2].isNote)
        assertEquals(false, messages[2].isFromUser)
    }

    @Test
    fun `nothing remembered adds no note`() {
        val repo = repoWithChat()
        runBlocking {
            ConversationLapseWatcher(repo, { "forgotten" }, this).turnFinished(engine(FfiConversationMemory.None))
        }
        assertEquals(listOf(question, answer), repo.messages.value)
    }

    @Test
    fun `a lapse noticed twice adds one note`() {
        val repo = repoWithChat()
        runBlocking {
            val watcher = ConversationLapseWatcher(repo, { "forgotten" }, this)
            watcher.turnFinished(engine(FfiConversationMemory.Lapsed))
        }
        runBlocking {
            ConversationLapseWatcher(repo, { "forgotten" }, this).turnFinished(engine(FfiConversationMemory.Lapsed))
        }
        assertEquals(3, repo.messages.value.size)
    }

    @Test
    fun `an empty chat gets no note`() {
        val repo = ConversationLogRepository()
        runBlocking {
            ConversationLapseWatcher(repo, { "forgotten" }, this).turnFinished(engine(FfiConversationMemory.Lapsed))
        }
        assertEquals(emptyList<Message>(), repo.messages.value)
    }

    @Test
    fun `a new turn replaces the wait for the old deadline`() {
        val repo = repoWithChat()
        val started = System.nanoTime()
        runBlocking {
            val watcher = ConversationLapseWatcher(repo, { "forgotten" }, this)
            watcher.turnFinished(engine(FfiConversationMemory.Live(60_000uL), FfiConversationMemory.Lapsed))
            watcher.turnFinished(engine(FfiConversationMemory.Lapsed))
        }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("still waiting on the first turn's deadline: ${waitedMs}ms", waitedMs < 10_000)
        assertEquals(3, repo.messages.value.size)
    }
}

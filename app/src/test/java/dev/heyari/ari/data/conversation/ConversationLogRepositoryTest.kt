package dev.heyari.ari.data.conversation

import dev.heyari.ari.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLogRepositoryTest {

    @Test
    fun `append adds messages in insertion order`() {
        val repo = ConversationLogRepository()
        val user = Message(text = "what's the weather", isFromUser = true)
        val ari = Message(text = "Sunny, 24 degrees.", isFromUser = false)

        repo.append(user)
        repo.append(ari)

        assertEquals(listOf(user, ari), repo.messages.value)
    }

    @Test
    fun `messages starts empty`() {
        assertEquals(emptyList<Message>(), ConversationLogRepository().messages.value)
    }

    @Test
    fun `clear empties the log`() {
        val repo = ConversationLogRepository()
        repo.append(Message(text = "hello", isFromUser = true))

        repo.clear()

        assertEquals(emptyList<Message>(), repo.messages.value)
    }

    @Test
    fun `concurrent appends all land`() = runBlocking {
        val repo = ConversationLogRepository()

        val jobs = (0 until 100).map { i ->
            launch(Dispatchers.Default) {
                repo.append(Message(text = "m$i", isFromUser = i % 2 == 0))
            }
        }
        jobs.forEach { it.join() }

        assertEquals(100, repo.messages.value.size)
        assertEquals(
            (0 until 100).map { "m$it" }.toSet(),
            repo.messages.value.map { it.text }.toSet(),
        )
    }
}

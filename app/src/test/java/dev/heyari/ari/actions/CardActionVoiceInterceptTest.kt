package dev.heyari.ari.actions

import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.data.card.CardStateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardActionVoiceInterceptTest {

    // ---------------------------------------------------------------------------
    // Test seam helpers
    // ---------------------------------------------------------------------------

    /**
     * Minimal [CardStateSource] backed by a single card with one button.
     * No Android framework — just a plain [MutableStateFlow].
     */
    private fun makeInterceptWithCardButton(id: String, label: String): CardActionVoiceIntercept {
        val action = CardAction(
            id = id,
            label = label,
            utterance = "utterance:$id",
            speak = null,
            style = CardAction.Style.DEFAULT,
        )
        val card = Card(
            id = "card-test",
            skillId = "test-skill",
            title = "Pick a service",
            subtitle = null,
            body = null,
            icon = null,
            countdownToTsMs = null,
            startedAtTsMs = null,
            progress = null,
            accent = Card.Accent.DEFAULT,
            actions = listOf(action),
            onComplete = null,
            onCancel = null,
        )
        val source = object : CardStateSource {
            override val state: StateFlow<List<Card>> =
                MutableStateFlow(listOf(card))
        }
        return CardActionVoiceIntercept(source)
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun matchesMultiWordLabel() {
        // A card button with label "Apple Music" should match the spoken "apple music"
        val intercept = makeInterceptWithCardButton(id = "apple_music", label = "Apple Music")
        val match = intercept.resolve("apple music")
        assertNotNull(match)
        assertEquals("apple_music", match!!.action.id)
    }

    @Test
    fun stillMatchesSingleWord() {
        val intercept = makeInterceptWithCardButton(id = "spotify", label = "Spotify")
        val match = intercept.resolve("spotify")
        assertNotNull(match)
        assertEquals("spotify", match!!.action.id)
    }

    @Test
    fun matchesSingleWordById() {
        // The id matcher also works (id = "spotify", say "spotify")
        val intercept = makeInterceptWithCardButton(id = "spotify", label = "Spotify")
        assertNotNull(intercept.resolve("spotify"))
    }

    @Test
    fun matchIsCaseInsensitive() {
        val intercept = makeInterceptWithCardButton(id = "tidal", label = "Tidal")
        assertNotNull(intercept.resolve("TIDAL"))
    }

    @Test
    fun emptyInputReturnsNull() {
        val intercept = makeInterceptWithCardButton(id = "spotify", label = "Spotify")
        assertNull(intercept.resolve(""))
        assertNull(intercept.resolve("   "))
    }

    @Test
    fun nonMatchingInputReturnsNull() {
        val intercept = makeInterceptWithCardButton(id = "spotify", label = "Spotify")
        assertNull(intercept.resolve("deezer"))
    }

    @Test
    fun trailingPunctuationIsStripped() {
        val intercept = makeInterceptWithCardButton(id = "spotify", label = "Spotify")
        assertNotNull(intercept.resolve("spotify."))
        assertNotNull(intercept.resolve("spotify!"))
    }
}

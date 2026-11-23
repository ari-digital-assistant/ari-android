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
    private fun makeInterceptWithCardButton(
        id: String,
        label: String,
        endings: List<String> = ENGLISH_ENDINGS,
    ): CardActionVoiceIntercept {
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
        return CardActionVoiceIntercept(source) { endings }
    }

    private companion object {
        /** As `values/strings.xml` has them. */
        val ENGLISH_ENDINGS = listOf("s", "d", "ed", "led")
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

    @Test
    fun aLabelHeardWithAnEndingStillPressesTheButton() {
        // Kroko heard "cancel" as "cancelled" on 2026-11-22, and the timer's
        // Cancel button wasn't pressed.
        val intercept = makeInterceptWithCardButton(id = "cancel", label = "Cancel")
        for (heard in listOf("cancelled", "Canceled.", "cancels")) {
            assertEquals(heard, "cancel", intercept.resolve(heard)?.action?.id)
        }
    }

    @Test
    fun onlyTheListedEndingsCount() {
        val intercept = makeInterceptWithCardButton(id = "yes", label = "Yes")
        assertNull(intercept.resolve("yesterday"))
        assertNull(intercept.resolve("yesss"))
        assertNull(makeInterceptWithCardButton(id = "cancel", label = "Cancel", endings = emptyList()).resolve("cancelled"))
    }
}

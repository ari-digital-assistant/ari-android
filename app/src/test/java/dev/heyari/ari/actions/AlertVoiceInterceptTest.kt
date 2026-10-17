package dev.heyari.ari.actions

import dev.heyari.ari.notifications.AlertAction
import dev.heyari.ari.notifications.AlertSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertVoiceInterceptTest {

    private val englishPhrases = listOf("stop", "stopped", "stops")

    private fun alert(id: String, stopLabel: String? = "Stop") = AlertSpec(
        id = id,
        skillId = "dev.heyari.timer",
        title = "Pasta timer done",
        body = null,
        urgency = AlertSpec.Urgency.CRITICAL,
        sound = "asset:timer.mp3",
        speechLoop = "Pasta timer",
        autoStopMs = 120_000,
        maxCycles = 12,
        fullTakeover = true,
        actions = listOfNotNull(stopLabel?.let {
            AlertAction(id = "stop_alert", label = it, utterance = null, style = AlertAction.Style.PRIMARY)
        }),
        icon = null,
    )

    private fun intercept(sounding: List<AlertSpec>, phrases: List<String> = englishPhrases) =
        AlertVoiceIntercept(stopPhrases = { phrases }, sounding = { sounding })

    @Test
    fun stopOverARingingAlertStopsIt() {
        assertEquals("alert_t1", intercept(listOf(alert("alert_t1"))).resolve("stop"))
    }

    @Test
    fun theRecognisersMishearingsOfStopCountToo() {
        val i = intercept(listOf(alert("alert_t1")))
        assertEquals("alert_t1", i.resolve("stopped"))
        assertEquals("alert_t1", i.resolve("Stops."))
    }

    @Test
    fun caseAndTrailingPunctuationDontMatter() {
        val i = intercept(listOf(alert("alert_t1")))
        assertEquals("alert_t1", i.resolve("  Stop!  "))
        assertEquals("alert_t1", i.resolve("STOP."))
    }

    @Test
    fun theSkillsOwnStopLabelWorksInAnyLanguage() {
        // Italian label, no Italian phrase list: the button's label alone is enough.
        val i = intercept(listOf(alert("alert_t1", stopLabel = "Ferma")), phrases = emptyList())
        assertEquals("alert_t1", i.resolve("ferma"))
        assertNull(i.resolve("stop"))
    }

    @Test
    fun theStopPhrasesWorkOnAnAlertWithNoButtons() {
        assertEquals("alert_t1", intercept(listOf(alert("alert_t1", stopLabel = null))).resolve("stop"))
    }

    @Test
    fun nothingRingingMeansNoIntercept() {
        assertNull(intercept(emptyList()).resolve("stop"))
    }

    @Test
    fun longerRequestsGoToTheEngine() {
        // "stop the timer" is the timer skill's to answer, not a bare stop.
        val i = intercept(listOf(alert("alert_t1")))
        assertNull(i.resolve("stop the timer"))
        assertNull(i.resolve("what's the weather"))
        assertNull(i.resolve(""))
        assertNull(i.resolve("   "))
    }
}

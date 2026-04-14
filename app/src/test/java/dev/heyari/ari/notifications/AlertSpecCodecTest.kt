package dev.heyari.ari.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertSpecCodecTest {

    private val sample = AlertSpec(
        id = "alert_pasta",
        skillId = "dev.heyari.timer",
        title = "Pasta timer done",
        body = null,
        urgency = AlertSpec.Urgency.CRITICAL,
        sound = AlertSpec.SoundToken.ALARM,
        speechLoop = "Pasta timer",
        autoStopMs = 120_000L,
        maxCycles = 12,
        fullTakeover = true,
        actions = listOf(
            AlertAction(
                id = "stop_alert",
                label = "Stop",
                utterance = null,
                style = AlertAction.Style.PRIMARY,
            ),
        ),
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val json = AlertSpecCodec.encode(sample)
        val decoded = AlertSpecCodec.decode(json)
        assertEquals(sample, decoded)
    }

    @Test
    fun decodeReturnsNullOnGarbage() {
        assertNull(AlertSpecCodec.decode("not json"))
    }

    @Test
    fun decodeReturnsNullWhenIdMissing() {
        // id is the only mandatory field for the codec — without it we can't
        // route the alert anywhere meaningful.
        assertNull(AlertSpecCodec.decode("""{"title":"x"}"""))
    }

    @Test
    fun decodeFillsDefaultsForOptional() {
        val decoded = AlertSpecCodec.decode("""{"id":"a","title":"T"}""")!!
        assertEquals(AlertSpec.Urgency.NORMAL, decoded.urgency)
        assertEquals(AlertSpec.SoundToken.NOTIFICATION, decoded.sound)
        assertEquals(120_000L, decoded.autoStopMs)
        assertEquals(12, decoded.maxCycles)
        assertEquals(false, decoded.fullTakeover)
        assertNull(decoded.body)
        assertNull(decoded.speechLoop)
    }

    @Test
    fun assetSoundRoundTrips() {
        val withAsset = sample.copy(sound = "asset:custom_ding.wav")
        val decoded = AlertSpecCodec.decode(AlertSpecCodec.encode(withAsset))
        assertEquals("asset:custom_ding.wav", decoded?.sound)
    }

    @Test
    fun emptyActionsRoundTrip() {
        val empty = sample.copy(actions = emptyList())
        val decoded = AlertSpecCodec.decode(AlertSpecCodec.encode(empty))
        assertNotNull(decoded)
        assertEquals(emptyList<AlertAction>(), decoded!!.actions)
    }
}

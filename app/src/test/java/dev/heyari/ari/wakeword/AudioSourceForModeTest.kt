package dev.heyari.ari.wakeword

import android.media.MediaRecorder
import dev.heyari.ari.voice.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceForModeTest {
    @Test
    fun `normal mode captures on VOICE_RECOGNITION`() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            audioSourceFor(CaptureMode.NORMAL),
        )
    }

    @Test
    fun `conversation mode keeps VOICE_COMMUNICATION for hardware AEC`() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            audioSourceFor(CaptureMode.CONVERSATION),
        )
    }
}

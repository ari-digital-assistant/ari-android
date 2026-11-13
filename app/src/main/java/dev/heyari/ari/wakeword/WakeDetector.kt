package dev.heyari.ari.wakeword

import java.io.Closeable
import java.nio.ByteBuffer

/** A wake word engine as the capture loop and the tuner use it. */
interface WakeDetector : Closeable {
    /** Whether the wake word was detected in [samples]. */
    fun processAudio(samples: ShortArray): Boolean

    fun reset()
}

/**
 * This model's engine at [point]. [modelBuffer] holds [WakeWordModel.assetFilename];
 * an openWakeWord model also needs the feature models all of them share, which
 * [load] reads from the app's assets.
 */
fun WakeWordModel.detector(
    modelBuffer: ByteBuffer,
    point: OperatingPoint,
    resetOnDetection: Boolean,
    load: (String) -> ByteBuffer?,
): WakeDetector = when (engine) {
    WakeEngine.MICRO_WAKE_WORD -> MicroWakeWord(
        modelBuffer = modelBuffer,
        featureStepSizeMs = featureStepSizeMs,
        probabilityCutoff = point.probabilityCutoff,
        slidingWindowSize = point.slidingWindowSize,
        resetOnDetection = resetOnDetection,
    )
    WakeEngine.OPEN_WAKE_WORD -> OpenWakeWord(
        melspectrogram = checkNotNull(load(OWW_MELSPECTROGRAM_ASSET)) { "$OWW_MELSPECTROGRAM_ASSET would not load" },
        embedding = checkNotNull(load(OWW_EMBEDDING_ASSET)) { "$OWW_EMBEDDING_ASSET would not load" },
        classifier = modelBuffer,
        probabilityCutoff = point.probabilityCutoff,
        resetOnDetection = resetOnDetection,
    )
}

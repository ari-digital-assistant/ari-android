package dev.heyari.ari.wakeword

/**
 * A wake word model bundled in app assets. The micro params come from the
 * companion .json that ships alongside each .tflite — kept in code rather
 * than parsed at runtime because they're tiny, fixed, and we want a compile-
 * time guarantee that every registered model has them.
 */
data class WakeWordModel(
    val id: String,
    val displayName: String,
    val assetFilename: String,
    val probabilityCutoff: Float,
    val slidingWindowSize: Int,
    val featureStepSizeMs: Int,
)

object WakeWordRegistry {
    val all: List<WakeWordModel> = listOf(
        WakeWordModel(
            id = "hey_ari",
            displayName = "Hey Ari",
            assetFilename = "hey_ari.tflite",
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
            featureStepSizeMs = 10,
        ),
        WakeWordModel(
            id = "ok_ari",
            displayName = "OK Ari",
            assetFilename = "ok_ari.tflite",
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
            featureStepSizeMs = 10,
        ),
        WakeWordModel(
            id = "hey_jarvis",
            displayName = "Hey Jarvis",
            assetFilename = "hey_jarvis.tflite",
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
            featureStepSizeMs = 10,
        ),
    )

    val default: WakeWordModel = all.first { it.id == "hey_ari" }

    fun byId(id: String?): WakeWordModel = all.firstOrNull { it.id == id } ?: default
}

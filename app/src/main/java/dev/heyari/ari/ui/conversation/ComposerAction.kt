package dev.heyari.ari.ui.conversation

enum class ComposerAction { Mic, Stop, Send }

/** The composer's trailing button: Stop while dictating (tap cancels), Mic when
 *  the field is blank (tap starts dictation), Send otherwise. */
fun composerAction(inputText: String, isDictating: Boolean): ComposerAction = when {
    isDictating -> ComposerAction.Stop
    inputText.isBlank() -> ComposerAction.Mic
    else -> ComposerAction.Send
}

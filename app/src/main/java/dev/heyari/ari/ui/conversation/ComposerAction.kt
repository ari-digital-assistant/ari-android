package dev.heyari.ari.ui.conversation

enum class ComposerAction { Mic, Send }

fun composerAction(inputText: String): ComposerAction =
    if (inputText.isBlank()) ComposerAction.Mic else ComposerAction.Send

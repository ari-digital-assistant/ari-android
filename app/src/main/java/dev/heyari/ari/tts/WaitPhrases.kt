package dev.heyari.ari.tts

import android.content.Context
import dev.heyari.ari.R

/**
 * Random "please wait" phrase. Shown + spoken while the STT model warms up
 * and reused for the conversation screen's slow-reply filler. Resolves
 * against the active per-app locale, so Italian users get Italian phrases.
 */
fun pleaseWaitPhrase(context: Context): String =
    context.resources.getStringArray(R.array.please_wait_phrases).random()

/**
 * Random "say that again" phrase, spoken once the STT model is warm — the
 * original words have already aged out of the 2 s capture buffer.
 */
fun pleaseRepeatPhrase(context: Context): String =
    context.resources.getStringArray(R.array.please_repeat_phrases).random()

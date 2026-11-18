package dev.heyari.ari.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.heyari.ari.R
import dev.heyari.ari.llm.LlmModel
import dev.heyari.ari.llm.MemoryFit
import dev.heyari.ari.llm.PhoneMemory
import dev.heyari.ari.ui.formatBytes
import dev.heyari.ari.ui.theme.LocalAriSemanticColors

/**
 * One line under an on-device model saying how it suits this phone's memory
 * (ari-android#42): what it uses, a warning when that's tight, or the phone
 * size it needs when it's too big. [fit] is null when Android won't report
 * the phone's memory, and the line then just says what the model uses.
 */
@Composable
fun LlmMemoryLine(
    model: LlmModel,
    fit: MemoryFit?,
    phoneGb: Int?,
    plainColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val uses = formatBytes(model.runtimeBytes)
    val (text, color) = when {
        fit == MemoryFit.TooBig ->
            stringResource(R.string.llm_memory_too_big, PhoneMemory.minimumGb(model)) to
                MaterialTheme.colorScheme.error
        fit == MemoryFit.Tight && phoneGb != null ->
            stringResource(R.string.llm_memory_tight, uses, phoneGb) to
                LocalAriSemanticColors.current.warning
        else -> stringResource(R.string.llm_memory_fits, uses) to plainColor
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

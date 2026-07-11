package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R

/**
 * Always-listening (wake word) control for the conversation top bar.
 *
 * When armed, a STEADY (non-animated) tinted pill sits behind the switch —
 * momentary listening/processing already animates elsewhere (the ambient
 * field), so this control conveys its persistent state with stillness, not a
 * pulse. The halo is a fixed-size pill hugging the visible track: applying a
 * rounded-50% shape to the Switch itself renders as a circle, because Material
 * inflates the switch bounds to a 48dp minimum touch target.
 */
@Composable
fun WakeSwitch(armed: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(
        if (armed) R.string.wake_switch_armed_description else R.string.wake_switch_off_description
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (armed) {
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(percent = 50),
                    )
            )
        }
        Switch(
            checked = armed,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = description },
            thumbContent = {
                Icon(
                    imageVector = if (armed) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}

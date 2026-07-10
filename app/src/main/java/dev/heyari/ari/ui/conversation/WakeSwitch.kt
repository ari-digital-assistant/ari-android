package dev.heyari.ari.ui.conversation

import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Always-listening (wake word) control for the conversation top bar.
 *
 * Persistence is conveyed by a STEADY halo when armed, not an animation —
 * momentary listening/processing already animates elsewhere (the ambient
 * field), so this switch itself never pulses.
 */
@Composable
fun WakeSwitch(armed: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val steady = if (armed) {
        Modifier.clip(RoundedCornerShape(50)).border(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            shape = RoundedCornerShape(50),
        )
    } else {
        Modifier
    }
    Switch(
        checked = armed,
        onCheckedChange = onToggle,
        modifier = modifier.then(steady).semantics {
            contentDescription = if (armed) "Always listening for Hey Ari, on" else "Always listening, off"
        },
        thumbContent = {
            Icon(
                imageVector = if (armed) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

package dev.heyari.ari.ui.bugreport

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import kotlin.math.roundToInt

private val FAB_SIZE = 56.dp
private val EDGE_MARGIN = 12.dp

/**
 * The bug-report button, in testing builds only.
 *
 * Round, parked top-right, and draggable. Both bottom corners are already
 * spoken for by the mic button and the input bar, and a permanent overlay will
 * eventually sit on top of something a tester needs to see — so rather than
 * guess a corner that is always safe, let them move it. It snaps to the nearer
 * side on release so it cannot be stranded mid-screen, and where they put it
 * is remembered.
 *
 * [container] is measured by the parent because a child cannot ask its parent
 * how big it is; the button needs it to know where the edges are.
 */
@Composable
fun BugReportFab(
    container: IntSize,
    position: Pair<Float, Float>?,
    onMoved: (Float, Float) -> Unit,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val fabPx = with(density) { FAB_SIZE.toPx() }
    val marginPx = with(density) { EDGE_MARGIN.toPx() }

    val maxX = (container.width - fabPx - marginPx).coerceAtLeast(marginPx)
    val maxY = (container.height - fabPx - marginPx).coerceAtLeast(marginPx)

    // Pixels while dragging, fractions when stored: absolute coordinates from
    // a portrait session point off the side of a landscape one.
    var offsetX by remember { mutableFloatStateOf(Float.NaN) }
    var offsetY by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(position, container) {
        if (container.width == 0 || container.height == 0) return@LaunchedEffect
        if (offsetX.isNaN() || offsetY.isNaN()) {
            val (fx, fy) = position ?: (1f to 0f)
            offsetX = marginPx + fx * (maxX - marginPx)
            offsetY = marginPx + fy * (maxY - marginPx)
        }
    }

    if (offsetX.isNaN() || offsetY.isNaN()) return

    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(FAB_SIZE)
            .pointerInput(maxX, maxY) {
                detectDragGestures(
                    onDrag = { change, dragged ->
                        change.consume()
                        offsetX = (offsetX + dragged.x).coerceIn(marginPx, maxX)
                        offsetY = (offsetY + dragged.y).coerceIn(marginPx, maxY)
                    },
                    onDragEnd = {
                        // Snap to the nearer side. Floating mid-screen it
                        // covers content wherever it lands; against an edge it
                        // covers a margin.
                        val snapped =
                            if (offsetX + fabPx / 2 < container.width / 2f) marginPx else maxX
                        offsetX = snapped
                        val xSpan = (maxX - marginPx).coerceAtLeast(1f)
                        val ySpan = (maxY - marginPx).coerceAtLeast(1f)
                        onMoved((snapped - marginPx) / xSpan, (offsetY - marginPx) / ySpan)
                    },
                )
            },
    ) {
        Icon(
            imageVector = Icons.Filled.BugReport,
            contentDescription = stringResource(R.string.bug_report_fab_description),
        )
    }
}

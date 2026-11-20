package dev.heyari.ari.ui.bugreport

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.heyari.ari.R
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Material's small FAB, which [SmallFloatingActionButton] draws at. */
private val FAB_SIZE = 40.dp
private val EDGE_MARGIN = 12.dp

/** Faded while untouched, so whatever it sits on still reads through it. */
private const val IDLE_ALPHA = 0.6f

/**
 * Material's small top app bar. Not exposed as a constant anywhere public, so
 * it is named here rather than left as a bare 64 in the middle of the maths.
 */
private val APP_BAR_HEIGHT = 64.dp

/**
 * The bug-report button, in testing builds only.
 *
 * Small, faded, and draggable. Both bottom corners are already spoken for by
 * the mic button and the input bar, and a permanent overlay will eventually
 * sit on top of something a tester needs to see — so rather than guess a spot
 * that is always safe, let them move it. It snaps to the nearer side on
 * release so it cannot be stranded mid-screen, and where they put it is
 * remembered. Nobody expects a floating button to move, so [showHint] has it
 * say so once (ari-android#55).
 *
 * [container] is measured by the parent because a child cannot ask its parent
 * how big it is; the button needs it to know where the edges are. The app
 * draws edge to edge, so that container includes the status and navigation
 * bars — the insets below keep the button out from underneath them, and out
 * of the app bar, where the listening-mode switch already lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportFab(
    container: IntSize,
    position: Pair<Float, Float>?,
    showHint: Boolean,
    onHintShown: () -> Unit,
    onMoved: (Float, Float) -> Unit,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val fabPx = with(density) { FAB_SIZE.toPx() }
    val marginPx = with(density) { EDGE_MARGIN.toPx() }

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val minY = with(density) { (statusBar + APP_BAR_HEIGHT + EDGE_MARGIN).toPx() }
    val bottomPx = with(density) { navBar.toPx() }

    val minX = marginPx
    val maxX = (container.width - fabPx - marginPx).coerceAtLeast(minX)
    val maxY = (container.height - fabPx - marginPx - bottomPx).coerceAtLeast(minY)

    // Pixels while dragging, fractions when stored: absolute coordinates from
    // a portrait session point off the side of a landscape one.
    var offsetX by remember { mutableFloatStateOf(Float.NaN) }
    var offsetY by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(position, container) {
        if (container.width == 0 || container.height == 0) return@LaunchedEffect
        if (offsetX.isNaN() || offsetY.isNaN()) {
            // Default is halfway down the right edge. The top is where the
            // download and update banners appear, and it covered them.
            val (fx, fy) = position ?: (1f to 0.5f)
            offsetX = minX + fx * (maxX - minX)
            offsetY = minY + fy * (maxY - minY)
        }
    }

    if (offsetX.isNaN() || offsetY.isNaN()) return

    val hint = rememberTooltipState(isPersistent = true)
    val hintDue by rememberUpdatedState(showHint)
    // Keyed on Unit, not on showHint: recording the hint as shown flips
    // showHint to false, and restarting this effect would cancel show() and
    // take the hint straight back down.
    LaunchedEffect(Unit) {
        snapshotFlow { hintDue }.first { it }
        onHintShown()
        hint.show()
    }

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }

    TooltipBox(
        // Start of the button, which sits on the end edge until first dragged.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Start),
        tooltip = { PlainTooltip { Text(stringResource(R.string.bug_report_fab_hint)) } },
        state = hint,
        modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
        // Not focusable, so the app stays usable while the hint is up; and no
        // long-press to show it, which would fight the drag.
        focusable = false,
        enableUserInput = false,
    ) {
        SmallFloatingActionButton(
            onClick = {
                hint.dismiss()
                onClick()
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            interactionSource = interactions,
            modifier = Modifier
                .size(FAB_SIZE)
                .alpha(if (pressed || dragging || hint.isVisible) 1f else IDLE_ALPHA)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            hint.dismiss()
                        },
                        onDrag = { change, dragged ->
                            change.consume()
                            offsetX = (offsetX + dragged.x).coerceIn(minX, maxX)
                            offsetY = (offsetY + dragged.y).coerceIn(minY, maxY)
                        },
                        onDragEnd = {
                            dragging = false
                            // Snap to the nearer side. Floating mid-screen it
                            // covers content wherever it lands; against an edge it
                            // covers a margin.
                            val snapped =
                                if (offsetX + fabPx / 2 < container.width / 2f) minX else maxX
                            offsetX = snapped
                            val xSpan = (maxX - minX).coerceAtLeast(1f)
                            val ySpan = (maxY - minY).coerceAtLeast(1f)
                            onMoved((snapped - minX) / xSpan, (offsetY - minY) / ySpan)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = stringResource(R.string.bug_report_fab_description),
            )
        }
    }
}

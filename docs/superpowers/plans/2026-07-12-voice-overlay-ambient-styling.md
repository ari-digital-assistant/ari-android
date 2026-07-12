# Voice-Overlay Ambient Styling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the chat screen's state-driven ambient treatment to the voice overlay card — a reactive border plus a bounded, card-hugging halo that breathes with the voice phase.

**Architecture:** Reuse the dependency-free draw primitives in `ui/conversation/` (`ambientComposerBorder`, plus a new `ambientCardHalo`) on `VoiceOverlayContent`, driven by the same `AmbientState`. Lift the existing `VoiceState → VoicePhase` mapper to a shared home and add an overlay-local `toOverlayAmbientState()`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Compose `drawBehind`, `rememberInfiniteTransition`, JUnit4 JVM unit tests.

## Global Constraints

- **Frontend-only, ari-android only.** No `ari-engine`, skill, `VoiceOverlayActivity`, or ari-linux changes.
- **No screen-fill.** The overlay window is `@android:style/Theme.Translucent.NoTitleBar` with no scrim — the halo must stay bounded within the card's existing 12dp inset and feather to zero, never painting over the live background beyond that band.
- **Corner radius:** the overlay card is `RoundedCornerShape(20.dp)`; primitives must draw to a 20.dp corner there. `ambientComposerBorder`'s default stays `24.dp` so the composer is untouched.
- **Reduce-motion aware:** reuse the existing `animationsEnabled` gate — static fallback when the system animator scale is 0.
- **Material You clean:** accent is `colorScheme.primary`; no hardcoded colours.
- **Behaviour-preserving refactor:** lifting the mapper must not change the chat screen's ambient behaviour (identical `when` arms).
- **Tests assert exact values** (project rule).
- **Gradle needs `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`** on this machine.
- **Device verification** on Keith's connected device / emulator — his visual sign-off.

## File Structure

- `app/src/main/java/dev/heyari/ari/ui/conversation/AmbientState.kt` — lift shared `toVoicePhase()`, add `toOverlayAmbientState()` (Task 1).
- `app/src/test/java/dev/heyari/ari/ui/conversation/AmbientMappingTest.kt` — **new**, mapper unit tests (Task 1).
- `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` — drop the private mapper, use the shared one (Task 1).
- `app/src/main/java/dev/heyari/ari/ui/conversation/AmbientField.kt` — corner param on `ambientComposerBorder`, new `ambientCardHalo` (Task 2).
- `app/src/main/java/dev/heyari/ari/voice/VoiceOverlayContent.kt` — attach border + halo (Task 3).

---

### Task 1: Shared state mapper + overlay ambient mapping

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/AmbientState.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` (remove private `toVoicePhase`, lines 380-394)
- Test: `app/src/test/java/dev/heyari/ari/ui/conversation/AmbientMappingTest.kt` (create)

**Interfaces:**
- Produces: top-level `fun VoiceState.toVoicePhase(): VoicePhase` and `fun VoiceState.toOverlayAmbientState(): AmbientState` in package `dev.heyari.ari.ui.conversation`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/ui/conversation/AmbientMappingTest.kt`:

```kotlin
package dev.heyari.ari.ui.conversation

import dev.heyari.ari.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientMappingTest {
    @Test
    fun `toVoicePhase maps every voice state`() {
        assertEquals(VoicePhase.Idle, VoiceState.Idle.toVoicePhase())
        assertEquals(VoicePhase.Idle, VoiceState.Preparing("x").toVoicePhase())
        assertEquals(VoicePhase.Listening, VoiceState.Listening("").toVoicePhase())
        assertEquals(VoicePhase.Thinking, VoiceState.Thinking.toVoicePhase())
        assertEquals(VoicePhase.Speaking, VoiceState.Responding("hi").toVoicePhase())
        assertEquals(VoicePhase.Idle, VoiceState.Error("e").toVoicePhase())
    }

    @Test
    fun `toOverlayAmbientState maps every state, Preparing shows Thinking`() {
        assertEquals(AmbientState.Idle, VoiceState.Idle.toOverlayAmbientState())
        assertEquals(AmbientState.Thinking, VoiceState.Preparing("x").toOverlayAmbientState())
        assertEquals(AmbientState.Listening, VoiceState.Listening("").toOverlayAmbientState())
        assertEquals(AmbientState.Thinking, VoiceState.Thinking.toOverlayAmbientState())
        assertEquals(AmbientState.Speaking, VoiceState.Responding("hi").toOverlayAmbientState())
        assertEquals(AmbientState.Idle, VoiceState.Error("e").toOverlayAmbientState())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.ui.conversation.AmbientMappingTest"`
Expected: FAIL — unresolved reference `toVoicePhase` / `toOverlayAmbientState` (they're still private in the VM).

- [ ] **Step 3: Add the shared mappers to `AmbientState.kt`**

Append to `app/src/main/java/dev/heyari/ari/ui/conversation/AmbientState.kt` (and add the import under the `package` line):

```kotlin
import dev.heyari.ari.voice.VoiceState
```

```kotlin

/**
 * Map the voice pipeline's [VoiceState] onto the presentation [VoicePhase].
 * Shared by the chat screen and the voice overlay. Preparing (cold STT warm-up)
 * and Error are transient and fold to Idle so the chat aura doesn't twitch on
 * them; Responding is the phase where Ari is speaking back.
 */
fun VoiceState.toVoicePhase(): VoicePhase = when (this) {
    is VoiceState.Idle -> VoicePhase.Idle
    is VoiceState.Preparing -> VoicePhase.Idle
    is VoiceState.Listening -> VoicePhase.Listening
    is VoiceState.Thinking -> VoicePhase.Thinking
    is VoiceState.Responding -> VoicePhase.Speaking
    is VoiceState.Error -> VoicePhase.Idle
}

/**
 * Ambient state for the voice overlay. The overlay has no typed-path or wake
 * inputs, and Preparing shows the Thinking treatment (the overlay renders a
 * "connecting…" spinner for it, so the border/halo should be alive).
 */
fun VoiceState.toOverlayAmbientState(): AmbientState =
    if (this is VoiceState.Preparing) {
        AmbientState.Thinking
    } else {
        deriveAmbientState(toVoicePhase(), textThinking = false, wakeArmed = false)
    }
```

- [ ] **Step 4: Remove the now-duplicate private mapper from `ConversationViewModel.kt`**

Delete the KDoc + private function (lines 380-394):

```kotlin
    /**
     * Map the voice pipeline's [VoiceState] onto the presentation [VoicePhase].
     * Preparing (cold STT warm-up) and Error are transient and fold to Idle so
     * the ambient aura doesn't twitch on them; Responding is the phase where
     * Ari is speaking back.
     */
    private fun VoiceState.toVoicePhase(): VoicePhase = when (this) {
        is VoiceState.Idle -> VoicePhase.Idle
        is VoiceState.Preparing -> VoicePhase.Idle
        is VoiceState.Listening -> VoicePhase.Listening
        is VoiceState.Thinking -> VoicePhase.Thinking
        is VoiceState.Responding -> VoicePhase.Speaking
        is VoiceState.Error -> VoicePhase.Idle
    }
```

The call site `voiceSession.state.map { it.toVoicePhase() }` (line 104) now resolves to the shared top-level extension (same package `dev.heyari.ari.ui.conversation`, no import needed).

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.ui.conversation.AmbientMappingTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Compile the app to confirm the VM refactor is clean**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/AmbientState.kt app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt app/src/test/java/dev/heyari/ari/ui/conversation/AmbientMappingTest.kt
git commit -m "refactor: share VoiceState→ambient mappers, add toOverlayAmbientState"
```

---

### Task 2: Corner param + `ambientCardHalo` primitive

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/AmbientField.kt`

**Interfaces:**
- Consumes: `AmbientState` (unchanged).
- Produces: `Modifier.ambientComposerBorder(state, cornerRadius: Dp = 24.dp)` and new `Modifier.ambientCardHalo(state: AmbientState, cornerRadius: Dp = 20.dp, inset: Dp = 12.dp, maxBleed: Dp = 8.dp): Modifier`.

- [ ] **Step 1: Add imports**

Add to the imports in `AmbientField.kt`:

```kotlin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
```

- [ ] **Step 2: Parameterize the corner on `ambientComposerBorder`**

Change the signature (line 97):

```kotlin
fun Modifier.ambientComposerBorder(state: AmbientState): Modifier {
```

to:

```kotlin
fun Modifier.ambientComposerBorder(state: AmbientState, cornerRadius: Dp = COMPOSER_CORNER): Modifier {
```

Then replace **both** occurrences of `COMPOSER_CORNER.toPx()` (currently lines 110 and 130) with `cornerRadius.toPx()`. Leave the private `COMPOSER_CORNER = 24.dp` as the default source — the composer call site is unchanged, so its rendering is identical.

- [ ] **Step 3: Add the `ambientCardHalo` primitive**

Append to `AmbientField.kt`:

```kotlin

/**
 * A bounded, card-hugging halo for the voice overlay card — the overlay's
 * counterpart to [AmbientField]'s bottom aura, but feathered to stay within the
 * card's [inset] margin so it never spills onto the translucent window's
 * background. Drawn behind an opaque card whose edge sits [inset] inside this
 * node; the glow feathers outward up to [maxBleed] via layered decaying-alpha
 * strokes, breathing its peak alpha per [state]. Reduce-motion aware.
 */
@Composable
fun Modifier.ambientCardHalo(
    state: AmbientState,
    cornerRadius: Dp = 20.dp,
    inset: Dp = 12.dp,
    maxBleed: Dp = 8.dp,
): Modifier {
    val ctx = LocalContext.current
    val motion = remember { animationsEnabled(ctx) }
    val accent = MaterialTheme.colorScheme.primary

    val (baseAlpha, period) = when (state) {
        AmbientState.Idle -> 0.06f to 5500
        AmbientState.Listening -> 0.22f to 1600
        AmbientState.Thinking -> 0.14f to 2200
        AmbientState.Speaking -> 0.20f to 1100
    }
    val alpha = if (!motion) baseAlpha else {
        val t = rememberInfiniteTransition(label = "halo")
        val v by t.animateFloat(
            initialValue = baseAlpha * 0.5f, targetValue = baseAlpha,
            animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Reverse),
            label = "haloAlpha",
        )
        v
    }

    return this.drawBehind {
        val insetPx = inset.toPx()
        val bleedPx = maxBleed.toPx()
        val cornerPx = cornerRadius.toPx()
        val left = insetPx
        val top = insetPx
        val cardW = size.width - insetPx * 2
        val cardH = size.height - insetPx * 2
        val layers = 8
        for (i in 1..layers) {
            val f = i / layers.toFloat()       // 0..1 outward
            val grow = bleedPx * f
            val a = alpha * (1f - f)            // decay to 0 → feathered edge
            drawRoundRect(
                color = accent.copy(alpha = a),
                topLeft = Offset(left - grow, top - grow),
                size = Size(cardW + grow * 2, cardH + grow * 2),
                cornerRadius = CornerRadius(cornerPx + grow),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
```

- [ ] **Step 4: Compile to verify**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No unit-test seam — these are Compose draw primitives; verified visually in Task 3.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/AmbientField.kt
git commit -m "feat: parameterize composer-border corner, add ambientCardHalo"
```

---

### Task 3: Attach border + halo to the voice overlay

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceOverlayContent.kt`

**Interfaces:**
- Consumes: `toOverlayAmbientState()` (Task 1), `ambientCardHalo` / `ambientComposerBorder` (Task 2).

- [ ] **Step 1: Add imports**

Add to `VoiceOverlayContent.kt` imports:

```kotlin
import dev.heyari.ari.ui.conversation.ambientCardHalo
import dev.heyari.ari.ui.conversation.ambientComposerBorder
import dev.heyari.ari.ui.conversation.toOverlayAmbientState
```

- [ ] **Step 2: Compute the ambient state and attach the primitives to the card**

Change the top of the composable body — from:

```kotlin
@Composable
fun VoiceOverlayContent(
    state: VoiceState,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
```

to:

```kotlin
@Composable
fun VoiceOverlayContent(
    state: VoiceState,
    onDismiss: () -> Unit,
) {
    val ambient = state.toOverlayAmbientState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Halo draws in the 12dp margin (before padding, so its node includes
            // the margin); the card sits 12dp inside and the glow feathers outward.
            .ambientCardHalo(ambient, cornerRadius = 20.dp, inset = 12.dp)
            .padding(12.dp)
            // Border hugs the card edge (after padding, so its node == the card).
            .ambientComposerBorder(ambient, cornerRadius = 20.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
```

- [ ] **Step 3: Compile + run the full unit suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (incl. Task 1's `AmbientMappingTest`).

- [ ] **Step 4: Build + install to the connected device**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:assembleDebug
ANDROID_HOME=/home/keith/Android/Sdk /home/keith/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Device sign-off (Keith)**

Trigger a voice turn ("Hey Ari" / tap-to-talk) and confirm on the overlay card:
- **Listening** → border sweeps + halo brightens/breathes faster.
- **Thinking** ("connecting…" / processing) → softer sweep + fainter halo (Preparing also shows this).
- **Responding** (Ari speaking) → border pulses (alpha+width) + halo breathes.
- The halo **hugs the card** and fades out within the 12dp margin — **no glow spilling onto the home/lock screen** behind.
- Turn on Developer Options → "Animator duration scale: off" → the treatment is a **static** accent border + faint static halo (no motion).
- The mic badge (`StatusIndicator`) still reads fine alongside the border; if its pulse fights the border, note it for a follow-up (drop the badge pulse).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/voice/VoiceOverlayContent.kt
git commit -m "feat: ambient border + halo on the voice overlay card"
```

---

## Self-Review

**Spec coverage:**
- Border reused with 20dp corner param → Task 2 Step 2 + Task 3 Step 2. ✓
- Bounded feathered halo, ~6-8dp bleed, no screen-fill, breathing per state, reduce-motion static → Task 2 Step 3 (`ambientCardHalo`). ✓
- Shared `toVoicePhase` + `toOverlayAmbientState` with `Preparing → Thinking`; chat behaviour preserved → Task 1. ✓
- `Error`/`Idle` quiet → falls out of `deriveAmbientState` (Error→Idle via `toVoicePhase`) → Task 1 mapper + test. ✓
- `StatusIndicator` left as-is → not modified; noted in Task 3 Step 5 device check. ✓
- Frontend-only, no Activity change → File Structure lists only the four files. ✓
- Testing: pure mapper tests + device sign-off → Tasks 1 & 3. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. Halo tuning values (alphas/periods/8 layers/8dp bleed) are concrete starting values, adjustable on device — not placeholders. ✓

**Type consistency:** `toVoicePhase()` / `toOverlayAmbientState()` (Task 1) are consumed by name in Task 3. `ambientComposerBorder(state, cornerRadius)` and `ambientCardHalo(state, cornerRadius, inset, maxBleed)` signatures (Task 2) match their Task 3 call sites (`cornerRadius = 20.dp`, `inset = 12.dp`). `VoiceState` variant constructors in the test (`Preparing("x")`, `Listening("")`, `Responding("hi")`, `Error("e")`) match the sealed interface. ✓

# Voice-Overlay Ambient Styling — Design

**Date:** 2026-07-12
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

The chat screen has a state-driven "ambient" reactive treatment — the composer's
border and a bottom aura sweep when listening, shimmer when thinking, and pulse
when speaking, giving Ari a "breathing presence". But real voice turns don't run
on the chat screen: they run in `VoiceOverlayActivity`, a separate translucent
task that takes over the screen and renders the overlay card
(`voice/VoiceOverlayContent.kt`). So during an actual voice interaction — the
moment the ambient aesthetic matters most — none of it is visible. The overlay's
only reactive element is a small mic badge that pulses on Listening.

Bring the same breathing presence to the overlay card, driven off the overlay's
own `VoiceState`.

## Approach

Reuse the existing, dependency-free draw primitives from `ui/conversation/` on the
overlay card, driven by the **same** `AmbientState` the chat screen uses. Two
coherent layers that breathe together off one state:

1. **Border** — reuse `ambientComposerBorder` (sweep / soft-sweep / pulse) around
   the card, parameterized so its corner radius matches the card's `20.dp`.
2. **Bounded halo** — a new sibling primitive `ambientCardHalo` that paints a soft
   feathered glow *behind* the card, bleeding only ~6–8dp into the card's existing
   12dp inset and fading out. **Never a screen-fill** — this is the key constraint,
   because the overlay window is `@android:style/Theme.Translucent.NoTitleBar`
   with no scrim, so anything beyond the card renders over whatever is behind
   (home screen / lock screen / previous app).

The `VoiceState → AmbientState` mapping already effectively exists (as
`ConversationViewModel.toVoicePhase()` + `deriveAmbientState`); we lift it to a
shared home and add one overlay-local tweak.

### Rejected alternatives

- **Port `AmbientField` (the chat aura) to the overlay.** The chat aura is a
  120dp screen-anchored radial glow. On the translucent, scrim-less overlay it
  would bleed over live background content (magical over a dark lock screen,
  messy over a bright home screen). The bounded halo gives the same "presence"
  feel while staying contained. (This is why the halo is a new primitive, not a
  reuse of `AmbientField`.)
- **`Modifier.blur` for the halo feather.** Cleanest soft glow, but `RenderEffect`
  blur is API 31+ and the app min-SDK is 29. Layered decaying-alpha strokes give a
  smooth feather on all API levels and match the codebase's `drawBehind` idiom.
- **Colored `Modifier.shadow` (tinted spot/ambient) for the glow.** Directional
  drop-shadow, not a symmetric halo, and it would stack awkwardly with the card's
  existing `shadowElevation = 12.dp`. Rejected.
- **Duplicate the `VoiceState → VoicePhase` mapping in the overlay.** Two copies
  drift. Lift the one mapper to a shared top-level function instead (DRY).

## Decisions (agreed)

1. **Border + bounded card-hugging halo**, both driven by one `AmbientState`. No
   screen-filling aura.
2. **Parameterize the corner radius** on `ambientComposerBorder` (default `24.dp`
   preserves the composer exactly); the overlay passes `20.dp`.
3. **Halo is feathered via layered strokes**, bounded to ~6–8dp bleed, breathing
   its alpha per state, reduce-motion aware (static faint halo when motion is off)
   — inheriting the existing `animationsEnabled` gate.
4. **`Preparing → Thinking`** on the overlay (overlay-local), so the halo/border
   are alive while the "connecting…" spinner shows. Chat behaviour is untouched.
   `Error` and `Idle` stay quiet (static Idle border; red error text carries the
   error).
5. **Leave `StatusIndicator` (the mic badge) as-is.** Add the border+halo around
   it. If the badge's Listening-pulse visibly fights the border on-device, drop
   just the badge's standalone pulse — deferred to device review, not done up
   front.
6. **Frontend-only, ari-android only.** No `VoiceOverlayActivity` change (it
   already threads `VoiceState` into the content). No engine/skill/linux changes.

## Components

### Changed: `ui/conversation/AmbientState.kt`

Lift the mapper here (next to `deriveAmbientState`) so both the chat VM and the
overlay share one copy, and add the overlay-specific collapse:

```kotlin
import dev.heyari.ari.voice.VoiceState

/** The pipeline's momentary voice state → presentation phase. Shared by the
 *  chat screen and the voice overlay. */
fun VoiceState.toVoicePhase(): VoicePhase = when (this) {
    is VoiceState.Idle -> VoicePhase.Idle
    is VoiceState.Preparing -> VoicePhase.Idle
    is VoiceState.Listening -> VoicePhase.Listening
    is VoiceState.Thinking -> VoicePhase.Thinking
    is VoiceState.Responding -> VoicePhase.Speaking
    is VoiceState.Error -> VoicePhase.Idle
}

/** Ambient state for the voice overlay. No typed-path/wake inputs; Preparing
 *  shows the Thinking treatment because the overlay renders a spinner for it. */
fun VoiceState.toOverlayAmbientState(): AmbientState =
    if (this is VoiceState.Preparing) AmbientState.Thinking
    else deriveAmbientState(toVoicePhase(), textThinking = false, wakeArmed = false)
```

(`AmbientState.kt` gains a dependency on `voice.VoiceState`; `ConversationViewModel`
already depends on it, so this is just relocating the dependency, not adding a new
cross-cutting one.)

### Changed: `ui/conversation/ConversationViewModel.kt`

Delete the private `toVoicePhase()` and use the shared top-level one. Behaviour
identical (same `when` arms).

### Changed: `ui/conversation/AmbientField.kt`

- **`ambientComposerBorder`** gains a `cornerRadius: Dp = 24.dp` parameter,
  replacing the hardcoded `COMPOSER_CORNER`. All existing call sites (the composer)
  keep the default → no visual change there.
- **New `Modifier.ambientCardHalo(state: AmbientState, cornerRadius: Dp): Modifier`**
  — `drawBehind` painting a bounded feathered glow: N (~8) concentric rounded-rect
  strokes stepping outward ~1dp each from the card edge, alpha decaying outward, so
  the glow reads as a soft ~6–8dp halo with no hard outer edge. Accent is
  `colorScheme.primary`. Peak alpha breathes per state (reuse the
  `AmbientField`-style `(baseAlpha, period)` table: brighter/faster on
  Listening & Speaking, softer/slower on Thinking, faint-static on Idle),
  animated via `rememberInfiniteTransition`. Reduce-motion → static faint halo at
  `baseAlpha`. Draw stays within the card's 12dp inset so it never reaches the
  screen edge.

### Changed: `voice/VoiceOverlayContent.kt`

- Compute `val ambient = state.toOverlayAmbientState()` once.
- Wrap the card `Surface` in a `Box` so the halo can draw behind it within the
  existing 12dp inset; apply `ambientCardHalo(ambient, 20.dp)` behind the card and
  `ambientComposerBorder(ambient, cornerRadius = 20.dp)` on the `Surface` modifier
  (with the Surface's own visible border already effectively none).
- `StatusIndicator` unchanged.

## Data flow

```
VoiceSession.state: StateFlow<VoiceState>          (Hilt singleton)
  → VoiceOverlayActivity collects it (unchanged) → VoiceOverlayContent(state)
      → state.toOverlayAmbientState()  → AmbientState
          Listening  → sweep border + brighter breathing halo
          Thinking   → soft sweep border + softer halo
          Preparing  → (overlay-local) Thinking treatment  (spinner is up)
          Responding → pulse border + faster breathing halo
          Idle/Error → static Idle border, faint/no halo
      → ambientCardHalo(ambient, 20.dp) behind card + ambientComposerBorder(ambient, 20.dp) on card
```

## Edge cases

- **Translucent window, no scrim:** the halo is bounded to the 12dp inset and
  feathers to zero, so it never paints over the live background beyond the card.
- **Reduce motion:** both primitives fall back to static via the existing
  `animationsEnabled` gate — a static accent border and a faint static halo.
- **Very short states:** `Preparing`/`Thinking` may be brief; the border/halo just
  track whatever the current state is (no minimum-dwell logic — matches the chat
  screen, which also reacts immediately).
- **Card height varies by state** (partial transcript vs response text): the halo
  and border are drawn relative to the card's measured bounds, so they track height
  changes automatically.
- **Mic badge overlap:** `StatusIndicator` keeps its Listening-pulse; if it reads
  as busy against the border on-device, drop the badge pulse (Decision 5).

## Testing

Per the project rule that tests assert exact values and real behaviour:

- **`toOverlayAmbientState` (JUnit4, pure):** exact `AmbientState` per `VoiceState`
  — `Idle→Idle`, `Preparing→Thinking` (the overlay override), `Listening→Listening`,
  `Thinking→Thinking`, `Responding→Speaking`, `Error→Idle`.
- **`toVoicePhase` (JUnit4, pure):** unchanged mapping still holds after the lift
  (guards the `ConversationViewModel` refactor) — `Preparing→Idle`, etc.
- **Device sign-off (emulator/device):** each phase shows the right treatment
  (Listening sweep, Thinking soft, Responding pulse), the halo hugs the card and
  does **not** spill onto the background, and reduce-motion yields a static frame.
  Visual "does it breathe right" is Keith's call.

## Out of scope

- Any `ari-engine` or skill change (frontend-only).
- `VoiceOverlayActivity` / window or task changes.
- Porting the full screen-fill `AmbientField` aura to the overlay (deliberately
  rejected for the translucent window).
- Changing the chat screen's ambient behaviour (the mapper lift is behaviour-
  preserving).
- Removing/altering `StatusIndicator` up front (only a deferred fallback).
- ari-linux (not yet implemented).

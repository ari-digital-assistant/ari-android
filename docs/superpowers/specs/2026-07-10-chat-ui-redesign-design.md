# Chat UI Redesign — Design

**Date:** 2026-07-10
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

The chat UI is the stock Android Studio Material 3 template, essentially
untouched: `Theme.kt` still carries the default purple fallback with
`dynamicColor = true`, `Type.kt` is the template default (system Roboto), and
`MessageBubble.kt` is ~85 lines of a single 16dp rounded rectangle per side. On a
modern device the whole app therefore wears the user's wallpaper palette by
accident and reads as a demo, not a product.

Meanwhile `GenericCard.kt` (the weather/stat/list card system) is genuinely
crafted — frosted chips, full-bleed image stat cards, an accent→container role
mapping. So the app already contains a sophisticated visual language; it simply
isn't applied to the 90% of the screen that is plain chat. **The gap between the
weather card and the text bubbles is the whole problem.** With an Android release
approaching, we close that gap.

## Guiding principle

**It's Material You _on purpose_.** Colour stays fully dynamic (wallpaper-driven
Material You) — this is a deliberate, kept feature, not the thing we fix. Every
_other_ craft lever — typography, shape, elevation, spacing, motion, hierarchy,
chrome — is intentionally designed so the result reads as crafted rather than
defaulted, the way Google's own apps are obviously dynamic-coloured yet obviously
designed.

This is a **near-frontend-only** redesign of `ari-android`. The **only** engine
touch is a single _additive_ FFI field — surfacing the already-parsed, already-
validated skill `examples` on `FfiSkillManifest` so the empty-state chips can use
real skill-declared utterances generically (decision 5). No behavioural engine
change; no skill-specific code enters the frontend (chips and cards stay generic
host surfaces). It composes with the approved **Voice-in-Chat** spec
(`2026-07-09-voice-in-chat-design.md`) — both touch `ConversationScreen` and
`MessageBubble`; this redesign assumes the `ConversationLogRepository` singleton
from that spec is (or will be) the message source.

> **Amended 2026-07-10 (during planning):** three items were resolved against the
> real code — (a) chips are sourced from **skill-declared examples** exposed via a
> new additive FFI field (skills declare ≥5 each, validated in
> `ari-skill-loader`), not invented per-skill; (b) the name greeting uses a
> **heuristic scan** of the freeform `List<String>` remembered facts (there is no
> structured name field); (c) the "still working" change is confirmed **text-path
> local** — `VoiceSession` has no in-flight filler, so the spoken path is untouched.

## Design decisions

### 1. Colour — keep dynamic Material You (unchanged)

No fixed brand palette. `AriTheme` keeps `dynamicColor = true` and the M3 dynamic
light/dark schemes. The work here is _discipline_: use the M3 container roles
deliberately (as `GenericCard` already does) and verify both light and dark
dynamic schemes hold up for the new bubble/chrome/empty-state surfaces. Explicitly
**not** introducing a hand-tuned `ColorScheme`.

### 2. Typography — adopt Manrope

Replace the template default with **Manrope** (SIL Open Font License, variable),
applied app-wide. This is the single biggest identity lever now that colour is
dynamic.

- Add `app/src/main/res/font/` with the Manrope family (weights 400–800) and a
  `manrope` `FontFamily`.
- Rewrite `Type.kt` into a full, intentional type scale (display/title/body/label)
  built on `manrope` — not just the one `bodyLarge` override that exists today.
- The `GenericCard` inline overrides (monospace countdown digits, SemiBold card
  titles) are preserved; only the base family changes.

### 3. The Ambient Field — Ari's signature presence

The signature element. No mascot, no orb, no discrete object — **the UI itself
breathes.** A presence layer reacts to a single derived `AmbientState`:

| State | Read | Motion |
|---|---|---|
| **Idle** | "I'm here, no rush" | faint slow aura breath at the base |
| **Listening** | actively capturing your speech | input-bar border sweeps + aura pulses live |
| **Thinking** | working on it | shimmer crosses the input bar + a **dots-bubble** where the reply will land |
| **Speaking** | reading a reply aloud | aura/glow pulse in rhythm with TTS |

Intensity is deliberately restrained (idle is _barely_ there) — the reason the
Ambient Field was chosen over a mascot was subtlety.

### 4. Bubbles — "Ari with a face"

Both sides bubbled, with a redesigned form:

- **Asymmetric tail corner** (the bottom corner on the sender's side tightens to
  ~4dp) instead of a uniform 16dp rectangle.
- **Every Ari turn carries the `A:` mark** (a small circular avatar) plus a
  hairline accent edge (`primary`-tinted left border), giving Ari a persistent
  visible identity in the log. User turns have no avatar.
- **Grouping:** consecutive same-sender messages tuck together (reduced gap,
  softened interior corners); the avatar/tail shows only on the group's edge
  message.
- **Timestamp divider:** a quiet centered timestamp appears only when there's a
  meaningful time gap between turns (not per message).

### 5. Adaptive empty state

The blank-cream void becomes an adaptive home screen — a new-user first impression
_and_ a Play Store screenshot. It reads two inputs at startup: **installed-skill
count** and **whether Ari knows the user's name** (scanned from the remembered-facts
store).

- **First run (few/no skills):** a warm, faintly cheeky line ("Hi, I'm Ari 👋 —
  I'm pretty bare-bones right now"), a **prioritised "Browse skills" card**, and a
  quiet "…or just type below" (the builtin assistant answers immediately without
  any skills installed).
- **Set up (enough skills):** greeting + **suggestion chips built from the
  installed skills' declared `examples`** (surfaced via the new additive FFI
  field). A handful are sampled across installed skills; deterministic ordering
  (no `Math.random` reliance in tests). Chips are skill-agnostic — the frontend
  never names a skill.
- **Greeting personalisation:** on startup, **heuristically scan** the freeform
  `List<String>` remembered facts for the user's name (patterns: "my name is X",
  "i'm X", "call me X", "the user's name is X" — case-insensitive, first match
  wins, else none). Known → time-aware "Good morning, Keith". Unknown → neutral
  "Hi, I'm Ari" **and inject a "Remember my name" chip** into the suggestion set.
  The personalisation thereby teaches its own feature. The heuristic is
  best-effort: it reliably catches the canonical fact the "Remember my name"
  round-trip produces, and falls back to the neutral greeting otherwise.
- **Chip tap = submit immediately.** Tapping a chip sends that exact utterance as
  though typed (a user bubble appears; Ari runs its full pipeline, including
  multi-turn clarification — e.g. "Set a reminder" → "Course, what & when?").
  Chips are training wheels for conversation, not macros. No prefill.

The only "presence" on the empty screen is the ambient aura at the base — no blob.

### 6. Chrome — composer-centric, with an unambiguous wake switch

- **Top bar** keeps the hamburger + centered `A:` logo.
- **Composer:** a single rounded pill with the **mic inside it** (tap-to-talk),
  which **swaps to a send arrow the moment the field is non-empty** (universal
  mic↔send pattern). Replaces today's `OutlinedTextField` + detached send button.
- **"Hey Ari" always-listening control is a Switch**, not a tappable pill. This is
  a **sticky, persistent state** (on until toggled off or reboot; survives
  app-close as a foreground service). The design must make that persistence
  unmistakable **without explanatory copy**, via three reinforcing signals:
  1. It's a **Switch** — the universal set-and-forget affordance.
  2. **Steady vs. animated:** the armed state is _motionless_ (a steady lit
     glow); only the momentary live-capture (state 3, Listening) animates.
     Stillness reads as permanence; motion reads as "happening now."
  3. Android's **ongoing foreground-service notification** appears while armed —
     the OS-level "running in the background" badge, for free.

  This resolves a real confusion in the current UI, where the persistent
  wake-word setting and the momentary "listening now" state wear similar clothes.

### 7. "Still working" is a dual-channel signal

The existing 4-second `STILL_WORKING_DELAY_MS` filler is **not** a mere UI
placeholder — in a background/voice-only session with no window on screen, the
spoken filler is the _only_ cue that Ari hasn't died. It stays. We change only the
_visual_ presentation:

- **One signal, two presentations, same ~4s threshold.**
- **Window visible →** the animated **dots-bubble** (silent; the user can see
  it). This replaces the appended placeholder _text_ `Message`.
- **No window / backgrounded / screen-off / eyes-free →** the **spoken filler is
  retained, untouched.**

Requirement: the visual dots indicator is **transient** (a thinking affordance in
the pending assistant slot), not a persisted `Message` in the log — so it must not
survive into the conversation record the way today's text bubble does.

### 8. Motion — restrained Spring Rise + stagger

Material 3 Expressive spring motion. New messages (user bubbles, Ari replies, and
rich cards alike) enter with a **Spring Rise**: translate up + slight scale
overshoot, then settle. Tuned _restrained_ — enough life to feel alive, short of
bounce-fatigue over a long chat. A subtle **stagger** cascades a reply and its
attached rich card in one-after-another rather than snapping in together.
Entrances respect the system reduce-motion setting (see Accessibility).

## Components

### New

- **`res/font/` + Manrope `FontFamily`** — the typeface assets and family
  definition consumed by `Type.kt`.
- **`AmbientField` composable + `AmbientState`** — the presence layer (aura +
  input-bar treatment) driving idle/listening/thinking/speaking. `AmbientState` is
  derived from existing sources: the momentary voice pipeline state
  (`VoiceState` / `VoiceSession`) for listening/thinking/speaking, distinct from
  the persistent wake-word-armed flag. Single source of truth for the derivation
  lives with the screen/ViewModel, not scattered across composables.
- **`AriAvatar` composable** — the small circular `A:` mark used on Ari's grouped
  bubble edges (reuses `ic_ari_symbolic`).
- **`ThinkingIndicator` composable** — the animated three-dot pending-reply
  bubble; transient, never logged.
- **`EmptyState` composable** — adaptive first-run vs set-up faces, greeting
  personalisation, suggestion-chip row, "Browse skills" CTA.
- **`SuggestionChips` composable** — chips generated from installed skills (+ the
  conditional "Remember my name" chip); tap routes to `onTextSubmitted`.
- **`AriComposer` composable** — the pill input with integrated mic↔send swap.
- **`WakeSwitch` composable** — the steady-vs-animated always-listening switch for
  the top bar `actions` slot.

### Changed

- **`ui/theme/Type.kt`** — full Manrope type scale (see decision 2).
- **`ui/theme/Theme.kt` / `Color.kt`** — no scheme change; verify the new surfaces
  against dynamic light/dark. Remove dead template comments.
- **`ui/conversation/MessageBubble.kt`** — asymmetric tail corners, `AriAvatar` +
  hairline accent on Ari turns, grouping-aware corners/avatar. Grouping and
  timestamp-divider decisions are computed at the **list** level (neighbour-aware)
  and passed in as flags (`isFirstInGroup` / `isLastInGroup` / `showTimestamp`),
  keeping `MessageBubble` a pure function of its inputs.
- **`ui/conversation/ConversationScreen.kt`** — host the `AmbientField`; render
  `EmptyState` when the log is empty; compute grouping/timestamp flags; entrance
  animation + stagger (`Modifier.animateItem` placement + enter transition); swap
  the input row for `AriComposer`; wire the dead `AnimatedVisibility`/`fadeIn`
  imports into real use.
- **`ui/components/AriTopBar.kt`** — accept the `WakeSwitch` in its `actions` slot;
  spacing/type polish.
- **`ConversationViewModel`** — replace the appended "still working" _text_
  `Message` with the transient visual `ThinkingIndicator` state when a screen is
  visible; expose `AmbientState`, installed-skill-derived chip data, and the
  remembered-name lookup for the empty state. The **spoken** filler path is
  untouched.
- **`res/values/strings.xml`** — new source strings (greetings, empty-state copy,
  chip labels, content descriptions). Source language only; no invented
  translations.

### Engine (the one additive change)

- **`ari-ffi` — `FfiSkillManifest.examples`** — add `examples: Vec<String>`
  (the `SkillExample.text` values) to the FFI manifest struct, populated from the
  already-parsed `Manifest.examples`; regenerate the Kotlin bindings (manual regen
  per project convention). Additive only — no behavioural change. Must be pushed
  to `ari-engine` main before any skill-side work (per project CI rule), though
  this feature adds no skill-side work.

### Resolved during planning (was "verify-then-wire")

- **Remembered facts** = `SettingsRepository.rememberedFacts: Flow<List<String>>`
  (freeform strings; DataStore-backed) → name via heuristic scan (decision 5).
- **Installed skills** = `uniffi.ari_ffi.SkillRegistry.listInstalled()` →
  `readInstalledManifest(id, locale)` for name + the new `examples`. The
  **"Browse skills"** target is the existing `onOpenSkills` → `Routes.skills()`.
- **Spoken filler** — confirmed: only `ConversationViewModel.onTextSubmitted`
  emits the 4s filler; `VoiceSession` has none. The visual change is text-path
  local; the spoken path is untouched.
- **Test infra** = JVM `app/src/test/` with plain **JUnit4** + `org.junit.Assert`
  + `kotlinx.coroutines.runBlocking` (no Robolectric/MockK/Truth/Turbine). All
  new testable logic must therefore be **pure Kotlin** (no Android deps) so it
  runs under `:app:testDebugUnitTest`. Compose visuals are verified on the
  emulator, not via (absent) Compose UI tests.

## Behaviour details

- **Empty-state switch:** below a skill-count threshold → first-run face; at/above
  → set-up face. Threshold value decided in the plan.
- **Wake state machine:** Off → (tap switch) → Armed/steady (persists, background)
  → ("Hey Ari" or tap-to-talk) → Listening/animated (momentary) → back to
  Armed/steady when the turn ends. The Switch stays on throughout capture.
- **AmbientState precedence:** speaking > thinking > listening > idle, gated so the
  persistent armed flag never renders as the animated "listening" form.

## Accessibility

- **Reduce motion:** when the system animator duration scale is 0 (or accessibility
  reduce-motion is set), Spring Rise / stagger / ambient animations degrade to
  instant or a plain cross-fade; the dots-bubble becomes a static indicator.
- **Contrast:** validate bubble text, chips, and the accent edge against both
  dynamic light and dark schemes (they derive from wallpaper, so test several).
- **Content descriptions:** `AriAvatar`, `WakeSwitch` (announces armed/disarmed and
  that it persists), mic↔send composer button, `ThinkingIndicator`, and chips.
- **Tap targets:** composer mic/send and the wake switch meet the 48dp minimum.

## Testing

Per the project rule that tests assert exact values and real behaviour:

- **Grouping/timestamp logic** (list-level, pure): given a message list with known
  timestamps and senders, assert exact `isFirstInGroup`/`isLastInGroup`/
  `showTimestamp` flags per item, including the group boundaries and the gap
  threshold.
- **AmbientState derivation:** each input combination maps to the exact expected
  state; the persistent armed flag never yields the animated Listening form;
  precedence (speaking > thinking > listening > idle) holds.
- **Empty-state selection:** 0/near-0 skills → first-run face; above threshold →
  set-up face. Name known → time-aware greeting, no "Remember my name" chip; name
  unknown → neutral greeting **with** the chip present.
- **Chip tap:** invokes `onTextSubmitted` with the exact chip utterance (submit,
  not prefill).
- **"Still working" dual-channel:** with a visible screen, a >4s processing turn
  produces the transient `ThinkingIndicator` and appends **no** text `Message` to
  the log; the spoken-filler path is unchanged (assert it still fires on the
  background path).
- **Composer mic↔send swap:** empty field shows mic; non-empty shows send.

## Out of scope

- Any `ari-engine` change beyond the single additive `FfiSkillManifest.examples`
  field; no skill changes at all.
- A fixed/brand `ColorScheme` — dynamic Material You is kept.
- Durable/persistent chat history (owned by the Voice-in-Chat spec's decisions).
- ari-linux (not yet implemented).
- The transient `VoiceOverlayActivity` UX (lock-screen/overlay), beyond sharing
  the same `AmbientState` vocabulary.
- Reworking `GenericCard` internals — it sets the bar we harmonise _to_; only its
  base font family changes with the app-wide Manrope switch.

## Follow-ups / open items (for the plan, not blockers)

- Exact skill-count threshold for the empty-state switch.
- Final Manrope weight set to bundle (size vs. coverage).
- Whether the "armed" wake state also wants a persistent tinted top-bar strip for
  extra-loud transparency (offered; deferred pending the plan's judgement).

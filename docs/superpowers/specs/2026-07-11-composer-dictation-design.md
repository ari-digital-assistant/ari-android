# Composer Voice Dictation — Design

**Date:** 2026-07-11
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

The mic button in the chat composer currently reuses the **background / "Hey
Ari"** flow: tapping it (`ConversationViewModel.startVoiceTurn()`) starts the
wake-word foreground service and launches the full-screen `VoiceOverlayActivity`
takeover. That is the right behaviour for an eyes-free/backgrounded request, but
it is wrong for the **foreground** case — the user is already looking at the chat
window.

What a foreground voice button should do is **in-place dictation**: tap it, the
input field shows it's listening, the user talks (no "Hey Ari"), their words are
transcribed into the field live, and when they stop speaking it submits as if
they had typed it — no overlay, no wake word, and the reply renders in the chat
as a normal turn.

## Approach

**Reuse the tap-to-talk host machinery; add a "dictation mode" that forks off
it.** The existing flow already knows how to bring the mic host up (transiently,
via a one-shot `WakeWordService`, when always-listening is off) and stand it back
down. Dictation reuses that lifecycle but, instead of launching the overlay, runs
**STT-only** (no engine call, no spoken reply) and hands the final transcript
back to the composer.

### Rejected alternatives

- **ViewModel drives `SpeechRecognizer` + manages the foreground service
  itself.** Re-implements the mic-host teardown — exactly where the tap-to-talk
  hot-mic Critical bug lived. The one-shot stand-down is already correct in
  `WakeWordService` (via `VoiceSession.state → Idle`); reuse it.
- **Decouple mic capture from `WakeWordService` entirely.** A large refactor of
  the audio pipeline for no additional benefit here. Overkill.

### Key facts this design relies on (from the STT/audio investigation)

- `SpeechRecognizer` is a `@Singleton`, already injected into
  `ConversationViewModel`. Its `state: StateFlow<SttState>` emits live partials
  (`SttState.Listening(partial)`) and the final transcript (`SttState.Done(text,
  …)`). **Endpoint detection is automatic** (partial-text-stability / RMS
  silence) and it **stops itself** on `Done`.
- `SpeechRecognizer.startListening()` only *arms* the shared `CaptureBus`; audio
  only flows while `WakeWordService`'s `AudioRecord` loop is producing. So
  dictation needs the service running.
- `CaptureBus` is **single-consumer exclusive** (`arm()` returns null if already
  armed) — dictation and a wake-word turn cannot both hold the mic; they must be
  mutually exclusive.
- While the bus is armed, `WakeWordService` already **suppresses wake-word
  detection** (`if (captureBus.armed) { detector.reset(); continue }`), so
  dictation naturally pauses the wake word for its duration.

## Decisions (agreed)

1. **On end-of-speech → auto-submit immediately** — the final transcript is sent
   as if typed the instant silence is detected. Fully hands-free.
2. **Stop / cancel / error → keep the partial text in the field** — a
   half-caught sentence is preserved for the user to edit or send, not discarded.
3. **Always-listening ON → dictation takes over briefly** — dictation arms the
   bus (pausing the wake word), transcribes, submits, and hands the mic back; the
   service keeps running. Both features coexist.
4. **Cold start → disable the mic until the STT model is warm** — no cold-start
   choreography; the button is disabled while `!speechRecognizer.isModelLoaded`
   (the model warms at launch, so this is momentary).

## Components

### Changed: `SpeechRecognizer` / `VoiceState` — none

STT surface is reused as-is. No change.

### Changed: `VoiceSession`

Add an **STT-only dictation path** alongside the existing full pipeline.

- `fun startDictation()` — ensures the same warm STT setup, calls
  `speechRecognizer.startListening()`, and collects `speechRecognizer.state`:
  - `Listening(partial)` → `_state.value = VoiceState.Listening(partial)` (so the
    partial flows through the existing `state` flow the screen already observes).
  - `Done(text)` → emit `text` on a new **`dictatedText: SharedFlow<String>`**,
    then `_state.value = VoiceState.Idle`. **No `handleFinalText`, no engine, no
    TTS, no re-arm.**
  - `Error` → `VoiceState.Error(msg)` then `Idle`.
- `fun stopDictation()` — `speechRecognizer.stopListening()` + `VoiceState.Idle`
  (external cancel; produces no `Done`, so no submit).
- An internal mode guard so a dictation session never routes through the normal
  turn's engine/TTS path.

Reaching `VoiceState.Idle` at the end is what drives the existing one-shot
stand-down in `WakeWordService`.

### Changed: `WakeWordService`

Add `ACTION_START_DICTATION` (mirrors `ACTION_START_VOICE_TURN`):
`startForeground`, `startListening()` (mic host up), set `oneShotActive` from
`EXTRA_ONE_SHOT` — **but call `voiceSession.startDictation()` instead of
`launchVoiceOverlay()`**. The existing one-shot collector (watches
`voiceSession.state → Idle`) stands the transient host down unchanged.

### Changed: `ConversationViewModel`

- `fun startDictation()`:
  - Guard: return if `voiceSession.isActive || WakeWordService.oneShotActive`
    (a wake turn is running — the bus is taken).
  - `_state.update { isDictating = true }`.
  - Send `ACTION_START_DICTATION` to `WakeWordService` with
    `EXTRA_ONE_SHOT = !WakeWordService.isRunning`.
- `fun stopDictation()` → `voiceSession.stopDictation()`; `isDictating = false`
  (keeps `inputText` = last partial).
- Collectors (in `init`):
  - `voiceSession.state`: while `isDictating` and `Listening(partial)` →
    `_state.update { inputText = partial }` (live transcript into the field).
    On `Idle`/`Error` while dictating → `isDictating = false` (keep last partial).
  - `voiceSession.dictatedText`: on emit → set `inputText = text`,
    `isDictating = false`, then `onTextSubmitted(text)` (auto-submit; the
    existing `if (text.isBlank()) return` guard makes an empty transcript a no-op).

### Changed: `ConversationState`

Add `val isDictating: Boolean = false`.

### Changed: `AriComposer` + `composerAction`

The trailing button gains a third state. Add a pure helper:

```kotlin
enum class ComposerButton { Mic, Stop, Send }
fun composerButton(inputText: String, isDictating: Boolean): ComposerButton =
    when {
        isDictating -> ComposerButton.Stop
        inputText.isBlank() -> ComposerButton.Mic
        else -> ComposerButton.Send
    }
```

`AriComposer` takes `isDictating: Boolean` + an `onStop: () -> Unit`, and renders
Mic → `onMicTap` / Send → `onSend` / **Stop → `onStop`**. `ConversationScreen`
wires `onMicTap = startDictation`, `onStop = stopDictation`, passes `isDictating`.
While dictating, the field displays the live partial and is not user-edited.

### Unchanged: the ambient border

**No change to `deriveAmbientState`.** Dictation keeps the chat screen in the
foreground (no overlay), so `ConversationViewModel.voicePhase` (which observes
`voiceSession.state`, now `Listening(partial)` during dictation) resolves to
`VoicePhase.Listening` → the composer border shows its **Listening** treatment
for free. This is precisely the case that was invisible for a *voice-overlay*
turn (screen backgrounded) but is visible here (screen foreground).

**Fallback:** the "border shows Listening while dictating" device check
(Testing) confirms this. If `voicePhase` proves unreliable during dictation
(e.g. a `WhileSubscribed` timing edge), fall back to threading `isDictating`
into `deriveAmbientState` (→ `Listening`) — the flag already exists for the
composer button, so this is a one-line addition, not new plumbing.

## Data flow

```
tap composer mic (model warm, no active wake turn)
  → ConversationViewModel.startDictation()  [isDictating = true]
  → WakeWordService ACTION_START_DICTATION  [host up; one-shot if always-listening off]
  → VoiceSession.startDictation()  → SpeechRecognizer.startListening()  (arms CaptureBus; wake word suppressed)
  → SttState.Listening(partial) → VoiceState.Listening(partial)
        → voicePhase = Listening → composer border = Listening (sweep)
        → ConversationViewModel routes partial → inputText  (live transcript)
  → [silence] SttState.Done(text) → VoiceSession emits dictatedText, VoiceState.Idle
        → ConversationViewModel: inputText = text; isDictating = false; onTextSubmitted(text)  (auto-submit)
        → VoiceState.Idle → WakeWordService one-shot stand-down (if it started transiently)
  → engine reply renders as a normal chat turn (bubbles + TTS as usual)
```

## Edge cases

- **Always-listening ON:** service already running → `EXTRA_ONE_SHOT = false` →
  no stand-down; dictation arms the bus (wake word paused), finishes, bus
  disarmed, wake word resumes. Service keeps running.
- **Always-listening OFF:** `EXTRA_ONE_SHOT = true` → transient host; stands down
  on `VoiceState.Idle` via the existing collector.
- **Wake word cannot fire mid-dictation:** the bus is armed throughout, which the
  service already treats as "suppress detection".
- **Bus clash:** `startDictation()` is guarded against an active wake turn;
  conversely a wake turn can't start while the bus is armed by dictation.
- **Stop button / STT error:** stops, keeps the last partial in the field, no
  submit.
- **Empty transcript** (`Done("")`): auto-submit path hits `onTextSubmitted`'s
  blank guard → no-op; field left empty.
- **Cold start:** the mic button is disabled while `!isModelLoaded`; the model
  warms at launch, so this is momentary.
- **Offline (whisper) STT model:** emits no partials (one `Listening("")` then
  `Done`). Dictation still works — the field just fills at the end rather than
  streaming. Acceptable; online models stream.

## Testing

Per the project rule that tests assert exact values and real behaviour:

- **`composerButton` (pure logic, JUnit4):** `isDictating=true` → `Stop`
  (regardless of text); `isDictating=false` + blank → `Mic`; `isDictating=false`
  + non-blank → `Send`. Exact enum assertions.
- **Everything else is audio/service-lifecycle integration** with no unit-test
  seam (no mocking framework in the project) → **device-verified** on the
  emulator, exercising: dictation with always-listening OFF (transient host
  starts and stands down — verify no lingering mic FGS after), dictation with
  always-listening ON (wake word resumes after; service survives), the Stop
  button keeps text, an empty utterance submits nothing, and the border shows
  Listening while dictating. **The hot-mic edge cases get explicit attention**
  (double-tap, cancel, error) given the history.

## Out of scope

- Any `ari-engine` or skill change (frontend-only).
- Changes to the voice **overlay** UX or the wake-word / background flow.
- A spoken reply *during* dictation (dictation is STT→text only; the reply to the
  submitted turn speaks via the normal path).
- Editing the transcript mid-dictation (the field mirrors the live partial; edit
  happens after Stop, or the user just re-dictates).
- ari-linux (not yet implemented).

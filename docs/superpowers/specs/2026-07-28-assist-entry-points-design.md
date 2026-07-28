# Assist Entry Points — Design

**Date:** 2026-07-28
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

Ari has four ways a user might reasonably expect to summon it by voice. Only one
of them works.

| entry point | today |
| --- | --- |
| composer mic button | starts a voice turn |
| `ACTION_VOICE_COMMAND` (headset / car voice button) | not registered at all |
| `ACTION_ASSIST` | opens the chat UI, does not listen |
| assist gesture (long-press home, when Ari holds the role) | opens the chat UI, does not listen |

The assist gesture path is the most telling. `AriVoiceInteractionSession.onShow`
starts `MainActivity` with an `EXTRA_FROM_ASSIST` extra that **nothing reads** —
the file's own comment says "Future: we could pre-trigger STT here so the user
doesn't even need to tap the mic". So setting Ari as your digital assistant
currently buys you an app launcher.

The headset button matters beyond parity. It is the only zero-drain way to reach
Ari: no always-on microphone, no wake word model running, no battery cost until
the moment you press it. Always-on listening measures 5.18% of one core
continuously on a Pixel 10 Pro Fold. A button press costs nothing until pressed.
It also works on de-Googled devices, where no "Hey Google" fallback exists.

## What we are building

One shared launcher behind all four entry points, plus the trampoline activity
and role-sync needed to claim the headset button safely.

## Decisions

**1. All four entry points start a voice turn.** They share one code path rather
than each growing its own. This makes "set Ari as your assistant" mean something,
and removes the dead `EXTRA_FROM_ASSIST`.

**2. Precondition failures are spoken, not shown.** A headset button is pressed
with the phone in a pocket. There is no screen to put a toast on, and opening the
app is worse than useless over a lock screen. The user is wearing an audio device
by definition, so audio is the one channel guaranteed to reach them.

**3. Ari claims the headset button only while it holds the assistant role.**
`ACTION_VOICE_COMMAND` is a plain implicit intent. If another handler is
installed, Android can put a disambiguation chooser between the user and their
headset — on a locked phone, in a pocket, that is worse than the feature not
existing. The trampoline therefore ships disabled and is enabled at runtime.

## Architecture

All four entry points call `VoiceTurnLauncher`. It owns the preconditions, the
spoken failures, and the one-shot computation. Nothing else decides how to start
a turn.

Below the launcher nothing changes. It fires the same `WakeWordService` intent
that tap-to-talk already fires, so the voice overlay, the lock-screen path, the
`CaptureBus` hand-off and the transient capture-host teardown all come along
unmodified.

```
headset button ─┐
ACTION_ASSIST  ─┼─→ AssistTrampolineActivity ─┐
assist gesture ─── AriVoiceInteractionSession ┼─→ VoiceTurnLauncher ─→ WakeWordService
composer mic ────── ConversationViewModel ────┘                          (unchanged)
```

## Components

### `VoiceTurnLauncher` (new — `voice/`, Hilt singleton)

**What it does.** Single entry point for starting a voice turn.

```kotlin
sealed interface TurnLaunch {
    data object Started : TurnLaunch
    data object AlreadyActive : TurnLaunch
    data object NoMicPermission : TurnLaunch
    data object SttNotReady : TurnLaunch
}

enum class TurnSource { HEADLESS, IN_APP }

fun launch(source: TurnSource): TurnLaunch
```

Checks run in this order, first failure wins:

1. `voiceSession.isActive || WakeWordService.oneShotActive` → `AlreadyActive`,
   silent. Pressing the button mid-turn must not nag.
2. `RECORD_AUDIO` not granted → `NoMicPermission`.
3. `speechRecognizer.isModelLoaded` false → `SttNotReady`.
4. Otherwise compute one-shot, `startForegroundService`, `Started`.

**Why `TurnSource` exists.** `HEADLESS` speaks its failures. `IN_APP` does not —
the conversation screen already routes permission through the shared permission
launcher and would regress to worse UX if it started talking instead of showing
a system dialog. The caller says where it came from; the launcher decides what
that means. Distinct outcomes get distinct types so no caller has to
re-derive them.

**Depends on:** `Application`, `VoiceSession`, `SpeechRecognizer`, `SpeechOutput`
(already Hilt-provided by `EngineModule`).

### One-shot computation

Extracted as a pure function so it can be tested exhaustively:

```kotlin
internal fun computeOneShot(isRunning: Boolean, oneShotActive: Boolean): Boolean =
    !isRunning || oneShotActive
```

This logic exists **twice today** (`startVoiceTurn`, `startDictation`) and the
trampoline would make a third copy. It carries two non-obvious guards: the
`oneShotActive` term is sticky specifically so a second tap landing after the
transient host is up cannot send `EXTRA_ONE_SHOT=false` and strand a hot mic.
Extracting it is worth doing on its own merits, independent of this feature.

### `AssistTrampolineActivity` (new — `assistant/`, no UI)

Mirrors the existing `StartListeningActivity` pattern, which exists because
Android 14+ blocks mic-typed foreground services from starting in background
contexts while an activity in `onCreate` is unambiguously foreground.

Injects the launcher, calls it with `HEADLESS`, `finish()`, suppresses
transitions. Carries both `ACTION_VOICE_COMMAND` and `ACTION_ASSIST` filters.
Ships `android:enabled="false"`, `android:exported="true"`,
`android:excludeFromRecents="true"`, `android:noHistory="true"`, translucent
theme.

### `AssistRoleSync` (new — small helper)

Reads `RoleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)` (API 29+, and minSdk
is 29) and calls `setComponentEnabledSetting` on the trampoline with
`DONT_KILL_APP`.

Called from two places:

- `AriVoiceInteractionService.onReady()` — the system binds this the moment Ari
  is granted the assistant role, so the button is claimed without the user having
  to open the app.
- `MainActivity.onResume()` — catches the reverse. There is no system broadcast
  for losing the role, so the worst case is a stale-enabled filter until the user
  next opens Ari. Losing the role is the less urgent direction.

### Changed

- `ConversationViewModel.startVoiceTurn()` — calls `launcher.launch(IN_APP)` and
  drops its own `isActive || oneShotActive` guard, which the launcher now owns.
  Permission gating stays in the screen's existing permission launcher, which is
  why the call is `IN_APP` and therefore silent on failure.
- `ConversationViewModel.startDictation()` — uses `computeOneShot` **only**. It
  fires `ACTION_START_DICTATION` rather than `ACTION_START_VOICE_TURN`, so it
  cannot route through `launch()`. Its `isDictating` flag, model-loaded check and
  4-second safety net are unchanged.
- `AriVoiceInteractionSession.onShow()` — call the launcher, `finish()`. Delete
  the unread `EXTRA_FROM_ASSIST`.
- `AndroidManifest.xml` — `ACTION_ASSIST` moves off `MainActivity` onto the
  trampoline.

## Behaviour change

`ACTION_ASSIST` today always opens the chat UI. After this change Ari advertises
assist handling **only while it holds the assistant role**, and when it does, it
starts listening rather than opening the chat.

This is deliberate and follows from decisions 1 and 3, but it is a real change to
shipped behaviour and worth calling out for anyone reading this later wondering
why long-press home stopped opening the app.

## Strings

Two new keys, EN and IT, added in the same commit — the translation-parity check
is a post-push check rather than a merge gate, so a missing IT key fails after
the fact.

| key | EN |
| --- | --- |
| `assist_needs_mic_permission` | I need microphone permission first. Open Ari to grant it. |
| `assist_stt_not_ready` | My speech recognition isn't ready yet. Open Ari to finish setting up. |

**Italian below is AI-drafted and needs Keith's review before merge.** The
project's standing rule is source-language-only; this is a one-off exception he
asked for explicitly, on the condition that he reviews it.

| key | IT (draft) |
| --- | --- |
| `assist_needs_mic_permission` | Prima ho bisogno del permesso per il microfono. Apri Ari per concederlo. |
| `assist_stt_not_ready` | Il riconoscimento vocale non è ancora pronto. Apri Ari per completare la configurazione. |

Both are spoken, never displayed, so they should read as speech rather than as UI
copy.

## Error handling

- Launcher returns a result; the trampoline ignores it, because anything worth
  saying has already been said.
- Foreground-service start blocked → the existing `WakeWordService` path posts its
  tap-to-start recovery notification. No new handling.
- TTS not yet initialised → `SpeechOutput` handles its own readiness. A dropped
  failure announcement is acceptable; it is a degraded path already.

## Testing

**Unit — `computeOneShot`.** Table test over all four `(isRunning,
oneShotActive)` combinations asserting the exact boolean. This is the subtle
logic and the reason the extraction is worth doing.

**Unit — `VoiceTurnLauncher`.** One test per precondition asserting both the
returned type and whether `SpeechOutput` was called. Explicitly assert that
`IN_APP` does **not** speak on `NoMicPermission`, since that is the regression
this design is guarding against.

**Unit — `AssistRoleSync`.** Role held → component enabled; role absent →
disabled.

**Device.** The headset button needs a real check on hardware — OEM routing of
`ACTION_VOICE_COMMAND` varies and no unit test establishes whether a given phone
sends it. Verify: button press with Ari as assistant starts a turn; button press
with Ari not the assistant does not surface a chooser; press with the phone
locked behaves as the wake word does.

## Out of scope

- Listening modes (screen-on only / while charging / headset connected). Related
  and tracked separately in the backlog; this design is a prerequisite that makes
  a "headset connected" mode worth having.
- Any change to what happens *after* a turn starts. The overlay, lock-screen
  takeover and capture-host teardown are reused untouched.
- Media-button handling (`KEYCODE_MEDIA_PLAY`, headset hook long-press). A
  different, noisier surface that would compete with music apps.

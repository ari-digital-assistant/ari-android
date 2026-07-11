# Composer Voice Dictation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the composer mic do in-place foreground dictation — tap → STT streams partials into the input field → auto-submit on end-of-speech — instead of launching the background voice overlay.

**Architecture:** Add an STT-only "dictation mode" that reuses the tap-to-talk one-shot capture-host machinery (`WakeWordService` + `VoiceSession.dismiss` stand-down) but skips the overlay, engine, and TTS. `VoiceSession.startDictation()` streams `VoiceState.Listening(partial)` and emits the final transcript on a new `dictatedText` flow; `ConversationViewModel` routes partials → `inputText` and the final → `onTextSubmitted`. The composer border reacts via the existing `voicePhase` (screen stays foreground during dictation).

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, Kotlin Coroutines/Flow, JUnit4.

## Global Constraints

- **Frontend-only.** No `ari-engine` or skill changes.
- **Reuse the hardened path.** Do NOT re-implement mic-host teardown — the tap-to-talk hot-mic Critical bug lived there. `startDictation()` mirrors the existing hardened `startVoiceTurn()` guard, and teardown goes through `VoiceSession.dismiss()`.
- **Testing:** new pure logic is JUnit4 + `org.junit.Assert.*`, exact-value assertions. Audio/service-lifecycle behaviour has no unit-test seam (no mocking framework) → **device-verified on `emulator-5554`** (never Keith's Pixel). Run tests with:
  `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.installations.auto-detect=false :app:testDebugUnitTest --tests "<FQCN>"`
- **Build/compile** with `JAVA_HOME=/usr/lib/jvm/java-25-openjdk` **and** `-Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk` **and** `-Dorg.gradle.java.installations.auto-detect=false` (the last two are needed for `assembleDebug`; a stale VS Code-JRE daemon otherwise fails on missing `jlink` — `./gradlew --stop` if it does).
- **Strings:** new strings are ENGLISH source only in `res/values/strings.xml`, flagged `<!-- TODO(i18n): add values-it/ translation -->`. Do NOT create/modify `values-it/`.
- **Decisions (from the spec):** endpoint → auto-submit immediately; stop/cancel/error → keep partial text in the field; always-listening ON → dictation takes over briefly; cold start → mic disabled until STT model warm.
- **Do not remove** `startVoiceTurn()` / `ACTION_START_VOICE_TURN` — they're the just-hardened tap-to-talk code; the composer simply stops calling them. (Cleanup is a separate decision.)

---

## Task 1: Composer button state (pure logic)

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ComposerAction.kt`
- Test: `app/src/test/java/dev/heyari/ari/ui/conversation/ComposerActionTest.kt`

**Interfaces:**
- Produces: `enum class ComposerAction { Mic, Stop, Send }`; `fun composerAction(inputText: String, isDictating: Boolean): ComposerAction`. Consumed by `AriComposer` (Task 6).

- [ ] **Step 1: Update the failing test.** Replace `ComposerActionTest.kt` contents:

```kotlin
package dev.heyari.ari.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun blank_and_not_dictating_shows_mic() {
        assertEquals(ComposerAction.Mic, composerAction("", isDictating = false))
        assertEquals(ComposerAction.Mic, composerAction("   ", isDictating = false))
    }
    @Test fun nonblank_and_not_dictating_shows_send() {
        assertEquals(ComposerAction.Send, composerAction("hi", isDictating = false))
    }
    @Test fun dictating_shows_stop_regardless_of_text() {
        assertEquals(ComposerAction.Stop, composerAction("", isDictating = true))
        assertEquals(ComposerAction.Stop, composerAction("live partial words", isDictating = true))
    }
}
```

- [ ] **Step 2: Run it, watch it fail.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.installations.auto-detect=false :app:testDebugUnitTest --tests "dev.heyari.ari.ui.conversation.ComposerActionTest"`
Expected: FAIL (compile error — `composerAction` has one param / no `Stop`).

- [ ] **Step 3: Implement.** Replace `ComposerAction.kt` contents:

```kotlin
package dev.heyari.ari.ui.conversation

enum class ComposerAction { Mic, Stop, Send }

/** The composer's trailing button: Stop while dictating (tap cancels), Mic when
 *  the field is blank (tap starts dictation), Send otherwise. */
fun composerAction(inputText: String, isDictating: Boolean): ComposerAction = when {
    isDictating -> ComposerAction.Stop
    inputText.isBlank() -> ComposerAction.Mic
    else -> ComposerAction.Send
}
```

- [ ] **Step 4: Run it, watch it pass.** Same command as Step 2. Expected: PASS (5 assertions across 3 tests).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/ComposerAction.kt app/src/test/java/dev/heyari/ari/ui/conversation/ComposerActionTest.kt
git commit -m "ui: composer button gains a Stop (dictating) state

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `VoiceSession` dictation mode

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`

**Interfaces:**
- Consumes: existing `speechRecognizer` (`SpeechRecognizer`), `dismiss()`, `_state`/`state`.
- Produces: `val dictatedText: SharedFlow<String>`; `fun startDictation()`; `fun stopDictation()`. Consumed by `WakeWordService` (Task 3) and `ConversationViewModel` (Task 5).

This is intricate voice code — verification is a clean compile plus device testing in Task 8. No unit-test seam.

- [ ] **Step 1: Add the `dictatedText` flow.** After the existing `_state`/`state` declarations (around line 122-123):

```kotlin
    // Final transcript from an in-place dictation session (STT-only, no engine).
    // extraBufferCapacity=1 so the emit never suspends/drops even if the
    // collector is momentarily busy.
    private val _dictatedText = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val dictatedText: kotlinx.coroutines.flow.SharedFlow<String> =
        _dictatedText.asSharedFlow()
```

(`asSharedFlow` — add `import kotlinx.coroutines.flow.asSharedFlow` if not already present; `asStateFlow` is already imported, they live together.)

- [ ] **Step 2: Add `startDictation()` and `stopDictation()`.** Place them right after `dismiss()` (after line 674):

```kotlin
    /**
     * Foreground in-place dictation — STT only. No engine, no TTS, no re-arm,
     * no barge-in. Streams partials through [state] as VoiceState.Listening and
     * emits the final transcript on [dictatedText]; the caller (ConversationViewModel)
     * routes those into the composer. Reaching Idle (via [dismiss]) drives the
     * WakeWordService one-shot stand-down, exactly like a voice turn.
     */
    fun startDictation() {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "startDictation() called while a session is active — ignoring")
            return
        }
        // Non-Idle synchronously so the WakeWordService one-shot collector sees a
        // begun turn before any Idle (same reason start() does this).
        _state.value = VoiceState.Listening("")
        sessionJob = scope.launch {
            try {
                speechRecognizer.startListening()
                speechRecognizer.state.collect { stt ->
                    when (stt) {
                        is SttState.Listening ->
                            _state.update { VoiceState.Listening(stt.partial) }
                        SttState.Transcribing ->
                            // Offline whisper decode window — reflect it so the
                            // border shows Thinking rather than still-Listening.
                            _state.update { VoiceState.Thinking }
                        is SttState.Done -> {
                            _dictatedText.emit(stt.text)
                            dismiss()            // stops STT, cancels this job, → Idle
                            return@collect
                        }
                        is SttState.Error -> {
                            Log.w(TAG, "Dictation STT error: ${stt.message}")
                            dismiss()            // caller keeps the last partial
                            return@collect
                        }
                        SttState.Idle -> { /* ignore */ }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Dictation failed", t)
                dismiss()
            }
        }
    }

    /** Cancel an in-progress dictation (Stop button / lifecycle). Produces no
     *  final transcript, so nothing is submitted; the caller keeps the last
     *  partial already streamed into the field. */
    fun stopDictation() {
        if (sessionJob?.isActive != true) return
        dismiss()
    }
```

Imports to confirm present (add if missing): `dev.heyari.ari.stt.SttState`. `SttState` is referenced by the existing pipeline collector, so it is already imported.

- [ ] **Step 3: Compile.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "voice: add STT-only dictation mode to VoiceSession

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `WakeWordService` — `ACTION_START_DICTATION`

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/wakeword/WakeWordService.kt`

**Interfaces:**
- Consumes: `VoiceSession.startDictation()` (Task 2); existing `oneShotActive`, `oneShotTurnBegan`, `EXTRA_ONE_SHOT`, `isRunning`, `startListening()`, the onCreate one-shot stand-down collector.
- Produces: `const val ACTION_START_DICTATION`. Consumed by `ConversationViewModel` (Task 5).

- [ ] **Step 1: Add the action constant.** In the `companion object`, next to `ACTION_START_VOICE_TURN` (around line 565):

```kotlin
        const val ACTION_START_VOICE_TURN = "dev.heyari.ari.START_VOICE_TURN"
        const val ACTION_START_DICTATION = "dev.heyari.ari.START_DICTATION"
        const val EXTRA_ONE_SHOT = "one_shot"
```

- [ ] **Step 2: Handle it in `onStartCommand`.** Immediately AFTER the existing `if (intent?.action == ACTION_START_VOICE_TURN) { … }` block and BEFORE `return START_STICKY` (around line 188):

```kotlin
        if (intent?.action == ACTION_START_DICTATION) {
            // Foreground in-place dictation: same transient capture host as
            // tap-to-talk, but STT-only — no overlay. VoiceSession.startDictation()
            // streams partials to the composer and emits the final transcript;
            // reaching Idle stands this host down via the same one-shot collector
            // in onCreate.
            if (!isRunning) {
                Log.w(TAG, "Capture host failed to start — not starting dictation")
                return START_NOT_STICKY
            }
            oneShotActive = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
            oneShotTurnBegan = false
            voiceSession.startDictation()
            if (oneShotActive) {
                // Same START_NOT_STICKY reasoning as the voice-turn branch: a
                // sticky NULL-intent restart must not resurrect a full host.
                return START_NOT_STICKY
            }
        }
```

- [ ] **Step 3: Compile.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/dev/heyari/ari/wakeword/WakeWordService.kt
git commit -m "wakeword: ACTION_START_DICTATION starts an STT-only dictation host

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `ConversationState` fields

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/model/ConversationState.kt`

**Interfaces:**
- Produces: `ConversationState.isDictating: Boolean`, `ConversationState.sttReady: Boolean`. Consumed by Tasks 5 & 7.

- [ ] **Step 1: Add the fields.** Add to the `ConversationState` data class (alongside the existing flags like `isThinking`, `isListening`):

```kotlin
    /** True while an in-place dictation session is streaming into the composer. */
    val isDictating: Boolean = false,
    /** True once the STT model is loaded — gates the composer mic button. */
    val sttReady: Boolean = false,
```

- [ ] **Step 2: Compile.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/dev/heyari/ari/model/ConversationState.kt
git commit -m "ui: ConversationState gains isDictating + sttReady

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `ConversationViewModel` — dictation orchestration

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt`

**Interfaces:**
- Consumes: `voiceSession.startDictation()` / `stopDictation()` / `dictatedText` / `state` (Task 2), `WakeWordService.ACTION_START_DICTATION` (Task 3), `ConversationState.isDictating`/`sttReady` (Task 4), existing `speechRecognizer.isModelLoaded`, `onTextSubmitted`, `WakeWordService.isRunning`/`oneShotActive`.
- Produces: `fun startDictation()`, `fun stopDictation()`. Consumed by `ConversationScreen` (Task 7).

- [ ] **Step 1: Set `sttReady` when the model loads.** In `init`, the existing block that calls `sttModelLoader.ensureLoaded()` — add `sttReady` to its `_state.update`:

```kotlin
        viewModelScope.launch(Dispatchers.IO) {
            sttModelLoader.ensureLoaded()
            _state.update { it.copy(setupChecked = true, sttReady = speechRecognizer.isModelLoaded) }
            refreshOnboarding()
        }
```

And in the `settingsRepository.activeSttModelId.drop(1).collect { … }` block, after `sttModelLoader.ensureLoaded()`, add:

```kotlin
                _state.update { it.copy(sttReady = speechRecognizer.isModelLoaded) }
```

- [ ] **Step 2: Add the dictation collectors.** Add two new `viewModelScope.launch` blocks in `init` (near the other collectors):

```kotlin
        // Dictation: stream live partials into the input field; clear the flag
        // when the session ends (Idle/Error) — the last partial stays in the
        // field so a cancelled dictation isn't lost.
        viewModelScope.launch {
            voiceSession.state.collect { vs ->
                if (!_state.value.isDictating) return@collect
                when (vs) {
                    is VoiceState.Listening -> _state.update { it.copy(inputText = vs.partial) }
                    VoiceState.Idle -> _state.update { it.copy(isDictating = false) }
                    is VoiceState.Error -> _state.update { it.copy(isDictating = false) }
                    else -> { /* Preparing/Thinking/Responding: leave the field */ }
                }
            }
        }
        // Dictation final transcript → submit as if typed. onTextSubmitted's own
        // blank guard makes an empty utterance a no-op.
        viewModelScope.launch {
            voiceSession.dictatedText.collect { text ->
                _state.update { it.copy(isDictating = false, inputText = text) }
                onTextSubmitted(text)
            }
        }
```

- [ ] **Step 3: Add `startDictation()` and `stopDictation()`.** Place them next to the existing `startVoiceTurn()`:

```kotlin
    /**
     * Foreground composer dictation. Mirrors [startVoiceTurn]'s hardened guard +
     * one-shot computation, but routes to ACTION_START_DICTATION (STT-only, no
     * overlay). Gated by the caller on sttReady; guarded here against an active
     * wake turn (the CaptureBus is single-consumer).
     */
    fun startDictation() {
        if (voiceSession.isActive || WakeWordService.oneShotActive) return
        if (!speechRecognizer.isModelLoaded) return
        _state.update { it.copy(isDictating = true) }
        val oneShot = !WakeWordService.isRunning || WakeWordService.oneShotActive
        val intent = Intent(application, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START_DICTATION
            putExtra(WakeWordService.EXTRA_ONE_SHOT, oneShot)
        }
        ContextCompat.startForegroundService(application, intent)
    }

    /** Stop button: cancel dictation, keep the partial already in the field. */
    fun stopDictation() {
        voiceSession.stopDictation()
        _state.update { it.copy(isDictating = false) }
    }
```

(`Intent`, `ContextCompat`, `WakeWordService`, `VoiceState` are already imported — `startVoiceTurn` uses the first three and the voice collectors use `VoiceState`.)

- [ ] **Step 4: Compile.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt
git commit -m "ui: ConversationViewModel drives composer dictation (partials->field, final->submit)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `AriComposer` — three-state button + mic gating

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/AriComposer.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `composerAction(inputText, isDictating)` (Task 1).
- Produces: `AriComposer(value, onValueChange, onSend, onMicTap, onStop, isDictating, micEnabled, ambientState, modifier)`. Consumed by `ConversationScreen` (Task 7).

- [ ] **Step 1: Add the string.** In `res/values/strings.xml`, next to `conversation_talk` / `conversation_send`:

```xml
    <!-- TODO(i18n): add values-it/ translation -->
    <string name="conversation_stop_dictation">Stop dictation</string>
```

- [ ] **Step 2: Update `AriComposer`.** Add the new params and the three-state button. Replace the function signature + the `IconButton` block:

```kotlin
@Composable
fun AriComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    onStop: () -> Unit,
    isDictating: Boolean,
    micEnabled: Boolean,
    ambientState: AmbientState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .ambientComposerBorder(ambientState),
            placeholder = { Text(stringResource(R.string.conversation_input_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        val action = composerAction(value, isDictating)
        IconButton(
            onClick = {
                when (action) {
                    ComposerAction.Send -> onSend()
                    ComposerAction.Stop -> onStop()
                    ComposerAction.Mic -> onMicTap()
                }
            },
            enabled = action != ComposerAction.Mic || micEnabled,
        ) {
            when (action) {
                ComposerAction.Send -> Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.conversation_send),
                )
                ComposerAction.Stop -> Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.conversation_stop_dictation),
                )
                ComposerAction.Mic -> Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.conversation_talk),
                )
            }
        }
    }
}
```

Add imports: `androidx.compose.material.icons.filled.Stop`.

- [ ] **Step 3: Compile.**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: `AriComposer` call in `ConversationScreen.kt` now fails to compile (missing new args) — that's Task 7. To compile THIS task in isolation, do Step 3 together with Task 7 (they're one compile unit). Note it and proceed to Task 7 before compiling.

- [ ] **Step 4: Commit** (with Task 7, since they share a compile). See Task 7 Step 3.

---

## Task 7: Wire the composer in `ConversationScreen`

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt`

**Interfaces:**
- Consumes: `viewModel.startDictation()` / `stopDictation()` (Task 5), `state.isDictating` / `state.sttReady` (Task 4), the new `AriComposer` signature (Task 6).

- [ ] **Step 1: Update the `AriComposer` call.** Replace the existing call (the `onMicTap` currently points at `startVoiceTurn`):

```kotlin
                AriComposer(
                    value = state.inputText,
                    onValueChange = viewModel::onInputChanged,
                    onSend = { viewModel.onTextSubmitted(state.inputText) },
                    onMicTap = { withVoicePermissions { viewModel.startDictation() } },
                    onStop = { viewModel.stopDictation() },
                    isDictating = state.isDictating,
                    micEnabled = state.sttReady,
                    ambientState = ambient,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
```

- [ ] **Step 2: Compile (covers Task 6 + 7).**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit Tasks 6 + 7.**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/AriComposer.kt app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt app/src/main/res/values/strings.xml
git commit -m "ui: composer mic -> in-place dictation (three-state button, mic gated on sttReady)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Device verification (emulator only)

**Files:** none (verification).

No unit-test seam for the audio/service lifecycle — verify on `emulator-5554` (never Keith's Pixel). Build + install:
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk -Dorg.gradle.java.installations.auto-detect=false :app:installDebug`

- [ ] **Happy path (always-listening OFF):** tap the composer mic, speak — words stream into the field live, the border shows Listening; stop speaking → it auto-submits and Ari replies in-chat (no overlay). **Then confirm the mic FGS stood down** (`adb shell dumpsys activity services | grep -i wakeword` shows nothing running; the always-listening switch stayed OFF).
- [ ] **Happy path (always-listening ON):** flip "Hey Ari" on, tap the composer mic, dictate — after it submits, the wake word still works ("Hey Ari …") and the switch is still ON (service survived).
- [ ] **Stop button:** start dictating, tap the button (now Stop) mid-sentence → dictation stops, the partial text stays in the field, nothing is submitted.
- [ ] **Empty utterance:** tap mic, stay silent until endpoint → nothing submitted, field empty, host stood down.
- [ ] **Cold-start gate:** immediately on a fresh launch the mic is disabled; it enables within a second or two (model warm).
- [ ] **Hot-mic stress (given the history):** double-tap the mic fast; tap mic then immediately Stop; trigger an STT error (e.g. no model) — in every case the mic must not be left hot and the always-listening switch must not turn itself on.

If any hot-mic case fails, STOP and treat as Critical (this is the exact class of bug we fixed before).

## Self-review notes (coverage)

Spec → task: `composerButton`/button states = Task 1 + 6; `VoiceSession.startDictation/stopDictation/dictatedText` = Task 2; `WakeWordService.ACTION_START_DICTATION` = Task 3; `ConversationState.isDictating/sttReady` = Task 4; ViewModel orchestration (partials→field, final→submit, guards, sttReady, cold-start gate) = Task 5; three-state button + mic gating + Stop string = Task 6; composer wiring (mic→dictation) = Task 7; border-via-voicePhase needs no code (spec §"Unchanged: the ambient border") and is checked in Task 8; all edge cases (always-listening on/off, stop, empty, cold-start, hot-mic) = Task 8. The `isDictating`→`deriveAmbientState` fallback is intentionally NOT built (only if Task 8 shows the border doesn't react — see spec).

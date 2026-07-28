# Wake-Word False-Accept Containment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop wake-word false accepts from reaching the engine, and start collecting the false-trigger audio needed to retrain the model.

**Architecture:** Sherpa already transcribes the wake phrase (the `CaptureBus` 2 s rewind pulls it into the STT stream). Surface whether the strip actually found an "ari"-ish name token, carry that verdict on `SttState.Done`, and let `VoiceSession` dismiss a wake-initiated turn whose transcript never contained the name — before it dispatches. Fail open on every ambiguity. Separately, an opt-in debug setting persists false-trigger audio as 16 kHz WAV to app-private storage for a future retrain.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, JUnit 4, sherpa-onnx, microWakeWord (TFLite Micro via JNI).

**Spec:** `docs/superpowers/specs/2026-07-27-wake-word-false-accept-design.md`

## Global Constraints

- **Read `antislop.md` at the repo root before writing code. It is law.** Particularly #1 (reuse existing utilities), #9 (match surrounding style), #11 (don't over-comment), #13 (don't add config for things that should be hardcoded), #31 (tests assert exact values).
- **`JAVA_HOME=/usr/lib/jvm/java-25-openjdk`** is required for gradle on this machine. Every gradle command in this plan assumes it is exported.
- **Unit test command:** `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "<fqcn>"` from `ari-android/`. Note this also compiles the Rust engine via the `android.rust` plugin, so the first run is slow.
- **Device testing is on `emulator-5554`.** Never run `connectedAndroidTest` or churn reinstalls on the physical Pixel.
- **Fail open, always.** Any ambiguity in wake verification must result in the turn being accepted. A wrongly-rejected genuine command reads to the user as "Ari ignored me", which is worse than a spurious chime.
- **Translation parity:** `values/strings.xml` and `values-it/strings.xml` are currently at exact parity (347 strings each). New EN keys need IT counterparts **in the same commit**. Per `antislop.md` #2, **do not machine-generate the Italian** — Task 6 stops and asks Keith for it.
- **Direct to `main`.** PRs are only required for skill changes in `ari-skills/skills/`. Verify you are on `main` before every commit.
- **No audio leaves the device.** No upload, no telemetry, no analytics on any captured audio, ever.

### Two traps — do not "improve" these

Both look like obvious wins and both are wrong. They are in the spec for the same reason.

1. **Do not use the parallel sherpa stream as a second verification opinion.** It is already computed at `SpeechRecognizer.kt:461-482` and looks free, but per `SpeechRecognizer.kt:728-729` it **deliberately skips the pre-roll** — which is exactly where the wake phrase lives. It has nothing to verify against, so wiring it in would reject every wake.
2. **Do not lengthen `detectionDebounceMs` after a rejection.** Superficially sensible, since a rejection is evidence the environment is producing false fires. But if a *genuine* wake was rejected, the user says it again within a couple of seconds and a longer lockout eats the retry — a worse failure than the one it fixes.

---

### Task 1: Surface the wake-phrase verdict

`stripWakePhrase()` already runs a two-stage match — stage 1 finds `opener? + name`, stage 2 falls back to stripping a bare leading opener. It throws away *which* stage fired, and that is exactly the signal we need. This task exposes it. No behaviour changes.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/WakePhrase.kt:98-114`
- Test: `app/src/test/java/dev/heyari/ari/voice/WakePhraseTest.kt` (create — there are currently no tests for this file at all)

**Interfaces:**
- Consumes: nothing.
- Produces: `data class WakeMatch(val text: String, val nameMatched: Boolean)` and `fun matchWakePhrase(text: String, locale: String = "en"): WakeMatch`, both in package `dev.heyari.ari.voice`. `fun stripWakePhrase(text: String, locale: String = "en"): String` keeps its exact current signature and behaviour.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/voice/WakePhraseTest.kt`:

```kotlin
package dev.heyari.ari.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun `wake phrase with command reports a name match and strips it`() {
        val match = matchWakePhrase("hey ari whats the weather")
        assertTrue(match.nameMatched)
        assertEquals("whats the weather", match.text)
    }

    @Test
    fun `opener with no name does not report a name match`() {
        val match = matchWakePhrase("okay so whats the weather")
        assertFalse(match.nameMatched)
        assertEquals("so whats the weather", match.text)
    }

    @Test
    fun `mishear from the name list still reports a name match`() {
        val match = matchWakePhrase("harry can you set a timer")
        assertTrue(match.nameMatched)
        assertEquals("can you set a timer", match.text)
    }

    @Test
    fun `unrelated speech reports no name match and is left alone`() {
        val match = matchWakePhrase("i was talking to dave about it")
        assertFalse(match.nameMatched)
        assertEquals("i was talking to dave about it", match.text)
    }

    @Test
    fun `bare wake phrase reports a name match and empties the text`() {
        val match = matchWakePhrase("hey ari")
        assertTrue(match.nameMatched)
        assertEquals("", match.text)
    }

    @Test
    fun `ok opener with name reports a name match`() {
        val match = matchWakePhrase("ok ari whats the time")
        assertTrue(match.nameMatched)
        assertEquals("whats the time", match.text)
    }

    @Test
    fun `empty input reports no name match`() {
        val match = matchWakePhrase("")
        assertFalse(match.nameMatched)
        assertEquals("", match.text)
    }

    @Test
    fun `stripWakePhrase still returns just the text`() {
        assertEquals("whats the weather", stripWakePhrase("hey ari whats the weather"))
        assertEquals("so whats the weather", stripWakePhrase("okay so whats the weather"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.voice.WakePhraseTest"
```

Expected: FAIL — compilation error, `Unresolved reference: matchWakePhrase`.

- [ ] **Step 3: Implement**

In `app/src/main/java/dev/heyari/ari/voice/WakePhrase.kt`, replace the existing `stripWakePhrase` function (lines 98-114, including its KDoc) with:

```kotlin
/**
 * The outcome of a wake-phrase strip: the remaining query text, and whether a
 * real name token was found (stage 1) rather than just a bare leading opener
 * (stage 2). The flag is the signal used to verify that a wake-word detection
 * was genuinely addressed to Ari — the 64 KB detector fires on phonetic shape
 * alone, sherpa knows what words are.
 */
data class WakeMatch(val text: String, val nameMatched: Boolean)

/**
 * Strip the wake phrase from [text], using locale-specific mishears stacked on
 * top of the baseline English patterns. [locale] should be an ISO 639-1
 * lowercase code (`"en"`, `"it"`, …); unknown locales fall back to
 * baseline-only behaviour.
 */
fun matchWakePhrase(text: String, locale: String = "en"): WakeMatch {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return WakeMatch("", false)
    val (full, leading) = regexesFor(locale)
    val afterFull = full.replaceFirst(trimmed, "")
    if (afterFull != trimmed) return WakeMatch(afterFull.trim(), true)
    // Strict regex didn't match. Fall back to stripping a bare leading
    // opener — sherpa sometimes elides the wake-word name entirely, leaving
    // just "okay what time is it" with no recognisable "ari" token.
    return WakeMatch(leading.replaceFirst(trimmed, "").trim(), false)
}

/** [matchWakePhrase] for the call sites that only care about the query text. */
fun stripWakePhrase(text: String, locale: String = "en"): String =
    matchWakePhrase(text, locale).text
```

`stripWakePhrase` is kept as a wrapper rather than deleted (which would otherwise trip `antislop.md` #28). It has four call sites today, all in `SpeechRecognizer.kt`. Task 3 converts two of them (the streaming partial and the whisper decode) to `matchWakePhrase` because they need the verdict; the other two — the parallel-stream finalisation and `transcribeOffline` — genuinely only want the text, and churning them adds noise for no gain.

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.voice.WakePhraseTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/voice/WakePhrase.kt \
        app/src/test/java/dev/heyari/ari/voice/WakePhraseTest.kt
git commit -m "feat(wakeword): expose whether a wake-phrase name token matched"
```

---

### Task 2: The wake-verification decision rule

A pure function, no Android dependencies, matching the existing `shouldRearm` / `isStaleTurn` / `shouldCutTts` helpers at the top of `VoiceSession.kt` — those are deliberately top-level so they can be unit-tested without Robolectric.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt` (add near `shouldRearm` at line 59)
- Test: `app/src/test/java/dev/heyari/ari/voice/VoiceSessionTest.kt` (append to existing file)

**Interfaces:**
- Consumes: nothing (takes primitives).
- Produces: `internal fun shouldAcceptWake(verifyWake: Boolean, raw: String?, nameMatched: Boolean?): Boolean` in package `dev.heyari.ari.voice`.

- [ ] **Step 1: Write the failing test**

Append these tests inside the existing `class VoiceSessionTest` in `app/src/test/java/dev/heyari/ari/voice/VoiceSessionTest.kt` (before the closing brace):

```kotlin
    @Test
    fun `non-wake turns are never verified`() {
        assertTrue(shouldAcceptWake(verifyWake = false, raw = "hey there mate", nameMatched = false))
    }

    @Test
    fun `missing verdict fails open`() {
        assertTrue(shouldAcceptWake(verifyWake = true, raw = "hey there mate", nameMatched = null))
    }

    @Test
    fun `blank transcript fails open`() {
        assertTrue(shouldAcceptWake(verifyWake = true, raw = "", nameMatched = false))
        assertTrue(shouldAcceptWake(verifyWake = true, raw = "   ", nameMatched = false))
        assertTrue(shouldAcceptWake(verifyWake = true, raw = null, nameMatched = false))
    }

    @Test
    fun `wake turn with a name token is accepted`() {
        assertTrue(shouldAcceptWake(verifyWake = true, raw = "hey ari whats the weather", nameMatched = true))
    }

    @Test
    fun `wake turn with speech but no name token is rejected`() {
        assertFalse(shouldAcceptWake(verifyWake = true, raw = "hey there mate", nameMatched = false))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.voice.VoiceSessionTest"
```

Expected: FAIL — compilation error, `Unresolved reference: shouldAcceptWake`.

- [ ] **Step 3: Implement**

In `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`, add immediately after the existing `shouldRearm` function:

```kotlin
/**
 * Post-hoc wake verification. The 64 KB detector fires on phonetic shape and
 * saturates its confidence even when it's wrong, so the threshold has no
 * headroom left; sherpa — which is already transcribing the same audio via the
 * CaptureBus rewind — is the second opinion. A wake turn whose transcript
 * contains speech but no "ari"-ish name token was not addressed to Ari.
 *
 * Fails open on every ambiguity: a wrongly-rejected genuine command reads as
 * "Ari ignored me", which is a worse experience than a spurious chime.
 */
internal fun shouldAcceptWake(
    verifyWake: Boolean,
    raw: String?,
    nameMatched: Boolean?,
): Boolean = when {
    !verifyWake -> true
    nameMatched == null -> true
    raw.isNullOrBlank() -> true
    else -> nameMatched
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.voice.VoiceSessionTest"
```

Expected: PASS — the 5 new tests plus the 8 pre-existing ones.

- [ ] **Step 5: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt \
        app/src/test/java/dev/heyari/ari/voice/VoiceSessionTest.kt
git commit -m "feat(voice): add the wake-verification decision rule"
```

---

### Task 3: Carry the verdict and enforce the gate

Wires Task 1's verdict through `SttState.Done` into Task 2's rule, and plumbs a `verifyWake` flag from the wake detection down to the session. **The scope guard matters:** `launchVoiceOverlay()` is shared by the wake path *and* tap-to-talk. Tap-to-talk has no wake phrase in its pre-roll, so verifying it would bin every tap-to-talk turn.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/stt/SpeechRecognizer.kt:443-445`, `:496`, `:621-626`, `:781-796`
- Modify: `app/src/main/java/dev/heyari/ari/wakeword/WakeWordService.kt:173`, `:399`, `:412-414`, companion object at `:573`
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceOverlayActivity.kt:47`
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt:182`, `:275-308`, `:660-683`

**Interfaces:**
- Consumes: `matchWakePhrase` / `WakeMatch` (Task 1), `shouldAcceptWake` (Task 2).
- Produces: `SttState.Done(text, parallel, audio, raw, nameMatched)`; `VoiceSession.start(verifyWake: Boolean)`; `WakeWordService.EXTRA_VERIFY_WAKE`.

- [ ] **Step 1: Add the fields to `SttState.Done`**

In `app/src/main/java/dev/heyari/ari/stt/SpeechRecognizer.kt`, extend the `Done` data class (currently at lines 794-798). Add the two params and extend the existing KDoc block above it with:

```
     * @param raw The transcript before wake-phrase stripping, or null where
     *   only a cleaned partial survived (the manual [stopListening] path).
     *   Used for the wake-rejection log and the false-trigger capture sidecar.
     * @param nameMatched Whether [raw] contained a real wake-phrase name token.
     *   Null when no verdict could be formed — treated as "accept" downstream.
     *   Computed here rather than in the host because this class already holds
     *   the active locale that [matchWakePhrase] needs.
```

```kotlin
    data class Done(
        val text: String,
        val parallel: String? = null,
        val audio: ShortArray? = null,
        val raw: String? = null,
        val nameMatched: Boolean? = null,
    ) : SttState
```

- [ ] **Step 2: Populate them on the streaming path**

In the same file, replace lines 443-444:

```kotlin
                    val rawPartial = rec.getResult(currentStream).text.trim()
                    val cleanedPartial = stripWakePhrase(rawPartial, localeProvider.currentLocale())
```

with:

```kotlin
                    val rawPartial = rec.getResult(currentStream).text.trim()
                    val partialMatch = matchWakePhrase(rawPartial, localeProvider.currentLocale())
                    val cleanedPartial = partialMatch.text
```

Then replace line 496:

```kotlin
                        _state.value = SttState.Done(cleanedPartial, parallelText, mergedAudio)
```

with:

```kotlin
                        _state.value = SttState.Done(
                            cleanedPartial,
                            parallelText,
                            mergedAudio,
                            rawPartial,
                            partialMatch.nameMatched,
                        )
```

Add `import dev.heyari.ari.voice.matchWakePhrase` alongside the existing `import dev.heyari.ari.voice.stripWakePhrase` at line 19.

- [ ] **Step 3: Populate them on the whisper path**

In the same file, replace lines 621-626:

```kotlin
        val cleaned = stripWakePhrase(transcript, localeProvider.currentLocale())
        Log.i(TAG, "Whisper transcript: raw='$transcript' cleaned='$cleaned'")

        // No parallel stream, no audio-for-retry: whisper is the final
        // word. The retry layers in VoiceSession skip on null.
        _state.value = SttState.Done(text = cleaned, parallel = null, audio = null)
```

with:

```kotlin
        val match = matchWakePhrase(transcript, localeProvider.currentLocale())
        Log.i(TAG, "Whisper transcript: raw='$transcript' cleaned='${match.text}'")

        // No parallel stream, no audio-for-retry: whisper is the final
        // word. The retry layers in VoiceSession skip on null.
        _state.value = SttState.Done(
            text = match.text,
            parallel = null,
            audio = null,
            raw = transcript,
            nameMatched = match.nameMatched,
        )
```

Leave the `stopListening()` construction at line 644 alone — it has no raw transcript, so its defaults (`null`, `null`) correctly fail open.

**As implemented, both sites pass the match through `wakeVerdict(match, locale)` rather than reading `match.nameMatched` directly.** That helper is the English-only gate — an owner decision taken after this plan was written, described in spec §1 ("The gate is English-only"). It returns `null` outside `en`, which `shouldAcceptWake` accepts. Do not "simplify" it back to `match.nameMatched`; `WakePhraseTest` will fail if you do, which is the point.

- [ ] **Step 4: Plumb `verifyWake` from the service**

In `app/src/main/java/dev/heyari/ari/wakeword/WakeWordService.kt`:

Add to the companion object, next to `EXTRA_ONE_SHOT` (line 594):

```kotlin
        // True only for turns started by an actual wake-word detection. The
        // tap-to-talk path shares launchVoiceOverlay() but has no wake phrase
        // in its pre-roll, so verifying it would bin every tap-to-talk turn.
        const val EXTRA_VERIFY_WAKE = "verify_wake"
```

Change the signature at line 412 from `private fun launchVoiceOverlay(): Boolean {` to `private fun launchVoiceOverlay(verifyWake: Boolean): Boolean {`, and add the extra inside the `apply` block at line 414:

```kotlin
        val intent = Intent(this, VoiceOverlayActivity::class.java).apply {
            putExtra(EXTRA_VERIFY_WAKE, verifyWake)
            addFlags(
```

Update both call sites:
- Line 399 (in `onWakeWordDetected`): `launchVoiceOverlay(verifyWake = true)`
- Line 173 (tap-to-talk): `val launched = launchVoiceOverlay(verifyWake = false)`

- [ ] **Step 5: Pass it into the session**

In `app/src/main/java/dev/heyari/ari/voice/VoiceOverlayActivity.kt`, replace line 47:

```kotlin
        voiceSession.start()
```

with:

```kotlin
        voiceSession.start(
            verifyWake = intent.getBooleanExtra(WakeWordService.EXTRA_VERIFY_WAKE, false)
        )
```

Add `import dev.heyari.ari.wakeword.WakeWordService`.

- [ ] **Step 6: Enforce the gate**

In `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`:

Add a field next to `awaitingReply` (around line 146):

```kotlin
    // True from a wake-initiated start() until that session's first final
    // transcript is accepted. Only the opening turn of a wake session carries
    // the wake phrase in its pre-roll; re-armed reply turns arm with
    // rewindSeconds = 0f and have nothing to verify against.
    @Volatile
    private var verifyWake: Boolean = false
```

Change the signature at line 182 from `fun start() {` to `fun start(verifyWake: Boolean) {`, and set the field as the first statement inside the `sessionJob?.isActive` guard block — specifically, immediately after that guard returns, before `engineHolder.peek()?.cancelPendingReply()`:

```kotlin
        this.verifyWake = verifyWake
```

In the `is SttState.Done ->` branch, insert the gate **after** the existing `isStaleTurn` block (i.e. after line 303's closing brace) and **before** the `handleFinalText(...)` call at line 304:

```kotlin
                                if (!shouldAcceptWake(verifyWake, sttState.raw, sttState.nameMatched)) {
                                    Log.w(TAG, "Wake rejected: raw='${sttState.raw}'")
                                    dismiss()
                                    return@collect
                                }
                                verifyWake = false
```

Ordering is load-bearing: a straggler from a superseded turn is already discarded by `isStaleTurn`, and re-judging it here would evaluate a turn that isn't happening.

Finally, add `verifyWake = false` inside `dismiss()` (line 660), next to `awaitingReply = false`, so a dismissed session never leaves the flag set for the next one.

- [ ] **Step 7: Build and run the full unit suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS. No test asserts the new wiring directly — Tasks 1 and 2 cover the logic; this step is a regression check that nothing else broke on the `SttState.Done` signature change.

- [ ] **Step 8: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat | grep -E "VoiceSession|SpeechRecognizer|MicroWakeWordEngine"
```

Three checks, all against the emulator:

1. **Genuine wake still works.** Say:
   ```
   Hey Ari, what's the weather
   ```
   Expect a normal answer. Logcat shows `raw='hey ari whats the weather'` and no `Wake rejected`.

2. **Tap-to-talk still works.** Tap the mic, say:
   ```
   what's the weather
   ```
   Expect a normal answer. This is the regression that the scope guard exists to prevent — if this dismisses silently, `EXTRA_VERIFY_WAKE` is being set on the tap path.

3. **A false accept is contained.** Trigger the wake word (or force it), then say:
   ```
   hey there mate what time is it
   ```
   Expect the overlay to vanish silently with `Wake rejected: raw='hey there mate what time is it'` in logcat, no engine dispatch, and nothing added to the chat history.

- [ ] **Step 9: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/stt/SpeechRecognizer.kt \
        app/src/main/java/dev/heyari/ari/wakeword/WakeWordService.kt \
        app/src/main/java/dev/heyari/ari/voice/VoiceOverlayActivity.kt \
        app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat(voice): dismiss wake turns whose transcript has no name token"
```

---

### Task 4: Shorten the initial wake turn's silence window

After a false accept the mic currently stays armed for **thirty seconds**, which is the actual mechanism by which ambient speech reached the engine. This still helps when the verifier's token list lets something through, so it is worth having independently.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt:244-257`, `:300`, `:315`, `:767`

**Interfaces:**
- Consumes: `verifyWake` field (Task 3).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Parameterise the silence watcher**

In `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`, replace the `launchSilenceWatcher` local function and its first invocation (lines 244-257):

```kotlin
                fun launchSilenceWatcher(): Job = launch {
                    while (isActive) {
                        delay(1000)
                        val idle = System.currentTimeMillis() - lastActivityAt
                        if (idle > SILENCE_TIMEOUT_MS) {
                            Log.i(TAG, "No speech detected within $SILENCE_TIMEOUT_MS ms — dismissing")
                            dismiss()
                            return@launch
                        }
                    }
                }
                // The silence watcher is held in a var so we can relaunch it for
                // a re-armed reply turn (the previous one self-cancels at Done).
                var silenceWatcher = launchSilenceWatcher()
```

with:

```kotlin
                fun launchSilenceWatcher(timeoutMs: Long): Job = launch {
                    while (isActive) {
                        delay(1000)
                        val idle = System.currentTimeMillis() - lastActivityAt
                        if (idle > timeoutMs) {
                            Log.i(TAG, "No speech detected within $timeoutMs ms — dismissing")
                            dismiss()
                            return@launch
                        }
                    }
                }
                // The silence watcher is held in a var so we can relaunch it for
                // a re-armed reply turn (the previous one self-cancels at Done).
                // A wake turn gets a much shorter window: you have just said
                // "Hey Ari", so silence means it wasn't you — and every second
                // the mic stays armed after a false accept is a second in which
                // ambient speech can be taken as your command.
                var silenceWatcher = launchSilenceWatcher(
                    if (verifyWake) WAKE_TURN_SILENCE_TIMEOUT_MS else SILENCE_TIMEOUT_MS
                )
```

**Correction (post-implementation):** the shipped condition is `if (verifyWakePending && speechRecognizer.isStreaming)`. The field was renamed away from the `start()` parameter it shadowed, and the short window is restricted to the streaming recogniser — see Step 5 check 2 for why. The snippet above is left as written for the record.

- [ ] **Step 2: Update the two re-arm call sites**

Still in the `SttState.Done` collector, change both remaining invocations (lines 300 and 315, inside the `if (awaitingReply && isActive)` blocks) from `launchSilenceWatcher()` to:

```kotlin
                                    silenceWatcher = launchSilenceWatcher(SILENCE_TIMEOUT_MS)
```

A re-armed reply turn keeps the full 30 s — a skill asked a question and thinking time is legitimate.

- [ ] **Step 3: Add the constant**

In the companion object, next to `SILENCE_TIMEOUT_MS` (line 767):

```kotlin
        private const val WAKE_TURN_SILENCE_TIMEOUT_MS = 8_000L
```

Leave the dictation watcher at line 709 on `SILENCE_TIMEOUT_MS` — that is a deliberate user tap, not a wake.

- [ ] **Step 4: Build and run the unit suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS. There is no unit test for this — the watcher is a coroutine on wall-clock time, and a test that mocked the clock would be asserting the mock rather than the behaviour (`antislop.md` #31). Verified on device instead.

- [ ] **Step 5: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat | grep VoiceSession
```

1. **Wake turn dies in ~8 s.** Say just the wake phrase and then stay quiet:
   ```
   Hey Ari
   ```
   Expect `No speech detected within 8000 ms — dismissing` roughly eight seconds later.

2. **Slow speakers are not cut off — on the streaming recogniser.** With an English streaming model active, say the wake phrase, wait ~6 seconds, then speak:
   ```
   Hey Ari
   ```
   ```
   what's the weather
   ```
   Expect a normal answer — `lastActivityAt` refreshes on non-blank partials, so the 8 s is silence, not turn length.

   The offline whisper path emits no partials between arm and endpoint, so it never refreshes `lastActivityAt` and the short window would cap the utterance instead of the silence. It is therefore excluded: the wake turn only gets 8 s when `speechRecognizer.isStreaming` is true. Check that too — switch the locale to Italian (whisper), wake, pause ~10 s, then speak, and expect a normal answer plus `within 30000 ms` if you let it die.

3. **Reply turns keep 30 s.** Trigger a skill follow-up question and leave it hanging; expect `within 30000 ms`.

- [ ] **Step 6: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat(voice): cut the initial wake turn's silence window to 8s"
```

---

### Task 5: False-trigger capture store

Pure storage layer, no Android UI and no wiring yet. Writes 16-bit PCM WAV at 16 kHz — exactly the format microWakeWord training expects, so clips drop into a training set with no conversion. Hard-bounded so it can never grow without limit.

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt`
- Test: `app/src/test/java/dev/heyari/ari/wakeword/WakeCaptureStoreTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces, in package `dev.heyari.ari.wakeword`:
  - `enum class WakeCaptureHook(val slug: String) { REJECTED("rejected"), SILENT("silent") }`
  - `internal fun wavBytes(pcm: ShortArray): ByteArray`
  - `internal fun evictOldest(dir: File, maxFiles: Int, maxBytes: Long)`
  - `data class WakeCaptureStats(val count: Int, val totalBytes: Long)`
  - `@Singleton class WakeCaptureStore @Inject constructor(@param:ApplicationContext context: Context)` with `fun save(pcm: ShortArray, rawTranscript: String, hook: WakeCaptureHook, timestampMs: Long)`, `fun stats(): WakeCaptureStats`, `fun clear()`, `val dir: File`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/wakeword/WakeCaptureStoreTest.kt`:

```kotlin
package dev.heyari.ari.wakeword

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeCaptureStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun le(bytes: ByteArray, offset: Int, length: Int): Int =
        ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN).let {
            if (length == 2) it.short.toInt() else it.int
        }

    @Test
    fun `wav header describes 16 bit mono 16 kHz PCM`() {
        val pcm = ShortArray(800) { it.toShort() }
        val out = wavBytes(pcm)

        assertEquals(44 + 1600, out.size)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 1600, le(out, 4, 4))
        assertEquals("WAVE", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(out, 12, 4, Charsets.US_ASCII))
        assertEquals(16, le(out, 16, 4))
        assertEquals(1, le(out, 20, 2))
        assertEquals(1, le(out, 22, 2))
        assertEquals(16000, le(out, 24, 4))
        assertEquals(32000, le(out, 28, 4))
        assertEquals(2, le(out, 32, 2))
        assertEquals(16, le(out, 34, 2))
        assertEquals("data", String(out, 36, 4, Charsets.US_ASCII))
        assertEquals(1600, le(out, 40, 4))
    }

    @Test
    fun `wav samples are little endian and round trip`() {
        val out = wavBytes(shortArrayOf(0, 1, -1, 258))
        assertEquals(0, le(out, 44, 2))
        assertEquals(1, le(out, 46, 2))
        assertEquals(-1, le(out, 48, 2))
        assertEquals(258, le(out, 50, 2))
    }

    @Test
    fun `eviction drops the oldest clips past the file cap`() {
        val dir = temp.newFolder("captures")
        for (i in 1..5) {
            File(dir, "wake-0000000000000000$i-rejected.wav").writeBytes(ByteArray(10))
            File(dir, "wake-0000000000000000$i-rejected.txt").writeText("clip $i")
        }

        evictOldest(dir, maxFiles = 3, maxBytes = Long.MAX_VALUE)

        val remaining = dir.listFiles { f -> f.extension == "wav" }!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "wake-00000000000000003-rejected.wav",
                "wake-00000000000000004-rejected.wav",
                "wake-00000000000000005-rejected.wav",
            ),
            remaining,
        )
        assertEquals(0, dir.listFiles { f -> f.name.startsWith("wake-00000000000000001") }!!.size)
    }

    @Test
    fun `eviction drops the oldest clips past the byte cap`() {
        val dir = temp.newFolder("captures")
        for (i in 1..4) {
            File(dir, "wake-0000000000000000$i-silent.wav").writeBytes(ByteArray(100))
            File(dir, "wake-0000000000000000$i-silent.txt").writeText("")
        }

        evictOldest(dir, maxFiles = Int.MAX_VALUE, maxBytes = 250)

        val remaining = dir.listFiles { f -> f.extension == "wav" }!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "wake-00000000000000003-silent.wav",
                "wake-00000000000000004-silent.wav",
            ),
            remaining,
        )
    }

    @Test
    fun `eviction leaves an under-cap directory alone`() {
        val dir = temp.newFolder("captures")
        File(dir, "wake-00000000000000001-rejected.wav").writeBytes(ByteArray(10))
        File(dir, "wake-00000000000000001-rejected.txt").writeText("only one")

        evictOldest(dir, maxFiles = 50, maxBytes = 20L * 1024 * 1024)

        assertEquals(1, dir.listFiles { f -> f.extension == "wav" }!!.size)
        assertEquals("only one", File(dir, "wake-00000000000000001-rejected.txt").readText())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.wakeword.WakeCaptureStoreTest"
```

Expected: FAIL — compilation error, `Unresolved reference: wavBytes`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt`:

```kotlin
package dev.heyari.ari.wakeword

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Which containment path caught the clip. Recorded in the sidecar. */
enum class WakeCaptureHook(val slug: String) {
    /** The transcript contained speech but no wake-phrase name token. */
    REJECTED("rejected"),

    /** The wake fired and nobody said anything before the silence timeout. */
    SILENT("silent"),
}

data class WakeCaptureStats(val count: Int, val totalBytes: Long)

private const val SAMPLE_RATE = 16000
private const val CHANNELS = 1
private const val BYTES_PER_SAMPLE = 2

/**
 * Encode [pcm] as a 16-bit mono 16 kHz WAV. That is the format microWakeWord
 * training consumes, so captured clips drop straight into a hard-negative set
 * with no conversion step.
 */
internal fun wavBytes(pcm: ShortArray): ByteArray {
    val dataBytes = pcm.size * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray(Charsets.US_ASCII))
    buf.putInt(36 + dataBytes)
    buf.put("WAVE".toByteArray(Charsets.US_ASCII))
    buf.put("fmt ".toByteArray(Charsets.US_ASCII))
    buf.putInt(16)
    buf.putShort(1)
    buf.putShort(CHANNELS.toShort())
    buf.putInt(SAMPLE_RATE)
    buf.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
    buf.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
    buf.putShort((BYTES_PER_SAMPLE * 8).toShort())
    buf.put("data".toByteArray(Charsets.US_ASCII))
    buf.putInt(dataBytes)
    for (sample in pcm) buf.putShort(sample)
    return buf.array()
}

/**
 * Delete the oldest clip/sidecar pairs until [dir] is within both caps.
 * Filenames are timestamp-prefixed and fixed-width, so lexicographic order is
 * chronological order.
 */
internal fun evictOldest(dir: File, maxFiles: Int, maxBytes: Long) {
    val clips = dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: return
    var count = clips.size
    var bytes = clips.sumOf { it.length() }
    for (clip in clips) {
        if (count <= maxFiles && bytes <= maxBytes) return
        bytes -= clip.length()
        count--
        clip.delete()
        File(dir, "${clip.nameWithoutExtension}.txt").delete()
    }
}

/**
 * Persists audio that falsely triggered the wake word, for a future retrain of
 * `hey_ari.tflite` with real hard negatives.
 *
 * App-private storage only, hard-bounded, and gated behind a debug setting that
 * is off by default — the caller checks the setting, this class does not. See
 * `docs/superpowers/specs/2026-07-27-wake-word-false-accept-design.md` §5.
 */
@Singleton
class WakeCaptureStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val dir: File get() = File(context.filesDir, DIR_NAME)

    fun save(
        pcm: ShortArray,
        rawTranscript: String,
        hook: WakeCaptureHook,
        timestampMs: Long,
    ) {
        if (pcm.isEmpty()) return
        val target = dir
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "Could not create capture directory ${target.path}")
            return
        }
        val stem = "wake-%017d-%s".format(timestampMs, hook.slug)
        File(target, "$stem.wav").writeBytes(wavBytes(pcm))
        File(target, "$stem.txt").writeText(rawTranscript)
        evictOldest(target, MAX_FILES, MAX_BYTES)
        Log.i(TAG, "Captured false trigger: $stem.wav (${pcm.size} samples)")
    }

    fun stats(): WakeCaptureStats {
        val clips = dir.listFiles { f -> f.extension == "wav" } ?: return WakeCaptureStats(0, 0L)
        return WakeCaptureStats(clips.size, clips.sumOf { it.length() })
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "WakeCaptureStore"
        const val DIR_NAME = "wake-captures"
        const val MAX_FILES = 50
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
```

Note the audio constants are file-level, not in the companion: `wavBytes` is a top-level function and cannot see a private companion.

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.wakeword.WakeCaptureStoreTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt \
        app/src/test/java/dev/heyari/ari/wakeword/WakeCaptureStoreTest.kt
git commit -m "feat(wakeword): add a bounded false-trigger audio capture store"
```

---

### Task 6: The debug setting and its UI

Off by default, clearly labelled, with live count/size and an explicit delete. Nothing is captured until this is on.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/data/SettingsRepository.kt` (add near `startOnBoot` at line 84; key near line 318)
- Modify: `app/src/main/java/dev/heyari/ari/ui/settings/SettingsViewModel.kt` (state at ~line 114, collector at ~line 280, setter at ~line 383)
- Modify: `app/src/main/java/dev/heyari/ari/ui/settings/pages/SettingsSections.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/settings/pages/WakeWordSettingsPage.kt:39-46`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

**Interfaces:**
- Consumes: `WakeCaptureStore`, `WakeCaptureStats` (Task 5).
- Produces: `SettingsRepository.keepFalseTriggerAudio: Flow<Boolean>` and `suspend fun setKeepFalseTriggerAudio(enabled: Boolean)`; `SettingsViewModel.setKeepFalseTriggerAudio(Boolean)` and `clearWakeCaptures()`; state fields `keepFalseTriggerAudio: Boolean` and `wakeCaptureStats: WakeCaptureStats`.

- [ ] **Step 1: Add the DataStore flag**

In `app/src/main/java/dev/heyari/ari/data/SettingsRepository.kt`, add after the `startOnBoot` pair (line 88), matching that exact shape:

```kotlin
    /**
     * Whether to keep audio that falsely triggered the wake word, for a future
     * model retrain. Default off — this writes microphone audio to app-private
     * storage and must never be on unless the user asked for it.
     */
    val keepFalseTriggerAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_FALSE_TRIGGER_AUDIO] ?: false
    }

    suspend fun setKeepFalseTriggerAudio(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_FALSE_TRIGGER_AUDIO] = enabled
        }
    }
```

And the key, next to `KEY_START_ON_BOOT` (line 318):

```kotlin
        private val KEY_KEEP_FALSE_TRIGGER_AUDIO =
            booleanPreferencesKey("keep_false_trigger_audio")
```

- [ ] **Step 2: Add the EN strings**

In `app/src/main/res/values/strings.xml`, add next to the existing `wakeword_sensitivity_*` block (after line 288):

```xml
    <string name="settings_wake_capture_title">Keep false-trigger audio</string>
    <string name="settings_wake_capture_blurb">When Ari wakes for something you didn\'t say, keep the recording so the wake word can be improved. Audio stays on this device in Ari\'s private storage, is never uploaded, and is capped at 50 clips. Off by default.</string>
    <string name="settings_wake_capture_stats">%1$d clips · %2$s</string>
    <string name="settings_wake_capture_empty">No clips saved yet.</string>
    <string name="settings_wake_capture_delete">Delete all clips</string>
```

- [ ] **Step 3: Get the Italian strings from Keith — do not generate them**

`values/strings.xml` and `values-it/strings.xml` are at exact parity (347 each) and a post-push CI check enforces it. Per `antislop.md` #2, **never machine-generate translations.**

**Stop here and ask Keith for the Italian for the five keys above.** Add them to `app/src/main/res/values-it/strings.xml` in the same position, in the same commit as the EN keys, then continue.

- [ ] **Step 4: Expose it in the ViewModel**

In `app/src/main/java/dev/heyari/ari/ui/settings/SettingsViewModel.kt`:

Add to the state data class, next to `startOnBoot` (line 114):

```kotlin
    val keepFalseTriggerAudio: Boolean = false,
    val wakeCaptureStats: WakeCaptureStats = WakeCaptureStats(0, 0L),
```

Inject the store by adding `private val wakeCaptureStore: WakeCaptureStore,` to the constructor parameter list (after `speechOutput`, keeping the existing one-per-line style).

Add a collector immediately after the `startOnBoot` block (lines 279-283), matching its exact shape:

```kotlin
        viewModelScope.launch {
            settingsRepository.keepFalseTriggerAudio.collect { enabled ->
                _state.update {
                    it.copy(
                        keepFalseTriggerAudio = enabled,
                        wakeCaptureStats = wakeCaptureStore.stats(),
                    )
                }
            }
        }
```

Add setters next to `setStartOnBoot` (line 383):

```kotlin
    fun setKeepFalseTriggerAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepFalseTriggerAudio(enabled)
        }
    }

    fun clearWakeCaptures() {
        wakeCaptureStore.clear()
        _state.update { it.copy(wakeCaptureStats = wakeCaptureStore.stats()) }
    }
```

Add imports for `dev.heyari.ari.wakeword.WakeCaptureStore` and `dev.heyari.ari.wakeword.WakeCaptureStats`.

- [ ] **Step 5: Add the UI section**

In `app/src/main/java/dev/heyari/ari/ui/settings/pages/SettingsSections.kt`, add a new composable. Read the neighbouring `WakeWordSensitivitySection` first and match its card/heading/spacing idiom exactly rather than inventing a layout:

```kotlin
@Composable
internal fun WakeCaptureSection(
    enabled: Boolean,
    stats: WakeCaptureStats,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_wake_capture_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = stringResource(R.string.settings_wake_capture_blurb),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = if (stats.count == 0) {
                stringResource(R.string.settings_wake_capture_empty)
            } else {
                stringResource(
                    R.string.settings_wake_capture_stats,
                    stats.count,
                    android.text.format.Formatter.formatShortFileSize(
                        androidx.compose.ui.platform.LocalContext.current,
                        stats.totalBytes,
                    ),
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (stats.count > 0) {
            OutlinedButton(onClick = onClear) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_wake_capture_delete))
            }
        }
    }
}
```

Add `import dev.heyari.ari.wakeword.WakeCaptureStats`. `Icons.Filled.Delete`, `OutlinedButton`, `Switch`, `Spacer` and `Modifier.width` are all already imported in this file.

Then render it in `app/src/main/java/dev/heyari/ari/ui/settings/pages/WakeWordSettingsPage.kt`, after `WakeWordSensitivitySection` (line 45):

```kotlin
            WakeCaptureSection(
                enabled = state.keepFalseTriggerAudio,
                stats = state.wakeCaptureStats,
                onToggle = viewModel::setKeepFalseTriggerAudio,
                onClear = viewModel::clearWakeCaptures,
            )
```

- [ ] **Step 6: Build and check parity**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:assembleDebug
grep -c "<string" app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
```

Expected: build succeeds, and both files report **352**.

- [ ] **Step 7: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
```

Open Settings → Wake word. Confirm the new section is present, the switch is **off**, and it reads "No clips saved yet." Toggle it on, background and reopen the app, confirm it stayed on. Toggle it back off.

- [ ] **Step 8: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/data/SettingsRepository.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/SettingsViewModel.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/pages/SettingsSections.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/pages/WakeWordSettingsPage.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-it/strings.xml
git commit -m "feat(settings): add the false-trigger audio capture debug setting"
```

---

### Task 7: Capture hook 1 — verifier rejections

The full utterance (pre-roll plus live audio) is already assembled as `mergedAudio` for the offline-retry path and handed to the host on `SttState.Done.audio`. On a rejection we persist it instead of discarding it.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt` (constructor, and the gate added in Task 3)

**Interfaces:**
- Consumes: `WakeCaptureStore.save(...)`, `WakeCaptureHook.REJECTED` (Task 5); `SettingsRepository.keepFalseTriggerAudio` (Task 6); the rejection branch (Task 3).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Inject the store**

In `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`, add to the constructor parameter list next to `settingsRepository`:

```kotlin
    private val wakeCaptureStore: dev.heyari.ari.wakeword.WakeCaptureStore,
```

Match the surrounding style — this file already uses fully-qualified types in the constructor (e.g. `dev.heyari.ari.actions.CardActionVoiceIntercept`), so do the same rather than adding an import.

- [ ] **Step 2: Persist on rejection**

Replace the rejection branch added in Task 3 with:

```kotlin
                                if (!shouldAcceptWake(verifyWake, sttState.raw, sttState.nameMatched)) {
                                    Log.w(TAG, "Wake rejected: raw='${sttState.raw}'")
                                    captureFalseTrigger(
                                        sttState.audio,
                                        sttState.raw.orEmpty(),
                                        dev.heyari.ari.wakeword.WakeCaptureHook.REJECTED,
                                    )
                                    dismiss()
                                    return@collect
                                }
                                verifyWake = false
```

- [ ] **Step 3: Add the capture helper**

Add a private method to `VoiceSession`, next to `dismiss()`:

```kotlin
    /**
     * Persist audio that falsely triggered the wake word, if the user opted in.
     * Read the flag at call time rather than caching it — the setting is rare,
     * this path is rarer, and a stale cached value would silently capture (or
     * silently not) after a toggle.
     */
    private fun captureFalseTrigger(
        pcm: ShortArray?,
        rawTranscript: String,
        hook: dev.heyari.ari.wakeword.WakeCaptureHook,
    ) {
        if (pcm == null || pcm.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            if (!settingsRepository.keepFalseTriggerAudio.first()) return@launch
            wakeCaptureStore.save(pcm, rawTranscript, hook, System.currentTimeMillis())
        }
    }
```

Add imports for `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.flow.first` if they are not already present in the file.

- [ ] **Step 4: Build and run the unit suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
```

Turn the setting **on** in Settings → Wake word. Trigger the wake word, then say:

```
hey there mate what time is it
```

Then:

```bash
adb -s emulator-5554 shell run-as dev.heyari.ari ls -l files/wake-captures
```

Expect one `wake-<timestamp>-rejected.wav` and its `.txt` sidecar containing the raw transcript. Confirm the Settings page now shows `1 clip`.

Then turn the setting **off** and check both halves of the retention contract:

- Repeat the false trigger — no new file appears.
- Re-list the directory — **the existing clip is still there.** Turning the setting off stops capture; it must never delete what the user already has. Deleting is the explicit button's job.

- [ ] **Step 6: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat(wakeword): capture audio from rejected wake turns"
```

---

### Task 8: Capture hook 2 — silent false fires

A wake that fires with nobody speaking never produces an `SttState.Done`, so hook 1 never sees it — and these are the *best* hard negatives, because whatever set the model off is uncontaminated by a following command. This snapshots the `CaptureBus` ring at session start and persists it only if the turn dies unverified.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/audio/CaptureBus.kt`
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`

**Interfaces:**
- Consumes: `AudioRingBuffer.snapshot` / `samplesWritten` (existing); `captureFalseTrigger` (Task 7); `WakeCaptureHook.SILENT` (Task 5).
- Produces: `CaptureBus.peekRecent(seconds: Float): ShortArray`.

- [ ] **Step 1: Add a non-arming peek to the CaptureBus**

In `app/src/main/java/dev/heyari/ari/audio/CaptureBus.kt`, add after `arm()`:

```kotlin
    /**
     * Snapshot the last [seconds] of audio **without** arming. Used to keep the
     * pre-detection audio around while a wake turn plays out, so a wake that
     * nobody answers can be persisted as a hard negative for model retraining.
     * Does not disturb the live channel or [armed].
     */
    fun peekRecent(seconds: Float): ShortArray {
        val head = ringBuffer.samplesWritten
        val from = (head - (SAMPLE_RATE * seconds).toLong()).coerceAtLeast(0L)
        return ringBuffer.snapshot(from, head)
    }
```

Reuses the existing ring API rather than adding a parallel buffer (`antislop.md` #1).

- [ ] **Step 2: Snapshot at the start of a wake turn**

In `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`, add the `CaptureBus` to the constructor parameter list:

```kotlin
    private val captureBus: dev.heyari.ari.audio.CaptureBus,
```

Add a field next to `verifyWake`:

```kotlin
    // Pre-detection audio held for the duration of an unverified wake turn.
    // Dropped the moment the turn is accepted; persisted if it dies silently.
    @Volatile
    private var wakePreroll: ShortArray? = null
```

In `start(verifyWake: Boolean)`, immediately after `this.verifyWake = verifyWake`:

```kotlin
        // Snapshot now rather than at detection time: the overlay launch costs
        // a few hundred ms, but the ring holds 2 s and "Hey Ari" is ~0.7 s, so
        // the phrase is still comfortably inside the window.
        wakePreroll = if (verifyWake) captureBus.peekRecent(PREROLL_CAPTURE_SECONDS) else null
```

Add the constant to the companion object next to `WAKE_TURN_SILENCE_TIMEOUT_MS`:

```kotlin
        private const val PREROLL_CAPTURE_SECONDS = 2.0f
```

- [ ] **Step 3: Drop it on acceptance**

In the `SttState.Done` branch, extend the accept path added in Task 3:

```kotlin
                                verifyWake = false
                                wakePreroll = null
```

- [ ] **Step 4: Persist it when a wake turn dies silently**

In the `launchSilenceWatcher` local function (modified in Task 4), replace the timeout body:

```kotlin
                        if (idle > timeoutMs) {
                            Log.i(TAG, "No speech detected within $timeoutMs ms — dismissing")
                            dismiss()
                            return@launch
                        }
```

with:

```kotlin
                        if (idle > timeoutMs) {
                            Log.i(TAG, "No speech detected within $timeoutMs ms — dismissing")
                            if (verifyWake) {
                                captureFalseTrigger(
                                    wakePreroll,
                                    "",
                                    dev.heyari.ari.wakeword.WakeCaptureHook.SILENT,
                                )
                            }
                            dismiss()
                            return@launch
                        }
```

`verifyWake` is still true here precisely when no final transcript was ever accepted — a wake nobody answered.

Finally, add `wakePreroll = null` inside `dismiss()`, next to the `verifyWake = false` added in Task 3, so the buffer is never held across sessions.

- [ ] **Step 5: Build and run the unit suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
adb -s emulator-5554 shell run-as dev.heyari.ari rm -rf files/wake-captures
```

With the setting **on**, say just the wake phrase and then stay silent for ten seconds:

```
Hey Ari
```

Then:

```bash
adb -s emulator-5554 shell run-as dev.heyari.ari ls -l files/wake-captures
```

Expect one `wake-<timestamp>-silent.wav` of roughly 64 KB (2 s at 16 kHz, 16-bit mono) with an empty `.txt` sidecar. Pull it and confirm it plays back as recognisable audio:

```bash
adb -s emulator-5554 exec-out run-as dev.heyari.ari cat files/wake-captures/<name>.wav > /tmp/claude-1000/-home-keith-LocalCode-keithvassallomt-Ari/22ca216a-e0c2-43b6-aacd-5ca183126f70/scratchpad/silent.wav
```

Then confirm a **genuine** wake produces **no** silent clip: say "Hey Ari, what's the weather", get an answer, and check no new `-silent.wav` appeared.

- [ ] **Step 7: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/java/dev/heyari/ari/audio/CaptureBus.kt \
        app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat(wakeword): capture audio from wakes nobody answered"
```

---

### Task 9: Share-sheet export

Without this, captured clips are stranded — app-private storage is not `adb pull`-able on a release build, and the whole point is getting them into a training set. The app has **no `FileProvider` today**, so this task adds one.

**Files:**
- Create: `app/src/main/res/xml/wake_capture_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` (inside `<application>`, near the existing `androidx.startup.InitializationProvider` at line 332)
- Modify: `app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/settings/pages/SettingsSections.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/settings/pages/WakeWordSettingsPage.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`

**Interfaces:**
- Consumes: `WakeCaptureStore.dir` / `.stats()` (Task 5); `WakeCaptureSection` (Task 6).
- Produces: `WakeCaptureStore.shareIntent(): Intent?`.

- [ ] **Step 1: Declare the FileProvider**

Create `app/src/main/res/xml/wake_capture_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="wake-captures" path="wake-captures/" />
</paths>
```

In `app/src/main/AndroidManifest.xml`, inside `<application>`, next to the existing provider block:

```xml
        <!--
          Grants read access to captured false-trigger clips for the share
          sheet only. Scoped to the wake-captures directory: nothing else in
          filesDir is exposed. Not exported; access is per-URI via the grant
          flag on the share intent.
        -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.wakecaptures"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/wake_capture_paths" />
        </provider>
```

- [ ] **Step 2: Build the share intent**

In `app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt`, add:

```kotlin
    /**
     * An `ACTION_SEND_MULTIPLE` intent carrying every captured clip and its
     * sidecar, or null when there is nothing to share. The caller adds
     * `FLAG_ACTIVITY_NEW_TASK` if launching from a non-activity context.
     */
    fun shareIntent(): Intent? {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val uris = ArrayList(
            files.map { FileProvider.getUriForFile(context, "${context.packageName}.wakecaptures", it) }
        )
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
```

Add imports for `android.content.Intent` and `androidx.core.content.FileProvider`.

- [ ] **Step 3: Add the strings**

In `app/src/main/res/values/strings.xml`, next to the other `settings_wake_capture_*` keys:

```xml
    <string name="settings_wake_capture_export">Export clips</string>
    <string name="settings_wake_capture_export_chooser">Export wake-word clips</string>
```

**Then stop and ask Keith for the Italian**, exactly as in Task 6 Step 3. Do not machine-generate it. Add both to `values-it/strings.xml` in the same commit.

- [ ] **Step 4: Wire the button**

In `SettingsSections.kt`, add an `onExport: () -> Unit` parameter to `WakeCaptureSection` and a second button inside the existing `if (stats.count > 0)` block, above the delete button:

```kotlin
            OutlinedButton(onClick = onExport) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_wake_capture_export))
            }
```

Add `import androidx.compose.material.icons.filled.Share`.

In `WakeWordSettingsPage.kt`, wire it through. The chooser needs an `Activity` context, so resolve it in the page rather than the ViewModel:

```kotlin
    val context = LocalContext.current
```

```kotlin
                onExport = {
                    viewModel.wakeCaptureShareIntent()?.let { intent ->
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                context.getString(R.string.settings_wake_capture_export_chooser),
                            )
                        )
                    }
                },
```

Add imports for `android.content.Intent` and `androidx.compose.ui.platform.LocalContext`. Add the pass-through to `SettingsViewModel` next to `clearWakeCaptures`:

```kotlin
    fun wakeCaptureShareIntent(): Intent? = wakeCaptureStore.shareIntent()
```

- [ ] **Step 5: Build and check parity**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:assembleDebug
grep -c "<string" app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
```

Expected: build succeeds, both report **354**.

- [ ] **Step 6: Verify on the emulator**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
```

With at least one captured clip present, open Settings → Wake word and tap **Export clips**. Expect a share sheet listing the WAV and TXT files. Share to Files/Drive and confirm the WAV opens and plays. Confirm the button is hidden when the count is zero.

- [ ] **Step 7: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # must print: main
git add app/src/main/AndroidManifest.xml \
        app/src/main/res/xml/wake_capture_paths.xml \
        app/src/main/java/dev/heyari/ari/wakeword/WakeCaptureStore.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/SettingsViewModel.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/pages/SettingsSections.kt \
        app/src/main/java/dev/heyari/ari/ui/settings/pages/WakeWordSettingsPage.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-it/strings.xml
git commit -m "feat(wakeword): export captured false-trigger clips via the share sheet"
```

---

## Natural split point

Tasks 1-4 deliver the safety property on their own — false accepts stop reaching the engine and the blast radius shrinks. Tasks 5-9 deliver data collection for the retrain follow-up. If the work needs to be paused, pause after Task 4; the app is in a coherent, shippable state there.

## Not in this plan

Per the spec's "Out of scope": retraining `hey_ari.tflite` (that is `2026-07-27-wake-word-model-retrain-design.md`), phonetic matching, holding the ready cue until verification completes, and the `WakeWordRegistry` per-model operating-point coupling (a prerequisite of the retrain, not of containment).

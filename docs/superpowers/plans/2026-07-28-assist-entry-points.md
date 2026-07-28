# Assist Entry Points Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the headset voice button, `ACTION_ASSIST`, and the assist gesture all start a voice turn, behind one shared launcher.

**Architecture:** All four summon paths call a single `VoiceTurnLauncher`, which fires the same `WakeWordService` intent tap-to-talk already fires. The two load-bearing decisions are extracted as framework-free functions and tested directly. A no-UI trampoline activity carries the new intent filters and ships disabled, enabled at runtime only while Ari holds the assistant role.

**Tech Stack:** Kotlin, Hilt, Android `RoleManager` / `PackageManager` component enabling, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-28-assist-entry-points-design.md`

## Global Constraints

- **No new dependencies.** The project has plain JUnit 4 and no mocking library — no MockK, no Robolectric. This is deliberate: `VoiceSessionTest` documents the house pattern of extracting the decision into a framework-free function and testing that. Do not add a test dependency.
- **minSdk is 29.** `RoleManager` and `<profileable>` both require 29; no `Build.VERSION` guards are needed for either.
- **Every new EN string needs its IT counterpart in the same commit.** The translation-parity check runs post-push, not as a merge gate, so a missing key fails after the fact. The Italian below is approved copy — use it verbatim.
- **Tests assert exact values.** No `assertTrue(result != null)` — assert the exact type or the exact boolean.
- **Comments explain why, never what.** Match the density of the file you are editing.
- **Commit after each task.** Direct to `main`; this repo does not require PRs.

---

### Task 1: Pure decision logic

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/voice/VoiceTurnDecision.kt`
- Test: `app/src/test/java/dev/heyari/ari/voice/VoiceTurnDecisionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed interface TurnLaunch` with `Started`/`AlreadyActive`/`NoMicPermission`/`SttNotReady`; `enum class TurnSource { HEADLESS, IN_APP }`; `internal fun decideGate(turnActive: Boolean, oneShotActive: Boolean, micGranted: Boolean, sttReady: Boolean): TurnLaunch`; `internal fun computeOneShot(isRunning: Boolean, oneShotActive: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/voice/VoiceTurnDecisionTest.kt`:

```kotlin
package dev.heyari.ari.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two decisions behind every way of summoning Ari. [VoiceTurnLauncher]
 * itself reads Android state and starts a service, so per the pattern set by
 * [VoiceSessionTest] the load-bearing logic lives here and is tested directly.
 */
class VoiceTurnDecisionTest {

    @Test
    fun `an active turn blocks everything`() {
        assertEquals(
            TurnLaunch.AlreadyActive,
            decideGate(turnActive = true, oneShotActive = false, micGranted = true, sttReady = true),
        )
    }

    @Test
    fun `a live one-shot host blocks everything`() {
        assertEquals(
            TurnLaunch.AlreadyActive,
            decideGate(turnActive = false, oneShotActive = true, micGranted = true, sttReady = true),
        )
    }

    @Test
    fun `busy wins over missing permission`() {
        assertEquals(
            TurnLaunch.AlreadyActive,
            decideGate(turnActive = true, oneShotActive = false, micGranted = false, sttReady = false),
        )
    }

    @Test
    fun `missing permission wins over an unready model`() {
        assertEquals(
            TurnLaunch.NoMicPermission,
            decideGate(turnActive = false, oneShotActive = false, micGranted = false, sttReady = false),
        )
    }

    @Test
    fun `an unready model is reported when permission is granted`() {
        assertEquals(
            TurnLaunch.SttNotReady,
            decideGate(turnActive = false, oneShotActive = false, micGranted = true, sttReady = false),
        )
    }

    @Test
    fun `everything ready starts a turn`() {
        assertEquals(
            TurnLaunch.Started,
            decideGate(turnActive = false, oneShotActive = false, micGranted = true, sttReady = true),
        )
    }

    @Test
    fun `service not running means the host is transient`() {
        assertEquals(true, computeOneShot(isRunning = false, oneShotActive = false))
    }

    @Test
    fun `service already running means the host persists`() {
        assertEquals(false, computeOneShot(isRunning = true, oneShotActive = false))
    }

    @Test
    fun `a one-shot host that came up mid-decision stays one-shot`() {
        assertEquals(true, computeOneShot(isRunning = true, oneShotActive = true))
    }

    @Test
    fun `not running and one-shot is still one-shot`() {
        assertEquals(true, computeOneShot(isRunning = false, oneShotActive = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest --tests '*VoiceTurnDecisionTest*'`
Expected: FAIL — compilation error, `decideGate`/`computeOneShot`/`TurnLaunch` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/heyari/ari/voice/VoiceTurnDecision.kt`:

```kotlin
package dev.heyari.ari.voice

/** Outcome of asking [VoiceTurnLauncher] to start a turn. */
sealed interface TurnLaunch {
    data object Started : TurnLaunch
    data object AlreadyActive : TurnLaunch
    data object NoMicPermission : TurnLaunch
    data object SttNotReady : TurnLaunch
}

/**
 * Where the request came from, which decides whether a failure is spoken.
 * [HEADLESS] callers (headset button, assist gesture) have no screen in front
 * of the user; [IN_APP] callers already show a permission dialog and would be
 * worse for talking over it.
 */
enum class TurnSource { HEADLESS, IN_APP }

internal fun decideGate(
    turnActive: Boolean,
    oneShotActive: Boolean,
    micGranted: Boolean,
    sttReady: Boolean,
): TurnLaunch = when {
    turnActive || oneShotActive -> TurnLaunch.AlreadyActive
    !micGranted -> TurnLaunch.NoMicPermission
    !sttReady -> TurnLaunch.SttNotReady
    else -> TurnLaunch.Started
}

/**
 * Kept separate from [decideGate] so the caller re-reads `oneShotActive` at the
 * moment of use. Both inputs are @Volatile statics, and a transient host coming
 * up between the two reads is exactly what the sticky term closes: a single
 * snapshot would send EXTRA_ONE_SHOT=false and strand a hot mic.
 */
internal fun computeOneShot(isRunning: Boolean, oneShotActive: Boolean): Boolean =
    !isRunning || oneShotActive
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest --tests '*VoiceTurnDecisionTest*'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/voice/VoiceTurnDecision.kt \
        app/src/test/java/dev/heyari/ari/voice/VoiceTurnDecisionTest.kt
git commit -m "feat(voice): extract the turn-launch decisions as pure functions"
```

---

### Task 2: VoiceTurnLauncher, strings, and the composer mic

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/voice/VoiceTurnLauncher.kt`
- Modify: `app/src/main/res/values/strings.xml` (after `settings_utterance_capture_export_chooser`, line 301)
- Modify: `app/src/main/res/values-it/strings.xml` (after `settings_utterance_capture_export_chooser`, line 302)
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` (`startVoiceTurn`, `startDictation`)

**Interfaces:**
- Consumes: `TurnLaunch`, `TurnSource`, `decideGate`, `computeOneShot` from Task 1.
- Produces: `@Singleton class VoiceTurnLauncher` with `fun launch(source: TurnSource): TurnLaunch` and `fun oneShotForDictation(): Boolean`.

- [ ] **Step 1: Add the English strings**

In `app/src/main/res/values/strings.xml`, immediately after the `settings_utterance_capture_export_chooser` line:

```xml
    <string name="assist_needs_mic_permission">I need microphone permission first. Open Ari to grant it.</string>
    <string name="assist_stt_not_ready">My speech recognition isn\'t ready yet. Open Ari to finish setting up.</string>
```

- [ ] **Step 2: Add the Italian strings**

In `app/src/main/res/values-it/strings.xml`, immediately after the `settings_utterance_capture_export_chooser` line:

```xml
    <string name="assist_needs_mic_permission">Prima ho bisogno del permesso per il microfono. Apri Ari per concederlo.</string>
    <string name="assist_stt_not_ready">Il riconoscimento vocale non è ancora pronto. Apri Ari per completare la configurazione.</string>
```

- [ ] **Step 3: Write the launcher**

Create `app/src/main/java/dev/heyari/ari/voice/VoiceTurnLauncher.kt`:

```kotlin
package dev.heyari.ari.voice

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.heyari.ari.R
import dev.heyari.ari.stt.SpeechRecognizer
import dev.heyari.ari.tts.SpeechOutput
import dev.heyari.ari.wakeword.WakeWordService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single way to start a voice turn. Every summon path — headset button,
 * assist gesture, ACTION_ASSIST, composer mic — comes through here, so the
 * preconditions and the one-shot rule cannot drift between them.
 */
@Singleton
class VoiceTurnLauncher @Inject constructor(
    private val application: Application,
    private val voiceSession: VoiceSession,
    private val speechRecognizer: SpeechRecognizer,
    private val speechOutput: SpeechOutput,
) {

    fun launch(source: TurnSource): TurnLaunch {
        val decision = decideGate(
            turnActive = voiceSession.isActive,
            oneShotActive = WakeWordService.oneShotActive,
            micGranted = micGranted(),
            sttReady = speechRecognizer.isModelLoaded,
        )
        Log.i(TAG, "launch(source=$source) -> $decision")

        if (decision is TurnLaunch.Started) {
            val intent = Intent(application, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START_VOICE_TURN
                putExtra(WakeWordService.EXTRA_ONE_SHOT, currentOneShot())
            }
            ContextCompat.startForegroundService(application, intent)
            return decision
        }

        if (source == TurnSource.HEADLESS) speakFailure(decision)
        return decision
    }

    /**
     * Dictation fires ACTION_START_DICTATION rather than ACTION_START_VOICE_TURN,
     * so it cannot route through [launch] — but the one-shot rule must stay
     * identical, hence sharing this rather than a third copy of the expression.
     */
    fun oneShotForDictation(): Boolean = currentOneShot()

    private fun currentOneShot(): Boolean = computeOneShot(
        isRunning = WakeWordService.isRunning,
        oneShotActive = WakeWordService.oneShotActive,
    )

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun speakFailure(decision: TurnLaunch) {
        val message = when (decision) {
            TurnLaunch.NoMicPermission -> R.string.assist_needs_mic_permission
            TurnLaunch.SttNotReady -> R.string.assist_stt_not_ready
            // Busy is deliberately silent: the user is mid-turn and does not
            // need Ari talking over itself.
            TurnLaunch.AlreadyActive, TurnLaunch.Started -> return
        }
        speechOutput.speak(application.getString(message))
    }

    private companion object {
        const val TAG = "VoiceTurnLauncher"
    }
}
```

- [ ] **Step 4: Point the composer mic at the launcher**

In `ConversationViewModel.kt`, add `private val voiceTurnLauncher: VoiceTurnLauncher` to the constructor's injected parameters, then replace the body of `startVoiceTurn()` with:

```kotlin
    fun startVoiceTurn() {
        voiceTurnLauncher.launch(TurnSource.IN_APP)
    }
```

Delete the `if (voiceSession.isActive || WakeWordService.oneShotActive) return` guard and the local `oneShot`/`intent`/`startForegroundService` lines — the launcher owns all of it now. Keep the KDoc, trimmed to describe delegation rather than repeating the rules.

In `startDictation()`, replace only the one-shot line:

```kotlin
        val oneShot = voiceTurnLauncher.oneShotForDictation()
```

Leave the `isDictating` flag, the `isModelLoaded` check, the intent construction and the 4-second safety net exactly as they are.

Add the imports `dev.heyari.ari.voice.TurnSource` and `dev.heyari.ari.voice.VoiceTurnLauncher`, and remove any `WakeWordService` import that is now unused.

- [ ] **Step 5: Verify it compiles and tests still pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest`
Expected: PASS, no compilation errors. Existing test count plus the 10 from Task 1.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/voice/VoiceTurnLauncher.kt \
        app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-it/strings.xml
git commit -m "feat(voice): route turn starts through a single launcher"
```

---

### Task 3: Trampoline activity and the assist entry points

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/assistant/AssistTrampolineActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (remove the `ACTION_ASSIST` filter at lines 168-171; add the new activity next to `StartListeningActivity` at line 181)
- Modify: `app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionSession.kt`

**Interfaces:**
- Consumes: `VoiceTurnLauncher.launch`, `TurnSource.HEADLESS` from Task 2.
- Produces: `AssistTrampolineActivity` as a component name for Task 4 to enable and disable.

- [ ] **Step 1: Write the trampoline**

Create `app/src/main/java/dev/heyari/ari/assistant/AssistTrampolineActivity.kt`:

```kotlin
package dev.heyari.ari.assistant

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.voice.TurnSource
import dev.heyari.ari.voice.VoiceTurnLauncher
import javax.inject.Inject

/**
 * No-UI target for the headset voice button and ACTION_ASSIST. Android 14+
 * blocks mic-typed foreground services from starting in background contexts,
 * but an activity in onCreate is unambiguously foreground — the same reason
 * StartListeningActivity exists.
 *
 * Ships disabled. [AssistRoleSync] enables it only while Ari holds the
 * assistant role, so a second handler can never put a disambiguation chooser
 * between the user and their headset.
 */
@AndroidEntryPoint
class AssistTrampolineActivity : ComponentActivity() {

    @Inject
    lateinit var voiceTurnLauncher: VoiceTurnLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = voiceTurnLauncher.launch(TurnSource.HEADLESS)
        Log.i(TAG, "Assist trampoline fired: $result")
        finish()
        suppressCloseAnimation()
    }

    private fun suppressCloseAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        const val TAG = "AssistTrampoline"
    }
}
```

- [ ] **Step 2: Move ACTION_ASSIST off MainActivity**

In `app/src/main/AndroidManifest.xml`, delete this block from the `MainActivity` entry (lines 168-171):

```xml
            <intent-filter>
                <action android:name="android.intent.action.ASSIST" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
```

- [ ] **Step 3: Declare the trampoline**

In `app/src/main/AndroidManifest.xml`, immediately after the `StartListeningActivity` entry (line 181-185), add:

```xml
        <!--
          Disabled at install time and enabled by AssistRoleSync only while Ari
          holds the assistant role. ACTION_VOICE_COMMAND is a plain implicit
          intent, so advertising it unconditionally risks Android showing a
          chooser when the user presses a headset button with the phone in a
          pocket.
        -->
        <activity
            android:name=".assistant.AssistTrampolineActivity"
            android:enabled="false"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VOICE_COMMAND" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.ASSIST" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
```

- [ ] **Step 4: Point the assist gesture at the launcher**

Replace the whole body of `app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionSession.kt`:

```kotlin
package dev.heyari.ari.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.heyari.ari.voice.TurnSource
import dev.heyari.ari.voice.VoiceTurnLauncher

/**
 * Shown when the user invokes the assist gesture (long-press home, power button
 * on some devices) while Ari is the selected assistant. Starts a voice turn and
 * finishes — the same thing the headset button does, because the user asked for
 * an assistant either way.
 */
class AriVoiceInteractionSession(
    context: Context,
    private val voiceTurnLauncher: VoiceTurnLauncher,
) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        voiceTurnLauncher.launch(TurnSource.HEADLESS)
        finish()
    }
}
```

The `EXTRA_FROM_ASSIST` constant is deleted — nothing ever read it.

- [ ] **Step 5: Supply the launcher to the session**

`VoiceInteractionSession` is constructed by `AriVoiceInteractionSessionService`, not by Hilt. Replace the whole of `app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionSessionService.kt` with:

```kotlin
package dev.heyari.ari.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.heyari.ari.voice.VoiceTurnLauncher
import javax.inject.Inject

@AndroidEntryPoint
class AriVoiceInteractionSessionService : VoiceInteractionSessionService() {

    @Inject
    lateinit var voiceTurnLauncher: VoiceTurnLauncher

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AriVoiceInteractionSession(this, voiceTurnLauncher)
    }
}
```

This is the existing file verbatim plus the Hilt annotation, the injected field, and the extra constructor argument.

- [ ] **Step 6: Verify it compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/assistant/ app/src/main/AndroidManifest.xml
git commit -m "feat(assistant): start a voice turn from the headset button and assist gesture"
```

---

### Task 4: Role sync

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/assistant/AssistRoleSync.kt`
- Test: `app/src/test/java/dev/heyari/ari/assistant/AssistRoleSyncTest.kt`
- Modify: `app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionService.kt` (`onReady`)
- Modify: `app/src/main/java/dev/heyari/ari/MainActivity.kt` (`onResume`)

**Interfaces:**
- Consumes: `AssistTrampolineActivity` from Task 3.
- Produces: `internal fun componentStateFor(roleHeld: Boolean): Int`; `object AssistRoleSync` with `fun sync(context: Context)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/assistant/AssistRoleSyncTest.kt`:

```kotlin
package dev.heyari.ari.assistant

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistRoleSyncTest {

    @Test
    fun `holding the assistant role enables the trampoline`() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            componentStateFor(roleHeld = true),
        )
    }

    @Test
    fun `not holding the assistant role disables the trampoline`() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            componentStateFor(roleHeld = false),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest --tests '*AssistRoleSyncTest*'`
Expected: FAIL — `componentStateFor` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/heyari/ari/assistant/AssistRoleSync.kt`:

```kotlin
package dev.heyari.ari.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

internal fun componentStateFor(roleHeld: Boolean): Int =
    if (roleHeld) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

/**
 * Keeps [AssistTrampolineActivity] enabled exactly while Ari holds the
 * assistant role, so Ari never competes for the headset button it was not
 * chosen to answer.
 *
 * There is no broadcast for the role changing. The enable direction is driven
 * from AriVoiceInteractionService.onReady, which the system calls the moment it
 * binds Ari as assistant; the disable direction is caught on the next app
 * resume, which is the less urgent of the two.
 */
object AssistRoleSync {

    fun sync(context: Context) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        val target = componentStateFor(roleHeld)
        val component = ComponentName(context, AssistTrampolineActivity::class.java)
        val current = context.packageManager.getComponentEnabledSetting(component)
        if (current == target) return
        context.packageManager.setComponentEnabledSetting(
            component,
            target,
            PackageManager.DONT_KILL_APP,
        )
        Log.i(TAG, "Assistant role held=$roleHeld — trampoline ${if (roleHeld) "enabled" else "disabled"}")
    }

    private const val TAG = "AssistRoleSync"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest --tests '*AssistRoleSyncTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Call it when the role is granted**

In `app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionService.kt`, inside `onReady()` after the existing log line:

```kotlin
        AssistRoleSync.sync(this)
```

- [ ] **Step 6: Call it when the app resumes**

In `app/src/main/java/dev/heyari/ari/MainActivity.kt`, inside the existing `onResume()` after the `recordLaunch()` block:

```kotlin
        AssistRoleSync.sync(this)
```

Add `import dev.heyari.ari.assistant.AssistRoleSync`.

- [ ] **Step 7: Run the full test suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew testDebugUnitTest`
Expected: PASS, all suites.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/assistant/AssistRoleSync.kt \
        app/src/test/java/dev/heyari/ari/assistant/AssistRoleSyncTest.kt \
        app/src/main/java/dev/heyari/ari/assistant/AriVoiceInteractionService.kt \
        app/src/main/java/dev/heyari/ari/MainActivity.kt
git commit -m "feat(assistant): claim the headset button only while Ari is the assistant"
```

---

### Task 5: Device verification

**Files:** none — this task changes no code. Its deliverable is a verified feature and a recorded result.

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: a pass/fail record for each check below.

No unit test establishes whether a given phone routes its headset button to `ACTION_VOICE_COMMAND`; OEM behaviour varies. This has to be checked on hardware.

- [ ] **Step 1: Build and install a release build**

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew assembleRelease
SDK=$HOME/Android/Sdk/build-tools/37.0.0
"$SDK/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/ari-aligned.apk
"$SDK/apksigner" sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android --out /tmp/ari-release.apk /tmp/ari-aligned.apk
adb install -r /tmp/ari-release.apk
```

The debug keystore matches the installed signature, so this updates in place and preserves app data.

- [ ] **Step 2: Confirm the trampoline starts disabled**

Run: `adb shell dumpsys package dev.heyari.ari | grep -A2 AssistTrampolineActivity`
Expected: the component is listed as disabled, or absent from the enabled-components list, when Ari is not the assistant.

- [ ] **Step 3: Set Ari as the assistant and confirm it flips**

Set Ari as the default digital assistant in system settings, then re-run the command from Step 2.
Expected: enabled, without having had to open Ari — this is the `onReady()` hook doing its job.

- [ ] **Step 4: Press the headset voice button, phone unlocked**

Expected: a voice turn starts, same overlay as a wake word.
Record: pass/fail, and whether any chooser dialog appeared.

- [ ] **Step 5: Press the headset voice button, phone locked**

Expected: behaves as the wake word does over the lock screen.
Record: pass/fail.

- [ ] **Step 6: Revoke the assistant role and re-check**

Set a different assistant, reopen Ari once (to trigger the `onResume` path), then re-run the command from Step 2.
Expected: disabled. Pressing the headset button no longer reaches Ari.

- [ ] **Step 7: Long-press home with Ari as assistant**

Expected: starts listening rather than opening the chat UI. This is the behaviour change the spec calls out; confirm it feels right in practice.

- [ ] **Step 8: Record the results**

Append a short results block to the spec at `docs/superpowers/specs/2026-07-28-assist-entry-points-design.md` under a new `## Device verification` heading — which checks passed, which phone, and anything OEM-specific that surprised you.

```bash
git add docs/superpowers/specs/2026-07-28-assist-entry-points-design.md
git commit -m "docs(assistant): record device verification for the assist entry points"
```

---

## Self-review notes

**Spec coverage.** Decision 1 (all four entry points) → Tasks 2, 3. Decision 2 (spoken failures) → Task 2 Steps 1-3. Decision 3 (role-gated claiming) → Tasks 3, 4. `EXTRA_FROM_ASSIST` deletion → Task 3 Step 4. Behaviour change → verified in Task 5 Step 7. Strings incl. Italian → Task 2 Steps 1-2. Testing section → Tasks 1, 4.

**Known deviation from the spec.** The spec's Components section shows `AriVoiceInteractionSession(context)` with a single argument. Task 3 adds a second constructor parameter and makes `AriVoiceInteractionSessionService` a Hilt entry point, because the session is constructed by the framework rather than by Hilt. This was not spelled out in the spec and is the one place an implementer will have to reconcile the two documents.

**Type consistency.** `TurnLaunch`, `TurnSource`, `decideGate`, `computeOneShot`, `componentStateFor`, `VoiceTurnLauncher.launch`, `VoiceTurnLauncher.oneShotForDictation`, `AssistRoleSync.sync` — each is defined in exactly one task and referenced with the same name and signature everywhere else.

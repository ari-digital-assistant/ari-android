# Voice-in-Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make voice turns (recognised utterance + Ari's reply) appear in the chat window, so the chat is a faithful log of all conversation — typed or spoken.

**Architecture:** Hoist the message list out of the per-screen `ConversationViewModel` into an app-scoped singleton `ConversationLogRepository`. Both the text path (`ConversationViewModel`) and the voice path (`VoiceSession`, a `@Singleton` that runs with no ViewModel alive) become *writers* to the repo; the chat screen *observes* it. In-memory only.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, Kotlin coroutines/`StateFlow`, JUnit4. Design doc: `docs/superpowers/specs/2026-07-09-voice-in-chat-design.md`.

## Global Constraints

- **Component:** ari-android only. ari-linux is not implemented; do not touch it.
- **Persistence:** In-memory only. No Room/DataStore. The log clears on process death, exactly like today's text chat.
- **DI:** Hilt. A `@Singleton class X @Inject constructor()` is auto-provided — no module needed (unlike `CardStateRepository`, which needs a module only because it binds an interface).
- **Branch/PR:** ari-android changes go direct to `main` (PR is required only for `ari-skills/skills/` changes). Commit frequently.
- **Build/test JAVA_HOME:** This laptop's gradle needs `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`. Prefix gradle commands with it.
- **Unit-test command shape:** `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "<fqcn>"`
- **Device testing:** Use the emulator (`emulator-5554`), never Keith's physical Pixel. When asking Keith to retest, paste the literal test utterance in a code block.
- **Tests must be meaningful:** assert exact values and real behaviour. No size-only or threshold assertions.
- **`VoiceSession` is not unit-testable** without Robolectric (it drives MediaPlayer / SpeechRecognizer / a Main-dispatch coroutine). The existing `VoiceSessionTest` only tests extracted pure functions. Do NOT try to instantiate `VoiceSession` in a unit test — its logging is verified by emulator e2e.

---

## File Structure

- **Create:** `app/src/main/java/dev/heyari/ari/data/conversation/ConversationLogRepository.kt` — the singleton source of truth for the message list. One responsibility: hold + mutate the ordered `List<Message>`.
- **Create:** `app/src/test/java/dev/heyari/ari/data/conversation/ConversationLogRepositoryTest.kt` — unit tests for the repo.
- **Modify:** `app/src/main/java/dev/heyari/ari/model/ConversationState.kt` — remove the `messages` field.
- **Modify:** `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` — inject the repo; expose `messages`; replace all 12 message-append sites; drop `messages` from `_state`.
- **Modify:** `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt` — read messages from `viewModel.messages` instead of `state.messages` (6 references).
- **Modify:** `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt` — inject the repo; append user + assistant messages at the two emission points in `handleFinalText`; capture action attachments.

Task order: Task 1 (repo, TDD) → Task 2 (text path rewired, no behaviour change) → Task 3 (voice path writes to repo). Each task leaves the app compiling and working.

---

## Task 1: `ConversationLogRepository` + unit tests

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/data/conversation/ConversationLogRepository.kt`
- Test: `app/src/test/java/dev/heyari/ari/data/conversation/ConversationLogRepositoryTest.kt`

**Interfaces:**
- Consumes: `dev.heyari.ari.model.Message` (existing: `data class Message(id, text, isFromUser, timestamp, attachments)`).
- Produces:
  - `class ConversationLogRepository` — `@Singleton`, `@Inject constructor()`.
  - `val messages: StateFlow<List<Message>>` — observed by the screen (via the ViewModel).
  - `fun append(message: Message)` — atomic, thread-safe.
  - `fun clear()` — empties the log (used by tests; no UI wiring in this feature).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/dev/heyari/ari/data/conversation/ConversationLogRepositoryTest.kt`:

```kotlin
package dev.heyari.ari.data.conversation

import dev.heyari.ari.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLogRepositoryTest {

    @Test
    fun `append adds messages in insertion order`() {
        val repo = ConversationLogRepository()
        val user = Message(text = "what's the weather", isFromUser = true)
        val ari = Message(text = "Sunny, 24 degrees.", isFromUser = false)

        repo.append(user)
        repo.append(ari)

        assertEquals(listOf(user, ari), repo.messages.value)
    }

    @Test
    fun `messages starts empty`() {
        assertEquals(emptyList<Message>(), ConversationLogRepository().messages.value)
    }

    @Test
    fun `clear empties the log`() {
        val repo = ConversationLogRepository()
        repo.append(Message(text = "hello", isFromUser = true))

        repo.clear()

        assertEquals(emptyList<Message>(), repo.messages.value)
    }

    @Test
    fun `concurrent appends all land`() = runBlocking {
        val repo = ConversationLogRepository()

        val jobs = (0 until 100).map { i ->
            launch(Dispatchers.Default) {
                repo.append(Message(text = "m$i", isFromUser = i % 2 == 0))
            }
        }
        jobs.forEach { it.join() }

        assertEquals(100, repo.messages.value.size)
        assertEquals(
            (0 until 100).map { "m$it" }.toSet(),
            repo.messages.value.map { it.text }.toSet(),
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.data.conversation.ConversationLogRepositoryTest"`
Expected: FAIL — compilation error, `ConversationLogRepository` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/dev/heyari/ari/data/conversation/ConversationLogRepository.kt`:

```kotlin
package dev.heyari.ari.data.conversation

import dev.heyari.ari.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped single source of truth for the conversation message list.
 *
 * Both writers append here — the text path ([dev.heyari.ari.ui.conversation.ConversationViewModel])
 * and the voice path ([dev.heyari.ari.voice.VoiceSession], a singleton that
 * runs with no ViewModel alive, e.g. over the lock screen). The chat screen
 * observes [messages]. In-memory only: the log lives for the process lifetime
 * and is cleared on process death, exactly like text chat behaved before.
 *
 * [append] uses [MutableStateFlow.update], whose compare-and-set loop is
 * atomic, so voice (appending from Main) and text (from the ViewModel scope)
 * never race on the list.
 */
@Singleton
class ConversationLogRepository @Inject constructor() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    fun append(message: Message) {
        _messages.update { it + message }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.data.conversation.ConversationLogRepositoryTest"`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/data/conversation/ConversationLogRepository.kt app/src/test/java/dev/heyari/ari/data/conversation/ConversationLogRepositoryTest.kt
git commit -m "feat: add ConversationLogRepository as single source of truth for chat log"
```

---

## Task 2: Rewire the text path (`ConversationViewModel` + screen) through the repo

Behaviour-preserving refactor: the message list moves from `ConversationState.messages` to the repo. Typed chat must look and behave identically afterward. No new user-visible behaviour in this task.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/model/ConversationState.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt`
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt`

**Interfaces:**
- Consumes: `ConversationLogRepository.append(...)`, `ConversationLogRepository.messages` (from Task 1).
- Produces: `ConversationViewModel.messages: StateFlow<List<Message>>` (re-exposes the repo's flow so the screen keeps the ViewModel as its single facade).

- [ ] **Step 1: Remove `messages` from `ConversationState`**

In `app/src/main/java/dev/heyari/ari/model/ConversationState.kt`, delete the `messages` line so the class starts:

```kotlin
data class ConversationState(
    val inputText: String = "",
    val isListening: Boolean = false,
    val wakeWordDetected: Boolean = false,
    val sttState: SttState = SttState.Idle,
    val needsSetup: Boolean = false,
```

(Leave the rest of the class unchanged. Remove the now-unused `import dev.heyari.ari.model.Message`? No — `Message` isn't imported here; nothing else to change.)

- [ ] **Step 2: Inject the repo and expose `messages` in the ViewModel**

In `ConversationViewModel.kt`, add the import near the other `dev.heyari.ari` imports:

```kotlin
import dev.heyari.ari.data.conversation.ConversationLogRepository
```

Add the constructor parameter (place it next to the other repositories, e.g. after `cardRepository`):

```kotlin
    private val logRepository: ConversationLogRepository,
```

Immediately after the existing `_state` / `state` declarations (around line 74-75), add:

```kotlin
    /** The conversation log, sourced from the app-scoped repo. The screen
     *  observes this for the message list; the rest of the screen's state
     *  still comes from [state]. */
    val messages: StateFlow<List<Message>> = logRepository.messages
```

- [ ] **Step 3: Replace every message-append site in the ViewModel**

Replace each `it.copy(messages = it.messages + X, ...)` with a `logRepository.append(X)` call plus, where present, the remaining `_state.update` for non-message fields. Exact edits:

Line 192-193 (`onTextSubmitted` user message):
```kotlin
        val userMessage = Message(text = text, isFromUser = true)
        logRepository.append(userMessage)
        _state.update { it.copy(inputText = "", wakeWordDetected = false) }
```

Line 223-224 (filler bubble):
```kotlin
                val filler = Message(text = phrase, isFromUser = false)
                logRepository.append(filler)
```

Line 266-271 (assistant message):
```kotlin
            val ariMessage = Message(
                text = responseText,
                isFromUser = false,
                attachments = attachments,
            )
            logRepository.append(ariMessage)
```

Line 299-304 (`handlePushedEnvelope`):
```kotlin
        val message = Message(
            text = result.text,
            isFromUser = false,
            attachments = result.attachments,
        )
        logRepository.append(message)
```

Line 378-386 (`handleCardDemo`): replace the trailing block
```kotlin
        val userMessage = Message(text = raw, isFromUser = true)
        val ariMessage = Message(
            text = "Demo card injected: ${name ?: "anonymous"}, ${durSecs}s.",
            isFromUser = false,
            attachments = listOf(Attachment.Card(cardId)),
        )
        logRepository.append(userMessage)
        logRepository.append(ariMessage)
        _state.update { it.copy(inputText = "") }
```

Line 429-436 (`handleAlertDemo`):
```kotlin
        val userMessage = Message(text = raw, isFromUser = true)
        val ariMessage = Message(
            text = "Demo alert firing: ${name ?: "anonymous"}.",
            isFromUser = false,
        )
        logRepository.append(userMessage)
        logRepository.append(ariMessage)
        _state.update { it.copy(inputText = "") }
```

Line 457-458 (`handleLocationDebug` user echo):
```kotlin
        val userMessage = Message(text = raw, isFromUser = true)
        logRepository.append(userMessage)
        _state.update { it.copy(inputText = "") }
```

Line 491-492 (`handleLocationDebug` result):
```kotlin
            val ariMessage = Message(text = text, isFromUser = false)
            logRepository.append(ariMessage)
```

Line 498-499 (`handleRouterDebug` user echo):
```kotlin
        val userMessage = Message(text = raw, isFromUser = true)
        logRepository.append(userMessage)
        _state.update { it.copy(inputText = "") }
```

Line 507 (`handleRouterDebug` help):
```kotlin
            logRepository.append(help)
```

Line 516 (`handleRouterDebug` result):
```kotlin
            val ariMessage = Message(text = "🧭 $result", isFromUser = false)
            logRepository.append(ariMessage)
```

Line 566 (`onCardAction` spoken outcome):
```kotlin
                    logRepository.append(ariMessage)
```

After these edits, no `it.messages` reference remains in the file. Verify:
`grep -n "\.messages" app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` → no output.

- [ ] **Step 4: Point the screen at `viewModel.messages`**

In `ConversationScreen.kt`, just after line 84 (`val state by viewModel.state.collectAsStateWithLifecycle()`), add:

```kotlin
    val messages by viewModel.messages.collectAsStateWithLifecycle()
```

Then replace the 6 `state.messages` references with `messages`:
- Line 113: `LaunchedEffect(messages.size) {`
- Line 114: `if (messages.isNotEmpty()) {`
- Line 115: `listState.animateScrollToItem(messages.size - 1)`
- Line 125: `if (imeVisible && messages.isNotEmpty()) {`
- Line 126: `listState.animateScrollToItem(messages.size - 1)`
- Line 239: `items(messages, key = { it.id }) { message ->`

Verify: `grep -n "state.messages" app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt` → no output.

- [ ] **Step 5: Build and run the full unit-test suite (regression)**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (Task 1's repo tests included; nothing else references `ConversationState.messages`).

If compilation fails on an unresolved `messages`, re-check Steps 3-4 for a missed site.

- [ ] **Step 6: Emulator smoke test (text path unchanged)**

Build & install on the emulator, open the conversation screen, and type:

```
what is two plus two
```

Expected: your typed bubble appears, Ari replies in a bubble below it — identical to before. Also try `/router weather` and confirm the debug echo + result bubbles still render.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/model/ConversationState.kt app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt app/src/main/java/dev/heyari/ari/ui/conversation/ConversationScreen.kt
git commit -m "refactor: source chat message list from ConversationLogRepository"
```

---

## Task 3: Log voice turns from `VoiceSession`

Make `VoiceSession` append the recognised utterance and Ari's reply to the repo, so spoken turns appear in the chat. This is the payload task.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt`

**Interfaces:**
- Consumes: `ConversationLogRepository.append(...)` (Task 1), `dev.heyari.ari.model.Message`, `dev.heyari.ari.model.Attachment`, and `ActionHandler.handle(...).attachments` (existing; currently discarded in the voice path).

- [ ] **Step 1: Inject the repo and import the model types**

In `VoiceSession.kt`, add imports:

```kotlin
import dev.heyari.ari.data.conversation.ConversationLogRepository
import dev.heyari.ari.model.Attachment
import dev.heyari.ari.model.Message
```

Add a constructor parameter (after `settingsRepository`):

```kotlin
    private val logRepository: ConversationLogRepository,
```

- [ ] **Step 2: Log the card-answer branch (user word + spoken outcome)**

In `handleFinalText`, inside the `if (!awaitingReply)` block where a card intercept matches (around lines 353-372), the recognised word IS the user turn and `outcome.text` is Ari's reply. Update the `Spoken` branch, and append the user word for both outcomes. Replace the intercept block body with:

```kotlin
        if (!awaitingReply) {
            val intercept = cardActionVoiceIntercept.resolve(text)
            if (intercept != null) {
                logRepository.append(Message(text = text, isFromUser = true))
                val outcome = cardActionDispatcher.dispatch(intercept.cardId, intercept.action)
                when (outcome) {
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Silent -> {
                        dismiss()
                        return
                    }
                    is dev.heyari.ari.actions.CardActionDispatcher.Outcome.Spoken -> {
                        if (outcome.text.isNotBlank() || outcome.attachments.isNotEmpty()) {
                            logRepository.append(
                                Message(
                                    text = outcome.text,
                                    isFromUser = false,
                                    attachments = outcome.attachments,
                                )
                            )
                        }
                        _state.value = VoiceState.Responding(outcome.text)
                        speechOutput.speak(outcome.text)
                        val readMs = (outcome.text.length * 80L).coerceIn(3000L, 10_000L)
                        delay(readMs)
                        dismiss()
                        return
                    }
                }
            }
        }
```

(`CardActionDispatcher.Outcome.Spoken` already carries `text: String` and `attachments: List<Attachment>` — the same shape `ConversationViewModel.onCardAction` consumes.)

- [ ] **Step 3: Capture attachments from the Action branch**

Still in `handleFinalText`, the normal-dispatch `responseText` `when` (around lines 430-448) currently discards `actionHandler.handle(...).attachments`. Capture them. Add a `var` above the `when` and populate it in the `Action` arm:

```kotlin
        var attachments: List<Attachment> = emptyList()
        val responseText = when (response) {
            is FfiResponse.Text -> response.body
            is FfiResponse.Action -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val result = actionHandler.handle(response.json, response.skillId)
                attachments = result.attachments
                result.text
            }
            is FfiResponse.Binary -> "[Binary: ${response.mime}, ${response.data.size} bytes]"
            is FfiResponse.NotUnderstood -> response.body
        }
```

(The `withContext(Default)` wrapper and its rationale comment are unchanged — only the body now keeps `result` to read `.attachments`. Keep the existing comment block above the `Action` arm.)

- [ ] **Step 4: Append the user + assistant messages for the normal path**

After `responseText`/`attachments` are computed and after the retry/correction logic has settled on the final `usedText` (i.e. immediately before `_state.value = VoiceState.Responding(responseText)` at ~line 470), append both turns. Insert:

```kotlin
        // Log this spoken turn to the shared conversation log so it shows in
        // the chat window. Use `usedText` — the corrected transcript Ari
        // actually acted on (after the parallel/offline retry layers), not the
        // raw `text`. NotUnderstood replies are logged too, matching the text
        // path. Silent Layer-C phase-1 envelopes produce a blank responseText
        // with no attachments and are skipped, same as the text path.
        logRepository.append(Message(text = usedText, isFromUser = true))
        if (responseText.isNotBlank() || attachments.isNotEmpty()) {
            logRepository.append(
                Message(text = responseText, isFromUser = false, attachments = attachments)
            )
        }

        _state.value = VoiceState.Responding(responseText)
```

Note: the cold-start warm-up path (`Preparing` → "one moment" / "say that again") lives in `start()` and never reaches `handleFinalText`, so those cues are never logged — as intended. `handleFinalText` returns early on blank `text` (line 337-340) before any append, so an empty utterance logs nothing.

- [ ] **Step 5: Build and run unit tests (no regression)**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (`VoiceSessionTest` still only exercises the pure functions and is unaffected; the constructor change doesn't touch them.)

- [ ] **Step 6: Emulator e2e — voice turns appear in chat**

Build & install on the emulator. With the conversation screen open, trigger voice (wake word or tap-to-talk) and say:

```
hey ari what time is it
```

Expected: after the turn completes, a user bubble ("what time is it", or the corrected transcript) and an Ari reply bubble appear in the chat log — not just the transient overlay.

Then exercise a card-producing turn:

```
hey ari set a timer for two minutes
```

Expected: an Ari bubble with the countdown **card rendered inline** (parity with typing the same thing).

Then a card-answer turn — with that timer card active, say:

```
hey ari cancel
```

Expected: your "cancel" word logs as a user bubble and the spoken confirmation logs as an Ari bubble.

Finally, a miss — say something unintelligible/gibberish and confirm the "didn't catch that" reply appears as an Ari bubble.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat: log voice turns to the chat window via ConversationLogRepository"
```

- [ ] **Step 8: Push**

```bash
git push origin main
```

---

## Self-Review

**Spec coverage:**
- Singleton `ConversationLogRepository` as single source of truth → Task 1. ✅
- In-memory only → Task 1 (no persistence layer). ✅
- ViewModel becomes writer, keeps other state, drops ownership of list → Task 2. ✅
- Screen observes the repo (via `viewModel.messages`) → Task 2 Step 4. ✅
- VoiceSession appends user (`usedText`) + assistant → Task 3 Steps 2/4. ✅
- Log coverage: NotUnderstood ✅ (Step 4), card-answer turns ✅ (Step 2), let's-talk turns ✅ (they flow through `handleFinalText`, covered by Step 4), cold-start cues excluded ✅ (Step 4 note), Layer-C phase-1 skipped ✅ (Step 4 blank-guard). 
- Cards render inline for voice → Task 3 Step 3 captures attachments. ✅
- Overlay unchanged → no task modifies `VoiceOverlayActivity`/`VoiceState`. ✅
- Edge cases (user-bubble timing after correction, barge-in logs full text, thread safety, no dedup) → handled by Step 4 placement + repo's atomic `update`. ✅
- Testing: repo unit tests (Task 1), regression suite (Tasks 2/3 Step 5), emulator e2e (Task 3 Step 6). ✅

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows full code. ✅

**Type consistency:** `ConversationLogRepository.append(Message)` / `.messages: StateFlow<List<Message>>` / `.clear()` used identically across Tasks 1-3. `viewModel.messages: StateFlow<List<Message>>` matches the screen's `collectAsStateWithLifecycle()` usage. `Outcome.Spoken.text`/`.attachments` and `ActionResult.attachments` match existing usage in `ConversationViewModel`. ✅

**Note on VoiceSession test coverage:** Deliberately verified by emulator e2e, not a unit test — `VoiceSession` requires Robolectric (drives MediaPlayer/SpeechRecognizer/Main coroutine), which the codebase does not use. The genuinely-pure unit (the repo) is fully unit-tested. This matches the existing `VoiceSessionTest` convention.

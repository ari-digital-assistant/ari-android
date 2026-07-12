# User-Message Modality Glyphs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a subtle keyboard/mic glyph on every user chat message indicating whether it was typed or spoken, while leaving Ari's 'A' avatar untouched.

**Architecture:** Add a two-value `InputSource` (`Text`/`Voice`) to the `Message` model, defaulting to `Text`. Tag the message at each construction site (dictation and full voice turns → `Voice`; typing/chips → the default). Render the glyph in a trailing gutter on user rows in `MessageBubble`, driven by a pure `modalityGlyph(message)` helper that is the unit-tested seam.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `material-icons-extended` (already a dependency), JUnit4 JVM unit tests.

## Global Constraints

- **Frontend-only, ari-android only.** No `ari-engine`, no skill, no ari-linux changes.
- **Two buckets only:** `Text` and `Voice`. **Dictation is `Voice`** (the user spoke it), even though it submits through the typed path.
- **Ari's 'A' avatar is unchanged.** Only *user* messages get a modality glyph. Ari rows render no trailing glyph.
- **Per-message, every user row.** The glyph is per-message; do **not** change `MessageGrouping` and do **not** show it once-per-group.
- **Glyph style:** 16.dp icon, `Icons.Default.Keyboard` for `Text` / `Icons.Default.Mic` for `Voice`, tinted `onSurfaceVariant` at `alpha = 0.55f` (subtle). Trailing gutter mirrors Ari's leading avatar gutter.
- **Strings:** new EN strings AND their IT translations (DRAFT-marked) in the **same commit** — the ari-android translation-parity check is a post-push check, not a merge gate, so EN-only keys silently accrue debt.
- **Tests assert exact values** (project rule) — exact enum/`assertNull`, never weak thresholds.
- **Gradle needs `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`** on this machine (default daemon misses jlink).
- **Device verification is on the emulator (`emulator-5554`) only — never Keith's physical Pixel.**

## File Structure

- `app/src/main/java/dev/heyari/ari/model/Message.kt` — add `InputSource` enum + `source` field (Task 1).
- `app/src/test/java/dev/heyari/ari/model/MessageTest.kt` — **new**, model default test (Task 1).
- `app/src/main/java/dev/heyari/ari/ui/conversation/MessageGlyph.kt` — **new**, pure `ModalityGlyph` enum + `modalityGlyph()` helper (Task 2).
- `app/src/test/java/dev/heyari/ari/ui/conversation/MessageGlyphTest.kt` — **new**, helper unit tests (Task 2).
- `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` — `onTextSubmitted` source param + dictation caller (Task 3).
- `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt` — tag the two user-message sites `Voice` (Task 3).
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-it/strings.xml` — a11y labels (Task 4).
- `app/src/main/java/dev/heyari/ari/ui/conversation/MessageBubble.kt` — render the glyph (Task 4).

---

### Task 1: Add `InputSource` to the message model

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/model/Message.kt`
- Test: `app/src/test/java/dev/heyari/ari/model/MessageTest.kt` (create)

**Interfaces:**
- Produces: `enum class InputSource { Text, Voice }` and `Message.source: InputSource` (default `InputSource.Text`), in package `dev.heyari.ari.model`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/model/MessageTest.kt`:

```kotlin
package dev.heyari.ari.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTest {
    @Test
    fun `source defaults to Text`() {
        val m = Message(text = "hi", isFromUser = true)
        assertEquals(InputSource.Text, m.source)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.model.MessageTest"`
Expected: FAIL — compilation error, unresolved reference `InputSource` / `source`.

- [ ] **Step 3: Write minimal implementation**

Replace the entire contents of `app/src/main/java/dev/heyari/ari/model/Message.kt` with:

```kotlin
package dev.heyari.ari.model

import java.util.UUID

/** How a user's message reached Ari. Only meaningful for user messages. */
enum class InputSource { Text, Voice }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val source: InputSource = InputSource.Text,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.model.MessageTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/model/Message.kt app/src/test/java/dev/heyari/ari/model/MessageTest.kt
git commit -m "feat: add InputSource to Message model"
```

---

### Task 2: Pure `modalityGlyph` helper

**Files:**
- Create: `app/src/main/java/dev/heyari/ari/ui/conversation/MessageGlyph.kt`
- Test: `app/src/test/java/dev/heyari/ari/ui/conversation/MessageGlyphTest.kt` (create)

**Interfaces:**
- Consumes: `Message`, `InputSource` from Task 1.
- Produces: `enum class ModalityGlyph { Typed, Voice }` and `fun modalityGlyph(message: Message): ModalityGlyph?` in package `dev.heyari.ari.ui.conversation`. Returns `null` for Ari rows, `Typed`/`Voice` for user rows.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/heyari/ari/ui/conversation/MessageGlyphTest.kt`:

```kotlin
package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageGlyphTest {
    @Test
    fun `typed user message maps to Typed`() {
        val m = Message(text = "hi", isFromUser = true, source = InputSource.Text)
        assertEquals(ModalityGlyph.Typed, modalityGlyph(m))
    }

    @Test
    fun `voice user message maps to Voice`() {
        val m = Message(text = "hi", isFromUser = true, source = InputSource.Voice)
        assertEquals(ModalityGlyph.Voice, modalityGlyph(m))
    }

    @Test
    fun `ari message has no glyph regardless of source`() {
        val m = Message(text = "hi", isFromUser = false, source = InputSource.Voice)
        assertNull(modalityGlyph(m))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.ui.conversation.MessageGlyphTest"`
Expected: FAIL — unresolved reference `ModalityGlyph` / `modalityGlyph`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/heyari/ari/ui/conversation/MessageGlyph.kt`:

```kotlin
package dev.heyari.ari.ui.conversation

import dev.heyari.ari.model.InputSource
import dev.heyari.ari.model.Message

/** The modality glyph shown in a user message's trailing gutter. */
enum class ModalityGlyph { Typed, Voice }

/**
 * The trailing modality glyph for a chat row, or null when none should show.
 * Only user messages carry a modality glyph; Ari rows show the leading 'A'
 * avatar instead and yield null here.
 */
fun modalityGlyph(message: Message): ModalityGlyph? =
    if (!message.isFromUser) {
        null
    } else when (message.source) {
        InputSource.Text -> ModalityGlyph.Typed
        InputSource.Voice -> ModalityGlyph.Voice
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:testDebugUnitTest --tests "dev.heyari.ari.ui.conversation.MessageGlyphTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/MessageGlyph.kt app/src/test/java/dev/heyari/ari/ui/conversation/MessageGlyphTest.kt
git commit -m "feat: add pure modalityGlyph helper"
```

---

### Task 3: Thread the input source through the construction sites

There is **no unit-test seam** here — `ConversationViewModel` and `VoiceSession` require the full Hilt/Android graph and the project has no mocking framework (same rationale as the composer-dictation spec). Verification is compile + code review here; behaviour is verified on-device in Task 4.

**Files:**
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt` (lines ~175, ~247, ~280)
- Modify: `app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt` (lines ~368, ~501)

**Interfaces:**
- Consumes: `InputSource` from Task 1.
- Produces: `onTextSubmitted(text: String, source: InputSource = InputSource.Text)`. Every user `Message` now carries a correct `source`.

- [ ] **Step 1: Add the `InputSource` import to `ConversationViewModel.kt`**

Find the existing model import (the file already imports `dev.heyari.ari.model.Message`) and add alongside it:

```kotlin
import dev.heyari.ari.model.InputSource
```

- [ ] **Step 2: Add the `source` parameter to `onTextSubmitted` (line ~247)**

Change:

```kotlin
    fun onTextSubmitted(text: String) {
```

to:

```kotlin
    fun onTextSubmitted(text: String, source: InputSource = InputSource.Text) {
```

- [ ] **Step 3: Pass `source` into the user `Message` (line ~280)**

Change:

```kotlin
        val userMessage = Message(text = text, isFromUser = true)
```

to:

```kotlin
        val userMessage = Message(text = text, isFromUser = true, source = source)
```

- [ ] **Step 4: Tag the dictation submit as `Voice` (lines ~170-175)**

Change the dictation collector block:

```kotlin
        // Dictation final transcript → submit as if typed. onTextSubmitted's own
        // blank guard makes an empty utterance a no-op.
        viewModelScope.launch {
            voiceSession.dictatedText.collect { text ->
                _state.update { it.copy(isDictating = false, inputText = text) }
                onTextSubmitted(text)
            }
        }
```

to:

```kotlin
        // Dictation final transcript → submit as a Voice-sourced turn. The user
        // spoke it, so it carries the mic glyph even though it flows through the
        // typed path. onTextSubmitted's own blank guard makes an empty utterance
        // a no-op.
        viewModelScope.launch {
            voiceSession.dictatedText.collect { text ->
                _state.update { it.copy(isDictating = false, inputText = text) }
                onTextSubmitted(text, InputSource.Voice)
            }
        }
```

Leave the composer `onSend` (ConversationScreen:316) and empty-state `onChip` (ConversationScreen:232) callers **unchanged** — a tapped chip and typed text are both `Text` (the default). Do not touch them.

- [ ] **Step 5: Add the `InputSource` import to `VoiceSession.kt`**

The file already imports `dev.heyari.ari.model.Message`; add alongside it:

```kotlin
import dev.heyari.ari.model.InputSource
```

- [ ] **Step 6: Tag the card-intercept user message `Voice` (line ~368)**

Change:

```kotlin
                logRepository.append(Message(text = text, isFromUser = true))
```

to:

```kotlin
                logRepository.append(Message(text = text, isFromUser = true, source = InputSource.Voice))
```

- [ ] **Step 7: Tag the normal voice-turn user message `Voice` (line ~501)**

Change:

```kotlin
        logRepository.append(Message(text = usedText, isFromUser = true))
```

to:

```kotlin
        logRepository.append(Message(text = usedText, isFromUser = true, source = InputSource.Voice))
```

Do **not** change the assistant `Message(...)` at VoiceSession ~378 and ~504 (`isFromUser = false`) — assistant messages carry no glyph.

- [ ] **Step 8: Compile to verify**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/heyari/ari/ui/conversation/ConversationViewModel.kt app/src/main/java/dev/heyari/ari/voice/VoiceSession.kt
git commit -m "feat: tag voice and dictation messages as InputSource.Voice"
```

---

### Task 4: Render the glyph + a11y strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`
- Modify: `app/src/main/java/dev/heyari/ari/ui/conversation/MessageBubble.kt`

**Interfaces:**
- Consumes: `modalityGlyph()` / `ModalityGlyph` (Task 2), `R.string.msg_source_typed` / `R.string.msg_source_voice` (this task).

- [ ] **Step 1: Add EN a11y strings**

In `app/src/main/res/values/strings.xml`, immediately before the closing `</resources>`, add:

```xml
    <!-- Accessibility labels for the per-message input-modality glyph -->
    <string name="msg_source_typed">Typed message</string>
    <string name="msg_source_voice">Voice message</string>
```

- [ ] **Step 2: Add IT a11y strings (DRAFT)**

In `app/src/main/res/values-it/strings.xml`, immediately before the closing `</resources>`, add:

```xml
    <!-- DRAFT (auto, please confirm): accessibility labels for the input-modality glyph -->
    <string name="msg_source_typed">Messaggio digitato</string>
    <string name="msg_source_voice">Messaggio vocale</string>
```

- [ ] **Step 3: Add imports to `MessageBubble.kt`**

Add these imports (alphabetically among the existing `androidx.compose.*` imports):

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
```

- [ ] **Step 4: Compute the glyph once in the composable body**

Just after line 46 (`val isUser = message.isFromUser`), add:

```kotlin
    val glyph = modalityGlyph(message)   // null for Ari rows; drives the trailing gutter
```

(`modalityGlyph` is in the same package — no import needed.)

- [ ] **Step 5: Render the glyph in the trailing gutter**

In the outer `Row` (the one at line 59 with `horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start`), the `Surface { ... }` block closes at line 114 and the `Row` closes at line 115. Insert the glyph block between them, so it reads:

```kotlin
            Surface(
                shape = shape,
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = if (isUser) 0.dp else 2.dp,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                // ... unchanged inner content ...
            }

            // Trailing modality gutter — mirror of Ari's leading avatar gutter.
            // Present only on user rows (glyph != null), on every row.
            if (glyph != null) {
                MessageModalityGlyph(
                    glyph = glyph,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                )
            }
```

Because the outer `Row` uses `Arrangement.End` for user rows, the bubble sits to the left of the glyph and the pair right-aligns — the glyph forms the far-right column. Do not add any spacer/gutter on the Ari branch; `glyph` is `null` there.

- [ ] **Step 6: Add the `MessageModalityGlyph` composable**

At the bottom of `MessageBubble.kt` (e.g. after the `formatTimestamp` function), add:

```kotlin
/**
 * The subtle keyboard/mic glyph shown in a user message's trailing gutter,
 * indicating whether the turn was typed or spoken.
 */
@Composable
private fun MessageModalityGlyph(
    glyph: ModalityGlyph,
    modifier: Modifier = Modifier,
) {
    val (icon, descRes) = when (glyph) {
        ModalityGlyph.Typed -> Icons.Default.Keyboard to R.string.msg_source_typed
        ModalityGlyph.Voice -> Icons.Default.Mic to R.string.msg_source_voice
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.size(16.dp),
    )
}
```

- [ ] **Step 7: Compile + run the full unit suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (including Task 1 & 2 tests).

- [ ] **Step 8: Device verification (emulator only)**

Build & install to the emulator, then exercise the flows:

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:installDebug
adb -s emulator-5554 shell monkey -p dev.heyari.ari -c android.intent.category.LAUNCHER 1
```

Confirm on `emulator-5554`:
- Type `hello` in the composer and send → the user bubble shows a small **keyboard** glyph in a right-hand gutter.
- Tap the composer mic, say a phrase, let it auto-submit → that user bubble shows a **mic** glyph.
- Trigger a full voice turn (tap-to-talk / "Hey Ari") → the spoken user message shows a **mic** glyph.
- Ari's replies still show the **'A'** avatar on the left and **no** trailing glyph.
- The glyphs form a tidy right-aligned column; bubbles don't clip; the glyph aligns pleasantly with the first line of text (nudge the `top` padding in Step 5 if it sits high/low).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/java/dev/heyari/ari/ui/conversation/MessageBubble.kt
git commit -m "feat: render input-modality glyph on user messages"
```

---

## Self-Review

**Spec coverage:**
- Model `InputSource`/`source` field → Task 1. ✓
- Tag dictation + full voice turns `Voice`; typed/chip default `Text` → Task 3. ✓
- Trailing-gutter glyph, 16dp, keyboard/mic, subtle, per-message → Task 4 (Steps 5-6) + `modalityGlyph` Task 2. ✓
- Ari 'A' unchanged, no Ari trailing glyph → Task 4 Step 5 (`glyph` null on Ari branch). ✓
- EN + IT (DRAFT) strings same commit → Task 4 Steps 1-2 + 9. ✓
- No grouping change → not touched; `modalityGlyph` is per-message. ✓
- Frontend-only, ari-android only → no engine/skill/linux files in File Structure. ✓
- Testing: model default + helper unit tests + device sanity → Tasks 1, 2, 4. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The only soft note (glyph `top` padding) is an explicit device-tuning instruction with a concrete starting value (6.dp), not a placeholder. ✓

**Type consistency:** `InputSource { Text, Voice }` and `Message.source` (Task 1) are used identically in Tasks 3 & 4. `ModalityGlyph { Typed, Voice }` and `modalityGlyph(message): ModalityGlyph?` (Task 2) are consumed with matching names/types in Task 4. String ids `msg_source_typed` / `msg_source_voice` are defined (Task 4 Steps 1-2) and referenced (Step 6) consistently. ✓

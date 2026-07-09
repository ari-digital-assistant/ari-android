# Voice-in-Chat — Design

**Date:** 2026-07-09
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

Things said to Ari by voice are not reflected in the chat window — neither the
recognised user utterance nor Ari's spoken reply. The chat window should be a
faithful log of *all* conversation, whether typed or spoken.

## Root cause

The message list currently lives **inside** `ConversationViewModel` — messages
are a `List<Message>` field on the ViewModel's own `ConversationState`. The text
path (`onTextSubmitted`) appends to it; the voice path never touches it.

But `VoiceSession` is an app-wide `@Singleton` foreground pipeline that runs even
when **no screen and no ViewModel exist** — over the lock screen, or with the app
backgrounded (wake-word triggered). So the fix is not "wire voice into the
ViewModel." The message list must be hoisted out of the per-screen ViewModel into
an app-scoped singleton that both paths write to and the screen observes.

## Approach

Introduce a singleton **`ConversationLogRepository`** as the single source of
truth for the message list. Both writers append to it; the chat window observes
it. This mirrors the existing `CardStateRepository` singleton pattern already used
in the codebase.

### Rejected alternative

Pushing voice events to the ViewModel through a channel (like the existing
`AsyncEnvelopeChannel`) was considered and rejected: it only works while a screen
is alive, so turns spoken over the lock screen or while backgrounded would be
lost, and per-activity ViewModel recreation makes buffering/replay fragile. The
singleton repository is the honest fit for a writer that outlives any screen.

## Scope decisions (agreed)

- **Persistence:** In-memory only. The log lives for the app's process lifetime
  and is cleared on process death — exactly like text chat behaves today.
  Durable chat history is explicitly out of scope for this feature.
- **What gets logged from voice:**
  - Clean question → answer turns ✅
  - `NotUnderstood` ("didn't catch that") replies ✅ (text chat already logs these)
  - Card-answer turns — answering an active card by voice ("yes" / "cancel") ✅
  - "Let's talk" continuous-mode turns ✅
  - Transient cold-start cues ("one moment", "say that again") ❌ — UX plumbing,
    not conversation.
  - Layer-C phase-1 silent envelopes ❌ — stay skipped, same as the text path.
- **Voice-turn cards render inline:** the voice path already calls
  `actionHandler.handle(...)` and discards `result.attachments`; we capture and
  attach them to the logged bubble, giving full text/voice parity in the log.

## Components

### New: `ConversationLogRepository` (`@Singleton`)

The single source of truth for the conversation message list.

- `val messages: StateFlow<List<Message>>` — observed by the chat screen.
- `fun append(message: Message)` — atomic `update { it + message }`. Thread-safe:
  called from Main (voice) and from the ViewModel scope (text).
- `fun clear()` — resets the list. Not wired to any UI in this feature; provided
  for a future "clear chat" affordance and for test isolation.

In-memory only; no persistence layer.

### Changed: `ConversationViewModel`

Stops **owning** the message list but remains a **writer** and continues to own
all other screen state.

- Remove `messages` from `ConversationState`. The screen observes
  `repository.messages` directly for the message list, and the ViewModel for the
  rest of the UI state (input text, listening indicator, download progress,
  onboarding/setup cards, cloud-assistant hint).
- Every existing `_state.update { it.copy(messages = it.messages + …) }` becomes
  `logRepository.append(…)`. This includes:
  - `onTextSubmitted` (user + assistant bubbles, filler "still working" bubble)
  - `handlePushedEnvelope` (Layer-C phase-2 async continuation)
  - `onCardAction` (spoken card-action outcomes)
  - the debug hooks: `handleCardDemo`, `handleAlertDemo`, `handleLocationDebug`,
    `handleRouterDebug`
- Ordering across text, voice, and async envelopes stays consistent by insertion
  order into the shared repo.

### Changed: `VoiceSession`

Injects `ConversationLogRepository` and appends at its two existing emission
points inside `handleFinalText`:

- **User bubble:** append a `Message(isFromUser = true)` using **`usedText`** —
  the corrected transcript Ari actually acted on after the parallel/offline
  retry layers — not the raw `text`. Appended once, after correction resolves.
- **Assistant bubble:** append a `Message(isFromUser = false, attachments = …)`
  with the computed `responseText` and the captured action attachments.
- Applies to the normal dispatch path, the `NotUnderstood` path, and the
  `cardActionVoiceIntercept` card-answer branch (both the user's word and the
  spoken outcome). "Let's talk" turns flow through `handleFinalText` too, so they
  are logged with no extra work.
- The cold-start warm-up path (`Preparing` → "one moment" / "say that again")
  appends nothing.

## Data flow

```
Text:  onTextSubmitted   → append(user)          → engine → append(assistant)
Voice: handleFinalText   → append(user=usedText) → engine → append(assistant + attachments)
Both →  ConversationLogRepository.messages (StateFlow) → ConversationScreen
```

The transient voice overlay (`VoiceOverlayActivity` / `VoiceState`) is unchanged:
it stays the live "I'm listening / thinking / responding" affordance. The chat
window is now the durable record of the same turns. Turns spoken while
backgrounded or over the lock screen appear the next time the screen is opened,
naturally ordered by insertion.

## Edge cases

- **User-bubble timing (voice):** the user bubble is appended once, after the
  correction layer resolves — a beat later than the overlay's live partial. This
  avoids append-then-edit churn. Acceptable because the overlay already shows the
  live transcript in real time.
- **Barge-in:** if Ari is cut off mid-reply, the full `responseText` it was
  speaking is still logged. Minor and faithful enough.
- **Thread safety:** `StateFlow.update` is atomic; voice appends from Main, text
  from the ViewModel scope — no races, no shared mutable list.
- **No de-duplication needed:** the overlay and the chat are different surfaces;
  each turn is appended exactly once, by exactly one writer.

## Testing

Following the project rule that tests assert exact values and real behaviour:

- `ConversationLogRepository`: `append` produces exact list contents in order;
  concurrent appends from two coroutines all land (assert final set + size).
- `VoiceSession` turn: a normal spoken turn appends exactly one user `Message`
  (text == `usedText`) and one assistant `Message` (text == `responseText`, with
  the expected attachments). A `NotUnderstood` turn appends the apology bubble.
- The cold-start "one moment / say that again" path appends **nothing** to the
  repo.
- Regression: `onTextSubmitted` behaviour is unchanged from the consumer's point
  of view (same bubbles, same order), now routed through the repo.

## Out of scope

- Durable/persistent chat history across restarts.
- A "clear chat" UI affordance (the repo method exists; no UI).
- ari-linux (not yet implemented).
- Any change to the transient voice overlay UX.

# User-Message Modality Glyphs — Design

**Date:** 2026-07-12
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan

## Problem

In the chat window, Ari's replies carry the circular **'A'** avatar; user messages
carry nothing. That asymmetry gives Ari an identity mark but tells the user nothing
about their own turns.

More useful than a mirror-image user avatar is an **input-modality** indicator: was
this user turn **typed** into the chat window, or did it arrive by **voice** —
whether a full voice turn (wake word / tap-to-talk / from the system) or the
composer **dictation** mode we recently shipped? The chat log currently gives no
way to tell a typed turn from a spoken one after the fact.

## Approach

Add a per-message **input source** to the chat model and render a small, subtle
**modality glyph** in a trailing gutter on every user message — a keyboard glyph
for typed turns, a mic glyph for voice turns. **Ari's 'A' avatar is unchanged.**

Two buckets only — `Text` and `Voice`. **Dictation counts as Voice** (the user
spoke it), even though it is submitted through the same code path as typing. This
is the one place the design has to do real plumbing: typed and dictated turns
currently converge and become indistinguishable at `ConversationViewModel.onTextSubmitted`,
so the source has to be threaded through that funnel rather than inferred later.

### Rejected alternatives

- **Drop the 'A' avatar; show a modality glyph on both sides.** Symmetric and
  slightly more informative (Ari's glyph would signal whether it spoke back), but
  it costs Ari its brand mark, and Ari's output modality is largely derivable from
  the user turn. Not worth the trade. (Chosen: keep 'A', user-side glyph only.)
- **Only annotate voice turns; leave typed turns bare.** Cleaner, but a lone mic
  glyph reads ambiguously ("has audio attached?"). Showing both glyphs makes the
  text-vs-voice axis explicit — which is the whole point. (Chosen: both, always
  shown, low-emphasis.)
- **Store the finer distinction (`Dictation` vs `Voice`) in the model.** YAGNI —
  both render as the same mic glyph and the product decision is two buckets. If a
  future need to distinguish them arises, the enum extends without touching call
  sites.
- **Infer dictation from `_state.isDictating` at submit time instead of a param.**
  Relies on a mutable flag being in the right state at the right instant; an
  explicit `source` argument on `onTextSubmitted` is unambiguous and testable.

## Decisions (agreed)

1. **Keep Ari's 'A' avatar; add a modality glyph to user messages only.**
2. **Two buckets: `Text` vs `Voice`.** Dictation and full voice turns are both
   `Voice`.
3. **Both glyphs always shown, low-emphasis** — every user message renders its
   glyph (keyboard for `Text`, mic for `Voice`).
4. **Per-message, on every user row — no grouping change.** The glyph is a
   property of the individual message, not the group; showing it once-per-group
   (like the 'A') would hide the modality of earlier messages in a mixed-modality
   burst. Modality is *not* a grouping boundary.
5. **Right-hand gutter placement**, mirroring Ari's left-hand avatar gutter —
   a clean vertical "modality lane" decoupled from bubble width. User bubbles
   shift left by the gutter width to make room.
6. **Frontend-only, ari-android only.** No `ari-engine` or skill change.

## Components

### Changed: `model/Message.kt`

Add a two-value enum and one defaulted field:

```kotlin
enum class InputSource { Text, Voice }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val source: InputSource = InputSource.Text,   // only read when isFromUser
)
```

The `Text` default means assistant messages, the debug slash-command user
messages (`/card-demo`, `/alert-demo`, `/location`, `/router`), and any other
existing construction site compile and behave unchanged. `source` is only *read*
by the renderer when `isFromUser` is true.

### Changed: `ConversationViewModel`

`onTextSubmitted` gains a source parameter:

```kotlin
fun onTextSubmitted(text: String, source: InputSource = InputSource.Text) { ... }
```

- The user `Message` it constructs (~line 280) is built with `source = source`.
- The composer `onSend` path keeps the default → `Text`.
- The **dictation collector** (`voiceSession.dictatedText`, ~line 175) calls
  `onTextSubmitted(text, InputSource.Voice)`.

No other VM call site changes: the four debug slash-command handlers build their
own user `Message` inline and inherit the `Text` default.

### Changed: `VoiceSession`

Both user-message construction sites in `handleFinalText` set `source = Voice`:

- the card-intercept user message (~line 368),
- the normal voice-turn user message (~line 501).

These are full voice turns (wake / tap-to-talk / system) and never overlap the
dictation path.

### Changed: `ui/conversation/MessageBubble.kt`

Add a **trailing gutter** on user rows that mirrors the existing leading avatar
gutter on Ari rows (currently ~32.dp, drawn on `isLastInGroup`, `Spacer` otherwise).

- On **user** rows: after the bubble, render a fixed-width (~32.dp) gutter holding
  a **16.dp** glyph — `Icons.Default.Keyboard` for `InputSource.Text`,
  `Icons.Default.Mic` for `InputSource.Voice`, tinted `onSurfaceVariant` at
  reduced alpha (subtle). Drawn on **every** user row (per Decision 4), aligned to
  the top of the bubble the way the 'A' avatar is.
- User bubbles shift left by the gutter width so the lane clears the row's
  trailing padding — the right-hand mirror of Ari's left gutter.
- Ari rows are untouched: leading 'A' gutter as today, no trailing gutter.

Both glyph vectors already exist in `material-icons-extended` (a project
dependency) and `Icons.Default.Mic` is already used by `AriComposer` / `WakeSwitch`
— no new drawable assets.

### New strings (i18n + a11y)

The glyph is icon-only, so each needs a `contentDescription`. Two new **source**
strings, e.g.:

- `msg_source_typed` — EN "Typed message"
- `msg_source_voice` — EN "Voice message"

EN values plus **IT draft-marked** translations in the **same commit**. The
ari-android translation-parity check is a *post-push* check, not a merge gate, so
EN-only keys silently accrue missing-IT debt and email failures — add IT (marked
DRAFT for Keith to confirm) alongside the EN keys.

## Data flow

```
TYPED:
  composer onSend(text)
    → ConversationViewModel.onTextSubmitted(text)              [source defaults to Text]
    → Message(text, isFromUser = true, source = Text)          → keyboard glyph

DICTATION:
  composer mic → dictation → VoiceSession emits dictatedText
    → collector: onTextSubmitted(text, InputSource.Voice)
    → Message(text, isFromUser = true, source = Voice)         → mic glyph

FULL VOICE TURN (wake / tap-to-talk / system):
  VoiceSession.handleFinalText(...)
    → Message(text, isFromUser = true, source = Voice)         (~line 368 / ~line 501)  → mic glyph

RENDER (MessageBubble, user row):
  source == Text  → trailing gutter: Icons.Default.Keyboard (subtle)
  source == Voice → trailing gutter: Icons.Default.Mic      (subtle)
```

## Edge cases

- **Assistant messages:** `source` is never read (glyph is user-only); the `Text`
  default is immaterial for them.
- **Debug slash-command user messages:** inherit `Text` — correct, they are typed.
- **Empty dictation transcript** (`Done("")`): `onTextSubmitted`'s existing blank
  guard makes it a no-op → no message, no glyph. Unchanged.
- **Mixed-modality group** (e.g. type, type, then dictate — same-sender, close in
  time, so grouped together): each row still shows its own glyph, because the glyph
  is per-message, not per-group.
- **Layout shift:** the new trailing gutter narrows the max user-bubble width by
  ~32.dp. Long-message wrapping is unaffected beyond that; verify no clipping.

## Testing

Per the project rule that tests assert exact values and real behaviour:

- **Model default (JUnit4):** `Message(text = "hi", isFromUser = true).source ==
  InputSource.Text` — exact enum assertion.
- **Source threading (JUnit4, pure where possible):** the dictation path yields a
  user `Message` with `source == InputSource.Voice`; a plain composer submit yields
  `source == InputSource.Text`. If `onTextSubmitted` can be exercised without full
  Android wiring, assert the appended message's `source`; otherwise assert the
  smallest pure seam that carries the source decision.
- **Render (Compose UI test):** a user `Message(source = Voice)` shows the mic glyph
  (assert by `contentDescription` = the voice string); `source = Text` shows the
  keyboard glyph; an Ari message shows the 'A' avatar and **no** trailing glyph.
- **Device sanity (emulator):** type a turn → keyboard glyph; dictate a turn → mic
  glyph; do a tap-to-talk voice turn → mic glyph. Confirm the right gutter aligns
  as a clean column and bubbles don't clip.

## Out of scope

- Any `ari-engine` or skill change (frontend-only).
- Ari's avatar or any Ari-side modality indicator (the 'A' stays as-is).
- Distinguishing dictation from full voice turns visually (both are `Voice` / mic).
- A settings toggle to hide the glyphs (always on; revisit only if asked).
- ari-linux (not yet implemented).

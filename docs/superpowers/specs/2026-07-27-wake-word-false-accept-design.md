# Wake-Word False-Accept Containment — Design

**Date:** 2026-07-27
**Component:** ari-android (Android-only; ari-linux is not yet implemented)
**Status:** Approved, pending implementation plan
**Follow-up:** `2026-07-27-wake-word-model-retrain-design.md` (consumes the audio
this design collects)

## Problem

Ari fires on speech that was never addressed to it. Observed at the **Low**
sensitivity setting, with `hey_ari` as the active model. Two consequences have
both been seen in the field:

1. The ready cue plays and the overlay takes over the screen for nothing.
2. Worse — ambient speech has been transcribed and dispatched to the engine,
   so Ari has acted on things nobody asked it to do.

The same failure is reproducible on a Home Assistant Voice PE dev unit ("OK"
triggering "OK Nabu"), which is not a coincidence: see below.

## Why the sensitivity setting cannot fix this

`MicroWakeWordEngine.cpp` is a faithful port of ESPHome's `micro_wake_word` —
the same component Voice PE runs. Same sliding-window mean test, same
`MIN_SLICES_BEFORE_DETECTION` cool-off. The two devices share a failure mode
because they share an algorithm.

Detection (`MicroWakeWordEngine.cpp:269`) is:

```
sum(recent probabilities) > probabilityCutoff × slidingWindowSize
```

i.e. **mean probability > cutoff, sustained across the whole window.**

`WakeWordSensitivity.LOW` sets `probabilityCutoff = 0.99f`,
`slidingWindowSize = 14`. In engine units the cutoff is
`(uint8) (0.99 × 255) = 252`, so detection needs
`sum > 252 × 14 = 3528` against a theoretical maximum of `255 × 14 = 3570`.
That is a **mean probability ≥ 0.9885 held across 14 consecutive inferences.**

For comparison, the model author's own recommended operating point (`assets/hey_ari.json`)
is `probability_cutoff: 0.97`, `sliding_window_size: 5`. We are already running
roughly three times stricter than spec and still false-accepting.

**Conclusion:** the model is not marginally wrong, it is *confidently* wrong —
it saturates near 255 on the confusable input. There is under 1.2% of headroom
left before the ceiling, and spending it would start dropping genuine wakes.
The fix has to be a **second stage**, not a better threshold.

### Note on the dead per-model constants

`WakeWordRegistry` carries `probabilityCutoff` / `slidingWindowSize` per model,
but `WakeWordService` always passes the values from the `WakeWordSensitivity`
enum instead (`WakeWordService.kt:251-252`). The registry values are therefore
inert. Out of scope for this design — noted so nobody tunes them expecting an
effect.

## Approach

**Post-hoc verification by a second, much stronger model — the one we are
already running.**

`hey_ari.tflite` is 64 KB, int8, built for an ESP32. It has no vocabulary and
no language model; it answers one question, on phonetic shape alone. Sherpa is
a streaming zipformer ASR with real acoustic and language modelling, and it is
**already transcribing the same audio** — `CaptureBus` keeps a 2 s ring and STT
arms with `DEFAULT_REWIND_SECONDS = 2.0f`, so the wake phrase itself lands in
the transcript. That is why `WakePhrase.stripWakePhrase()` had to be written in
the first place (see the comment at `SpeechRecognizer.kt:715-720`).

The two disagree in exactly the useful way. The tiny model fires on a phonetic
near-miss; sherpa, which knows what English words are, renders the same audio
as "hey there" / "okay so" / "have a look" — **no name token.** That is the
signal, and today it is discarded.

This is the same shape as Alexa's device-model-then-cloud-verification design.
Here both models are local and both are already running, so the marginal cost
is a regex over a string we already have.

### Rejected alternatives

- **Tighten the threshold further.** No headroom left (see above). Trades false
  accepts for false rejects at a terrible exchange rate.
- **Phonetic matching (metaphone / phoneme edit distance) instead of a token
  list.** Generalises to mishears nobody has written down, but is materially
  more code and test surface for a marginal gain over a list that is already
  maintained empirically. Revisit if the rejection logs show the list failing.
- **Re-run the pre-roll through the wake model at a stricter threshold.** Same
  model, same blind spot. Asking the same witness twice.
- **Second opinion from the parallel sherpa stream.** Does not work — see
  "Traps" below. Documented so it is not attempted.
- **Retrain the model with hard negatives.** This is the real fix and it is the
  follow-up spec. It is blocked on having real false-accept data, which is what
  §5 of this design exists to collect.

## Design

### 1. Preserve the evidence

`stripWakePhrase()` destroys the thing we want to inspect before `VoiceSession`
ever sees it. Make the strip report what it did.

**`voice/WakePhrase.kt`:**

```kotlin
data class WakeMatch(val text: String, val nameMatched: Boolean)

fun matchWakePhrase(text: String, locale: String = "en"): WakeMatch
```

`nameMatched` is `true` **only** when the stage-1 regex fired — optional opener
plus a real name token from `BASE_NAMES`. The stage-2 bare-opener fallback
(`WakePhrase.kt:110-113`) sets it `false`, because "sherpa heard an opener and
no name" is precisely the false-accept signature.

`stripWakePhrase()` remains as a wrapper returning `.text`. There are four
existing call sites, all in `SpeechRecognizer.kt`; the two that need the
verdict (the streaming partial and the whisper decode) switch to
`matchWakePhrase`, and the two that only want query text (the parallel-stream
finalisation and `transcribeOffline`) are unchanged.

**`stt/SttState.Done`** gains two fields — `raw: String?` (the unstripped
transcript) and `nameMatched: Boolean?` (the verdict):

| Construction site | `raw` | `nameMatched` |
|---|---|---|
| `SpeechRecognizer.kt:496` (streaming/online — the common path) | `rawPartial`, already in scope | `wakeVerdict(matchWakePhrase(rawPartial, locale), locale)` |
| `SpeechRecognizer.kt:626` (whisper/offline) | `transcript`, already in scope | `wakeVerdict(matchWakePhrase(transcript, locale), locale)` |
| `SpeechRecognizer.kt:644` (`stopListening()`) | `null` | `null` — only the cleaned partial survives here; fails open |

**Why the verdict is computed in `SpeechRecognizer`, not `VoiceSession`:**
`matchWakePhrase` needs the active locale, and `SpeechRecognizer` already holds
`localeProvider` and already calls the strip with it. Computing it there avoids
injecting `LocaleProvider` into `VoiceSession` for one boolean. `raw` is carried
alongside regardless, because the rejection log (§6) and the capture sidecar
(§5) both need the actual text.

#### The gate is English-only

Owner decision taken mid-implementation, after this design was approved. A
verdict is formed **only when the active locale is `en`**; every other locale
gets `null`, which `shouldAcceptWake` treats as "accept". The rule lives in
`voice/WakePhrase.kt`:

```kotlin
internal fun wakeVerdict(match: WakeMatch, locale: String): Boolean? =
    if (locale == "en") match.nameMatched else null
```

The reason is that `BASE_NAMES` is an **empirical list of English sherpa
mishears** — `harry`, `airy`, `ray`, `re` are there because English sherpa
produced them — and `WakeMishearTable` is still empty for every non-English
locale. A non-English recogniser renders the (always English) wake phrase
through its own phonotactics, and we have no evidence about what comes out. On
that evidence a verdict would be a guess, and a wrong `false` is the expensive
direction: it converts a benign failure (wake phrase left in the query, engine
answers "not understood") into a hard one (turn silently dismissed, reads as
"Ari ignored me"). Same call the skill router already makes with
`routerSupportsLocale`.

`raw` is still populated for every locale — the non-English transcripts
accumulating in the rejection log (§6) are how `WakeMishearTable` eventually
gets filled in and the gate extended.

Tested in `WakePhraseTest`; the `assertNull(wakeVerdict(…, "it"))` case exists
specifically to fail if someone deletes the locale check.

### 2. The decision rule

A top-level `internal fun` in `VoiceSession.kt`, matching the existing
`shouldRearm` / `isStaleTurn` / `shouldCutTts` helpers (`VoiceSession.kt:59`)
which are already written that way to be unit-testable:

```kotlin
internal fun shouldAcceptWake(
    verifyWake: Boolean,
    raw: String?,
    nameMatched: Boolean?,
): Boolean = when {
    !verifyWake          -> true   // not a wake-initiated turn at all
    nameMatched == null  -> true   // no verdict available — fail open
    raw.isNullOrBlank()  -> true   // silence, not a mishear — the timeout owns this case
    else                 -> nameMatched
}
```

Evaluated in the `SttState.Done` branch (`VoiceSession.kt:275`), **after** the
existing `isStaleTurn` check (a straggler from a superseded turn is already
discarded and must not be re-judged here) and **before** `handleFinalText()`.

On reject: log, `dismiss()`, return. Nothing reaches the engine, nothing enters
conversation history, no TTS, no card, no haptics. The overlay simply goes away.

On accept, `verifyWake` is cleared for the remainder of the session, so a
re-armed reply turn is never re-verified.

**Fail open on every ambiguity.** A wrongly-rejected genuine command reads to
the user as "Ari ignored me", which is a worse experience than a spurious
chime. When in doubt, let it through.

**Only the first turn of a session is verified.** Re-armed reply turns and
"let's talk" turns arm with `rewindSeconds = 0f` — there is no wake phrase in
their audio to find, so verifying them would kill every one of them. Once a
session's opening turn is accepted, verification is off for its lifetime.

### 3. Scope guard — wake-initiated turns only

`WakeWordService.launchVoiceOverlay()` (`WakeWordService.kt:414`) is shared by
**two** callers:

- `onWakeWordDetected()` — the wake path. Pre-roll contains "Hey Ari".
- `ACTION_START_VOICE_TURN` — tap-to-talk. The user pressed a button and said
  nothing. **Pre-roll contains no wake phrase.**

Verifying blindly would bin every tap-to-talk turn. So:

- `launchVoiceOverlay(verifyWake: Boolean)`
- → `EXTRA_VERIFY_WAKE` on the `VoiceOverlayActivity` intent
- → read in `VoiceOverlayActivity.onCreate`
- → `voiceSession.start(verifyWake = …)`

`onWakeWordDetected()` passes `true`; `ACTION_START_VOICE_TURN` passes `false`.
`startDictation()` is a separate entry point and is untouched.

### 4. Blast radius — split the silence timeout

`SILENCE_TIMEOUT_MS = 30_000L` (`VoiceSession.kt:767`) currently serves three
situations that want different answers. After a false accept it leaves the mic
armed for **thirty seconds** — which is the actual mechanism by which ambient
speech reached the engine.

| Situation | Timeout | Rationale |
|---|---|---|
| Initial wake turn, streaming recogniser | **8 s** | You just said "Hey Ari". Nothing in 8 s means it was not you. |
| Initial wake turn, offline recogniser | 30 s (unchanged) | No partials to refresh on, so 8 s would cap the utterance rather than the silence. |
| Re-armed reply turn | 30 s (unchanged) | A skill asked a question; thinking time is legitimate. |
| Dictation (`VoiceSession.kt:709`) | 30 s (unchanged) | Deliberate user tap. |

**The short window applies to the streaming recogniser only.** On that path it
really is 8 s of *total silence*, not 8 s of turn: `lastActivityAt` refreshes on
every non-blank partial, so a slow speaker is never cut off mid-sentence. The
offline whisper path has no such refresh — it emits `Listening("")` once at arm
and then nothing until `Transcribing`, so `lastActivityAt` never moves between
arm and endpoint, and 8 s there would be a hard cap on the whole utterance
against a recogniser that supports 30 s ones. Since every non-English locale is
on the offline path, that would be a straight regression for exactly the users
the English-only verifier gate exists to leave alone. Offline wake turns
therefore keep the 30 s window (`SpeechRecognizer.isStreaming` gates it).

This change is valuable **independently** of the verifier — it still shrinks the
window when the verifier's token list lets something through.

### 5. False-trigger audio capture (debug setting)

Ships in **this** phase, not the follow-up. Data cannot be collected
retroactively: every false accept between now and the retrain project is a
recording we will never get back.

`SpeechRecognizer.kt:490-495` already merges the full utterance — pre-roll plus
live audio — into `mergedAudio` for the offline-retry path. `audioAccum`
(`:406`) starts collecting from the first batch off the channel, and the first
batches *are* the pre-roll slices. The audio we want is already assembled and
then thrown away.

**Two capture hooks:**

1. **Verifier rejection.** Persist `mergedAudio` from the rejected `Done`.
2. **Silent false fire.** Wake fires, nobody speaks, the turn dies on the 8 s
   timeout. No `Done` is ever produced, so hook 1 never sees it — and these are
   the *best* hard negatives, since whatever set the model off is uncontaminated
   by a following command. Requires snapshotting the `CaptureBus` ring at
   detection time (2 s × 16 kHz × 2 B ≈ 64 KB, held in memory) and persisting it
   only if the turn dies unverified. Dropped on acceptance.

**Retention and privacy.** This is a microphone writing to disk; the constraints
are not negotiable:

- **Off by default**, under a clearly labelled Debug section — not buried among
  normal settings.
- **App-private storage only.** Never external storage, never auto-uploaded,
  never auto-shared.
- **Hard bounded:** 50 files or 20 MB, whichever is reached first, oldest
  evicted. Never unbounded growth.
- **16-bit PCM WAV @ 16 kHz** — the format microWakeWord training expects, so
  clips drop into a training set with no conversion.
- Each clip gets a **sidecar** recording the raw transcript (or `""` for hook 2)
  and which hook caught it.
- The settings page shows **live file count and total size**, so this never
  becomes invisible background recording.
- **Delete all** button, explicit.
- **Share-sheet export** so a clip bundle can leave the device without `adb`
  (needed on release builds, where app-private storage is not pullable).
- Turning the setting **off stops capture but does not delete** what is already
  there. Deleting the user's data is their decision, not a side-effect of a
  toggle.

### 6. Observability

Every rejection logs at `Log.w` with the raw transcript:

```
Wake rejected: raw='hey there mate what time is it'
```

This is the empirical feed. A mishear seen twice is a one-line addition to
`BASE_NAMES`, which shrinks the false-reject risk over time. Text only — the
audio path in §5 is separately gated behind its own opt-in setting.

## Traps — deliberately documented

**Do not use the parallel sherpa stream as a second verification opinion.** It
is already computed (`SpeechRecognizer.kt:461-482`) and looks like a free second
verdict, but per `SpeechRecognizer.kt:728-729` it **deliberately skips the
pre-roll** — which is exactly where the wake phrase lives. It has nothing to
verify against. Wiring it up would reject every wake.

**Do not lengthen `detectionDebounceMs` after a rejection.** Superficially
sensible — we have evidence this environment is producing false fires — but if
we rejected a *genuine* wake, the user says it again within a couple of seconds,
and a longer lockout eats the retry. That is a worse failure than the one it
fixes.

## Testing

`WakePhrase` is pure and `shouldAcceptWake` is a top-level function, so these
assert exact values against real behaviour.

**`matchWakePhrase`:**

| Input | `nameMatched` | `text` |
|---|---|---|
| `hey ari whats the weather` | `true` | `whats the weather` |
| `okay so whats the weather` | `false` | `so whats the weather` |
| `harry can you set a timer` | `true` | `can you set a timer` |
| `i was talking to dave about it` | `false` | `i was talking to dave about it` |
| `` (empty) | `false` | `` |

**`wakeVerdict`:**

| Input | `locale` | Verdict |
|---|---|---|
| `hey ari whats the weather` | `en` | `true` |
| `hey there mate` | `en` | `false` |
| `hey there mate` | `it` | `null` — no verdict outside English |

**`shouldAcceptWake`:**

| `verifyWake` | `raw` | `nameMatched` | Result |
|---|---|---|---|
| `false` | `"hey there mate"` | `false` | accept |
| `true` | `null` | `null` | accept |
| `true` | `""` | `false` | accept |
| `true` | `"hey ari whats the weather"` | `true` | accept |
| `true` | `"hey there mate"` | `false` | **reject** |

**Integration:** a rejected turn must never call into the engine and must never
write to conversation history.

**Capture:** eviction is bounded (51st file removes the 1st); WAV header is
valid 16-bit/16 kHz/mono; capture is a no-op when the setting is off.

## Known limitations — stated plainly

1. **The chime still fires on a false accept.** This is a containment design,
   not a prevention design. Stage one false-fires at exactly today's rate; what
   changes is that a false accept can no longer *do* anything. Eliminating the
   chime would require holding the ready cue until verification completes,
   adding latency to every genuine wake — explicitly rejected during design.
2. **A novel mishear of "ari" kills a real command — in English.** If sherpa renders it as
   something outside the fourteen-entry `BASE_NAMES` list, a legitimate turn is
   binned and it looks to the user like Ari ignored them. Mitigated by failing
   open on blank/null and by the rejection logs surfacing new mishears quickly.
   It is a genuinely new failure mode.
3. **`BASE_NAMES` is loose by construction.** It contains `harry`, `airy`,
   `ray`, `re` — it was built to *avoid* false rejects, and using it as a gate
   inherits that looseness. Ambient speech containing those tokens sails
   through. This design **reduces** false accepts; it does not eliminate them.
4. **A genuine wake whose phrase fell outside the 2 s rewind is rejected.** The
   ring is 2 s and "Hey Ari" is ~0.7 s, so there is ~1.3 s of arm-latency slack
   (the generous rewind exists precisely for this — `SpeechRecognizer.kt:715`).
   Considered acceptable.

Limitation 1 is the reason the follow-up spec exists.

## Out of scope

- Retraining `hey_ari.tflite` — follow-up spec.
- Any change to `ok_ari` / `hey_jarvis` beyond inheriting the same gate.
- Phonetic matching (rejected alternative; revisit only if logs justify it).
- Any upload, telemetry, or off-device transmission of captured audio.
- ari-linux (not yet implemented).

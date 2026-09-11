# Wake-Word Retrain — Design

**Date:** 2026-09-10
**Components:** ari-tools (corpus + training), ari-android (capture, delivery)
**Status:** Draft. Blocked on §4 recording session.
**Supersedes:** `2026-07-27-wake-word-model-retrain-design.md` — that design
assumed the fault was hard negatives alone. It is not. See §2.
**Depends on:** `2026-07-27-wake-word-false-accept-design.md` (§5 audio capture,
shipped) — supplies the hard negatives this design consumes.

## 1. Problem

`hey_ari.tflite` fails in two directions at once in a two-person household:

- It **false-accepts** — reproducibly on non-speech with no phonetic content at
  all, and, per the §2.5 capture review, overwhelmingly on ordinary speech in
  practice. The demo case and the lived case are not the same case.
- It **fails to wake** for a second household member speaking at normal
  conversational volume, in the same room, on the same device.

The July design treated these as one problem (a weak negative set) with one fix
(hard negatives). The second symptom shows that framing was incomplete.

## 2. Diagnosis — evidence, not inference

Gathered 2026-09-10 by direct observation on the primary test device.

| Input | Fires stage 1? |
|---|---|
| Non-speech low-frequency burst (loud) | **yes** |
| Second speaker, "Hey Ari", normal volume | **no** |
| Second speaker, "Hey Ari", stressed syllable shouted | **yes** |
| Second speaker, "OK Ari" (`ok_ari` model), normal volume | **yes** |
| Second speaker, "Hey Jarvis" (`hey_jarvis` model), normal volume | **yes** |

Sensitivity was swept `MEDIUM` → `HIGH` with **no observable effect** on the
second speaker.

### 2.1 The threshold is not the problem

`HIGH` drops the cutoff to 0.95 and the window to 5 — a large, coarse loosening.
If the second speaker's utterances were producing probabilities near the
decision boundary, some would have converted. None did.

**Conclusion: her probability is not marginal, it is nowhere near the line.**
Threshold tuning is exhausted as an avenue. This closes the question the July
design left open.

### 2.2 The audio pipeline was audited and is clean

Before blaming the model, the mechanical explanations were checked and
eliminated:

- `MicroFrontendWrapper.cpp` is a faithful port of ESPHome's
  `preprocessor_settings.h`, constants included.
- **PCAN gain control is enabled** (strength 0.95, offset 80, 21 gain bits).
- Filterbank limits are 125–7500 Hz — ample for any adult speaker.
- Noise reduction and log scaling match upstream.
- `AudioRecord` runs at 16 kHz through the service's own source.

No defect found. The frontend is not the fault.

### 2.3 RETRACTED: "the model learned amplitude as a proxy"

**This section previously asserted that `hey_ari` learned loudness as a stand-in
for the wake phrase, because its positives were never volume-augmented. That
explanation is wrong and is retracted. It was a hypothesis that fitted the
symptoms, written up as though it were established.**

What actually settled it: reading the trainer that produced the model.
`TaterTotterson/microWakeWord-Trainer-AppleSilicon`, `scripts_macos/make_features.py`,
configures upstream's `Augmentation` with `"Gain": 0.8` and inherits the
upstream defaults `min_gain_db=-45`, `max_gain_db=0`. So four clips in five were
volume-augmented across a 45 dB range — wider than the range this project's own
pipeline applies. It also mixed backgrounds from CHiME, FMA and AudioSet at
`p=0.7`, and applied room impulse responses at `p=0.7`.

Volume augmentation was not skipped. The shortcut story cannot stand.

**What survives, because it is measured rather than reasoned:**

- The sensitivity sweep does nothing for the second speaker (2.1).
- She wakes it by shouting the stressed syllable; `ok_ari` and `hey_jarvis`
  both work for her at a normal speaking volume, same device, same room.
- The model is **saturated**. Sweeping the cutoff from 0.50 to 0.95 changes
  nothing at all: identical recall, identical 15-of-16 false accepts. At 0.999
  nine of sixteen still fire. It emits near-1.0 for a genuine wake and for
  "spaghetti" alike. Measured 2026-09-11; see `ari-tools/wakeword/baseline/`.

**The mechanism is therefore open.** Candidates worth testing rather than
asserting: the negative set carried no non-English speech at all, which fits the
capture review finding that Maltese and French dominate the real false accepts
(2.5); backgrounds were mixed at only 5-10 dB SNR, so positives were never
badly buried; and 2.4 below, which is now the strongest surviving explanation
and is independent of any training setting.

Augmenting volume remains correct practice and this project's pipeline does it
at `p=1.0`. It is just not the diagnosis.

### 2.4 The wake phrase itself has a low ceiling

Independent of training quality, `hey_ari` is the hardest phrase in the app:

| Phrase | Obstruent anchor |
|---|---|
| Hey **S**iri | `/s/` — high-energy 4–8 kHz fricative |
| Alexa | `/k/` plosive + `/s/` fricative |
| Hey **J**ar**v**is | `/dʒ/` affricate + `/v/` |
| **OK** Ari | `/k/` plosive |
| Hey Ari | **none** |

`/heɪ ɑɹi/` is four consecutive sonorants. No plosive, no fricative, no affricate
— nothing that produces a sharp spectral transition. Every shipping wake word in
the industry has at least one obstruent; this one has zero. The `/h/` onset is
turbulent airflow, which is also precisely why broadband non-speech bursts get
through.

**Implication: retraining will improve `hey_ari` substantially but will not make
it as reliable as `ok_ari` or `hey_jarvis`.** That is a property of the phoneme
sequence, not of effort. Plan around it; do not treat it as a training failure.

### 2.5 First capture review — 45 clips, 2026-09-10

The shipped false-accept capture was exported and read. It overturns two
assumptions this document was drafted with.

**Every false accept was speech. There were no silent captures at all** — the
"wake fired, nobody spoke" bucket was empty across the whole export. Loud
non-speech transients are reproducible on demand, but they are not what actually
wakes Ari in a lived-in house. §4.4 is reordered accordingly: transients stay in
the negative set, they are no longer the headline of it.

**Non-English speech is the largest single trigger class: 5 of 18.** Two
distinct sources, and they need different material:

- **Maltese — spoken in the household, live and near-field.** Three clips. This
  is the first language of the house alongside English and the model has never
  heard a word of it.
- **French — media playback, not people.** Two clips, from something playing on
  a screen. Nobody in the household speaks it. The significance is not French
  specifically: both clips contain an "arri" sequence (`J'arrive` /ʒaʁiv/,
  `arrêter` /aʁete/), and any Romance-language audio will supply those. The
  model's negative set was assembled by an English-speaking community trainer
  and contains English only.

**Two of the eighteen "rejected" clips were genuine wakes**, quarantined because
sherpa rendered the name as "Yari" and "Hayori". The same command
("Hey Ari, how's the weather?") appears five times among the accepted clips. So
the phase-1 gate has a real false-reject rate and it is not negligible — worth
measuring in its own right, and a standing argument for the quarantine: as
negatives these two would teach the model to ignore the user.

Remaining confusions cluster as spoken numbers ("Eighty-nine. Key.", "Eight and
nine.", "Plus 59"), "All right" twice, and "Okay, Addy." — which is a near-miss
on `hey_ari` and `ok_ari` simultaneously.

## 3. What ships before any of this

`ok_ari` already works for the affected speaker, today, in shipped code.

**The immediate mitigation is a settings change, not a project.** The brand is
the assistant's name; the wake phrase is a setting. Alexa answers to four
triggers, Siri to two. Supporting both "Hey Ari" and "OK Ari" costs nothing and
requires no branding change.

Everything below is the durable fix, and is not urgent once this is done.

## 4. Corpus — the blocker

### 4.1 Positives (recorded)

The scarce, irreplaceable material. Requirements:

- **Normal conversational volume.** Shouted takes are near-worthless: they are
  samples of the failure mode we are trying to remove.
- Multiple rooms, multiple distances (near-field to across-room).
- Some with realistic background: television, kitchen noise.
- A few dozen takes per speaker minimum.
- **Uncompressed PCM. AGC and noise suppression disabled.** A recorder that
  normalises loudness destroys the exact variation this corpus exists to supply.

### 4.2 Open decision — capture path

**The recordings must come through the same audio path the wake listener
uses**, or the eval set measures something production never sees.

Two options:

- **(a) Third-party recorder app.** Fast, zero development. Risks a different
  `AudioSource` and unknown platform-level processing.
- **(b) Debug capture mode inside Ari.** Guarantees an identical source, sample
  rate and processing chain. Costs a small amount of app work.

**Recommend (b).** This is a one-shot session with a non-technical participant
whose goodwill is finite; getting the path wrong means asking them to redo it.
The cost of (b) is hours. The cost of discovering (a) was wrong is the session.

Decide before scheduling the session.

### 4.3 Consent

Recording a household member's voice for model training requires their explicit,
informed agreement, including what is stored, where, and for how long. Not a
formality — it is the same standard the project applies to its users, and
applying a weaker one at home would be indefensible.

### 4.4 Negatives

Ordered by what the 2026-09-10 capture review (§2.5) actually found, not by
what was predicted.

- **Real captures from the shipped false-accept debug setting.** Gold: the actual
  confusions, not guesses about them. Rejected clips are reviewed by hand first
  — that export contained two genuine wakes among eighteen.
- **Non-English speech — the largest observed class.** Two kinds, both needed:
  **Maltese**, spoken and near-field, because it is a language of the household;
  and **non-English media audio**, because a screen playing Romance-language
  speech hands the model "arri" sequences all evening. Standard negative corpora
  are English-only and supply neither. English-only training with an
  English-only negative set is what produced this blind spot.
- **Near-misses** derived from the rejection logs' transcripts, not from
  intuition: spoken numbers, "all right", and name-adjacent words like "Addy".
- **Loud non-speech transients** — door slams, impacts, broadband bursts. Still
  worth including. Note the incumbent's trainer already mixed AudioSet and
  CHiME backgrounds, so this class was not absent from its training — another
  reason the §2.3 story did not survive contact with the trainer config.
  Demoted from the headline: the review found none of these in the wild, so the
  class is a known theoretical weakness rather than an observed cause.
- Standard large-vocabulary and multi-speaker ambient corpora.

### 4.5 Synthetic positives

`piper-sample-generator`, LibriTTS-R generator:

- Wide speaker sampling, with `--max-speakers` set **below** 904 — thinly
  represented speakers produce artefacts.
- Speaker-embedding blending (`--slerp-weights`) for voices between speakers.
- `--length-scales` across a wide range.
- **Volume reduction augmentation.** Correct practice, and this project applies
  it at `p=1.0` against the incumbent trainer's `0.8`. No longer claimed as the
  fix: see the retraction in §2.3.
- Impulse responses for room and distance variation.

## 5. The exam — held out, and kept honest

Recorded positives go to the **eval set, not the training set**.

With two real speakers the marginal training value is negligible and the
overfitting risk is real. In eval they are the only honest signal available:
the check on whether synthetic training generalises to a human it has never
heard.

**Ship gate — decided now, not after training. Both, on data neither model has
seen:**

1. **False accepts per hour** on a held-out negative corpus including real
   captures. Must beat the incumbent.
2. **Recall on held-out positives**, at each model's own best operating point.
   Must not regress. A model that never false-fires because it never fires is
   not a win.

Measured by a committed script. Both numbers recorded in the model's `.json`.

**The failure mode to guard against is leakage**, not incompetence: after
several failed runs it becomes tempting to let eval material into training
because the numbers improve. See `<repo-root>/docs/postmortems/2026-07-functiongemma-router-saga.md` (Ari monorepo root, not `ari-android/docs/`).
An explicit leakage check is part of the pipeline, not a manual discipline.

### 5.1 Leakage route nobody designed: two capture paths, one utterance

The 2026-09-10 export contained 22 accepted-wake clips and 22 command clips.
**They are the same 22 utterances.** Every accepted-wake clip pairs with a
command clip within 15 seconds: `WakeCaptureStore`'s keep-everything firehose
and `UtteranceCaptureStore` both record an accepted turn, through different
windows — roughly 2 s against 4.2 s of the same moment.

De-duplicating by filename or by hash will not catch this. The two files are
different lengths, from different directories, written by different features,
and neither knows the other exists.

**So the leakage check cannot be per-file. It has to be per-utterance**, pairing
across capture directories on timestamp proximity before anything is split. A
clip in training whose twin is in eval defeats the ship gate completely, and
would do so silently and with better-looking numbers.

Keep the command copy: it is the longer window, and audio that was never
recorded cannot be recovered by trimming.

## 6. Training — local

Lives in `ari-tools/wakeword/`. **Runs on the dev machine (RTX 5070, 8 GB), not
on Modal.**

A 64 KB int8 CNN over spectrogram features does not need rented silicon. More
importantly, the eval corpus is household voice recordings, and shipping those
off-machine contradicts the project's stated position that your voice stays on
your device. That is a hard blocker.

Public negative corpora may be preprocessed off-machine if size justifies it.
Recorded positives may not.

Expect several failed runs — the upstream trainer documents that a first attempt
"will most likely not be usable".

**Measured 2026-09-11, correcting an earlier estimate in this document:** Piper
generation is not the bottleneck it was assumed to be. 40 clips in 3.4 s on the
RTX 5070 — roughly 12/s, so 10,000 positives is a quarter of an hour, not an
overnight run. torch ships a `cu130` build that supports `sm_120`, so generation
gets the GPU even though TensorFlow currently cannot see it.

Augmentation cost is still unmeasured and runs on CPU; it may yet dominate. What
is settled is that *generation* does not.

## 7. App prerequisite — the operating point must not be ignored

`WakeWordRegistry` carries per-model `probabilityCutoff` / `slidingWindowSize`,
but `WakeWordService` passes the `WakeWordSensitivity` enum's values instead.
**The registry values are inert.**

A retrained model would therefore silently run on the incumbent's operating
point, and the entire retrain would be evaluated through the wrong lens.

Fix: sensitivity becomes a **per-model relative adjustment**. Each model ships
its derived operating point as the `MEDIUM` anchor; `HIGH` and `LOW` are offsets
from it. User-facing semantics are unchanged.

**Prerequisite of shipping any retrained model.** Not a tidy-up.

## 8. Risks

1. **Overfitting to one household.** Two speakers, one home. The eval set must
   not be drawn from the same sessions as anything used for tuning.
2. **The recording session is one-shot in practice.** Get §4.2 right first.
3. **`hey_ari`'s ceiling is a phoneme problem** (§2.4). A retrain that improves
   it substantially and still trails `hey_jarvis` is a success, not a failure.
   Say so before measuring, or the result reads as disappointing.
4. **The operating-point refactor in §7 touches `ok_ari` and `hey_jarvis`**,
   neither of which is being retrained. Both need a smoke test.
5. **Capture only records false accepts.** A miss produces no artefact, so the
   shipped capture setting will never yield a single data point about the
   second speaker. That data only exists if it is deliberately recorded.

## 9. Out of scope

- Per-language wake models. Wake training stays English-only — note this is
  about the *positives*: the phrase is still only ever spoken in English. The
  negative set is explicitly multilingual per §4.4, which is a different thing
  and not a contradiction.
- Retraining `ok_ari` or `hey_jarvis`.
- Changes to the phase-1 verification gate. It stays regardless.
- Broad community audio collection. Consenting testers **are** in scope, and the
  §4.2 recorder is the intended path for their contributions — so it is not
  purely a debug tool for one developer. Before it goes in front of anyone
  outside the household: the consent wording, the route off a tester's device,
  and what a tester is told happens to their voice all need answering. Not
  solved here. Anything wider is a separate consent and privacy design.
- Renaming the assistant or retiring "Hey Ari" as the primary phrase. §2.4
  documents the cost; the decision is a product one and is not taken here.

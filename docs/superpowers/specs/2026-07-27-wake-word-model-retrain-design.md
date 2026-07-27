# Wake-Word Model Retrain (Hard Negatives) — Design

**Date:** 2026-07-27
**Components:** ari-tools (training), ari-android (delivery)
**Status:** Approved in principle. **Blocked on data collection.**
**Depends on:** `2026-07-27-wake-word-false-accept-design.md` — specifically the
§5 audio capture, which produces the training data this design consumes.

## Problem

`hey_ari.tflite` false-accepts on speech that is not the wake phrase. The
containment design stops a false accept from *doing* anything, but stage one
still fires — the user still gets an unwanted chime and screen takeover.

The root cause is the model. Per `assets/hey_ari.json` it came off a community
trainer (`TaterTotterson/microWakeWord-Trainer-AppleSilicon`) with whatever
negative set that notebook ships by default. It was never shown the specific
things that actually confuse it in Keith's house.

The containment design measured the headroom precisely: at `LOW` sensitivity
the model must hold a mean probability ≥ 0.9885 across 14 consecutive
inferences and *still* fires on the wrong input. It is not uncertain, it is
confidently wrong. That is a training problem, and only training fixes it.

## Why this is a follow-up and not part of phase 1

It is blocked on data we do not have yet. Guessing at hard negatives is how you
get another mediocre model. The capture setting in phase 1 exists precisely to
answer "what is actually setting it off?" with evidence rather than speculation.

**Do not start this until there is a meaningful capture corpus.** A sensible
bar is a few dozen real false-accept clips spanning more than one room and more
than one background condition (TV, kitchen, conversation).

## Success criteria — decided before training, not after

This project has a specific failure mode: a model that looks better on the
metric you happened to optimise and is worse in the house. See
`docs/postmortems/2026-07-functiongemma-router-saga.md` — the same trap, the
same discipline required.

**Ship gate. A new model ships only if it beats the incumbent on both, measured
on data neither model was trained on:**

1. **Primary — false accepts per hour** on a held-out negative corpus that
   includes real captures from phase 1. This is microWakeWord's own headline
   metric and the thing the user actually feels.
2. **Guard — recall on held-out positives** must not regress against the
   incumbent at each model's own best operating point. A model that never
   false-fires because it never fires is not a win.

Both measured by a committed eval script, not by eyeballing training logs.
Both numbers recorded in the model's `.json` alongside the operating point, so
a future reader knows what was actually claimed.

## Approach

### 1. Build the corpus

**Hard negatives — the whole point of the exercise:**

- **Real captures from phase 1.** The gold data. Everything else is a proxy for
  this.
- **Synthesised near-misses driven by what the captures show.** Not guessed:
  the phase-1 rejection logs record the raw transcript of every false accept, so
  the near-miss list is derived from observed confusions rather than intuition.
  Expect openers alone ("hey", "ok", "okay"), name-adjacent words the strip list
  already knows about ("harry", "airy", "ray", "are we"), and whatever the logs
  surface that nobody predicted.
- **Standard negative corpora** already used by microWakeWord training
  (large-vocabulary speech, plus the "dinner party"-style multi-speaker
  ambient sets it evaluates false-accepts-per-hour against).

**Positives:**

- Piper-generated samples of "Hey Ari" across many voices, speeds and pitches.
- Augmentation with room impulse responses and background noise.
- **Real recordings from Keith and any willing testers.** Scarce and
  disproportionately valuable — synthetic positives all share a TTS accent
  profile that real speech does not.

### 2. Train

Lives in **`ari-tools/wakeword/`**, mirroring the structure already proven in
`ari-tools/functiongemma/`:

| functiongemma | wakeword equivalent |
|---|---|
| `generate-dataset.py` | corpus assembly + augmentation |
| `generate-eval.py` | held-out eval set construction |
| `modal_train.py` | Modal training entrypoint |
| `eval.py` | false-accepts-per-hour + recall against the gate |
| `derive_floor.py` | derive the per-model operating point (see §3) |
| `test_*.py` | unit tests for the above |

Same discipline: the eval set is generated and committed, the gate is a script,
and the tests cover the dataset tooling — not just the model.

**Dependency versions must be checked at implementation time.** The
microWakeWord trainer, TFLite-micro, and the Piper sample generator all move;
do not pin from this document.

### 3. Derive the operating point — and fix the coupling that would ignore it

A retrained model will have its own probability distribution and therefore its
own best `probability_cutoff` / `sliding_window_size`. That must be **derived
from the eval sweep**, exactly as the router's per-model floor was.

**There is a blocker in the app that has to be fixed as part of shipping this.**
`WakeWordRegistry` already carries per-model `probabilityCutoff` and
`slidingWindowSize`, but `WakeWordService.kt:251-252` passes the values from the
`WakeWordSensitivity` enum instead, so the registry values are inert (noted in
the phase-1 spec as dead constants).

That means **a new model would silently run on the old model's operating
point** — and the whole retrain would be evaluated through the wrong lens.

The fix: sensitivity becomes a **per-model relative adjustment** rather than an
absolute override. Each model ships its own derived operating point as the
`MEDIUM` anchor; `HIGH` and `LOW` are offsets from that anchor. This preserves
the user-facing setting and its current semantics (`HIGH` = fires more readily)
while letting each model's own numbers matter.

This is a prerequisite of shipping any retrained model, not an optional tidy-up.

### 4. Deliver

Two options, decided when there is a model to ship:

- **Bundled asset**, as today — replace `assets/hey_ari.tflite` + `.json`. Simple,
  ships with the APK, needs a release to update.
- **Model auto-update manifest**, riding the existing manifest-driven update
  layer already used for router / LLM / STT models. Wake models are tens of KB,
  so the transfer cost is trivial, and it decouples model iteration from app
  releases — which matters a lot if the first retrain is not the last.

Auto-update is the better long-term answer; bundled is fine for a first swap.
Recommend deciding based on how confident the first retrain looks.

## Risks — stated honestly

1. **microWakeWord training is documented as "advanced users only,"** and its
   own docs warn that basic notebook output "will most likely not be usable"
   without hyperparameter tuning. Budget for several failed runs. This is not a
   one-afternoon project.
2. **The real-capture corpus will grow slowly** — realistically a handful of
   clips a week. Starting too early with too little data produces a model that
   overfits to three recordings of the telly.
3. **Overfitting to one voice and one room.** If positives are overwhelmingly
   Keith in his kitchen, recall will fall off a cliff for everyone else. Real
   positives from testers matter, and the held-out set must not be drawn from
   the same sessions as the training set.
4. **The false-accept metric is environment-dependent.** False-accepts-per-hour
   measured on a clean corpus will flatter any model. The held-out negative set
   has to include the messy real captures or the gate means nothing.
5. **Regression risk to `ok_ari` / `hey_jarvis`.** If the operating-point
   refactor in §3 changes how sensitivity is applied, those two models are
   affected even though neither is being retrained. They need a smoke test at
   minimum.

## Out of scope

- Per-language wake models. Wake training remains English-only, consistent with
  the current routing architecture.
- Retraining `ok_ari` or `hey_jarvis` — `hey_ari` is the default and the one
  generating the complaints. Revisit once the pipeline is proven.
- Any change to the phase-1 verification gate. It stays regardless of how good
  the model gets; a cheap second opinion is worth keeping.
- Collecting audio from users other than Keith and explicitly consenting
  testers. Broad community data collection is a separate consent and privacy
  design, not a rider on this one.

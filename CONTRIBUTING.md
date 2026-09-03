# Contributing to ari-android

Thanks for thinking of contributing to Ari. This file covers the bits
that aren't obvious from the code: testing setup, translation policy,
PR conventions.

For what this repo is, how to build it and how to report a bug, see
[README.md](README.md). Ari spans several repositories — the Rust engine
is in [ari-engine](https://github.com/ari-digital-assistant/ari-engine)
and the skill registry is in
[ari-skills](https://github.com/ari-digital-assistant/ari-skills), each
with its own contributing notes.

## Translations

Ari ships with English (canonical) and Italian. Adding a new language
or improving an existing translation is welcome — instructions below.

### File layout

```
app/src/main/res/
├── values/
│   └── strings.xml          ← canonical English; source of truth
├── values-it/
│   └── strings.xml          ← Italian
└── values-{locale}/
    └── strings.xml          ← future locales
```

`{locale}` is an Android resource qualifier — typically a 2-letter
ISO 639-1 code (`it`, `es`, `fr`, `de`). Region variants (`pt-rBR`,
`zh-rCN`) work too if needed, but we don't ship any yet.

### How translation works

When the user picks a language in onboarding (or changes it later in
General settings), Ari calls `LocaleManager.applicationLocales` with
that ISO code. Android's resource resolver then prefers
`values-{locale}/strings.xml` over `values/strings.xml` for
`stringResource(...)` lookups in Compose and `context.getString(...)`
elsewhere. Missing keys silently fall back to the canonical English
entry — see the CI lint below for why we don't allow that to ship.

### Hard rule: no machine translation

**Never auto-translate strings into a language you don't read.** This
holds across every Ari repository, and it is not negotiable here. Bad
translations are worse than no translation: they're plausible enough to
slip through review, but they degrade the experience of the people
you're claiming to support.

If you don't speak the target language fluently:

- Don't add a `values-{locale}/strings.xml` for it. Open an issue
  asking for a translator instead.
- Don't fix individual strings from a Google Translate output. Wait
  for a native speaker to correct them.

If you *do* speak the language fluently and want to either add a new
locale or improve an existing one:

1. Copy `values/strings.xml` to `values-{locale}/strings.xml`.
2. Translate every `<string>` value. Don't translate the `name=`
   attribute — keys are stable identifiers, not display text.
3. Preserve `%1$s`, `%1$d`, `%2$s` etc. format placeholders exactly,
   in the same order — Kotlin code substitutes them positionally.
4. Preserve inline `<b>`, `<i>` tags and HTML entities (`&amp;`,
   `&apos;`, etc.). Use `\'` for apostrophes inside `'…'`-quoted
   string values per Android resource rules.
5. Keep tone consistent within the locale. Italian uses informal
   "tu" throughout (matching Ari's conversational tone). Match
   that register if you're translating into another European
   language; for languages with sharper formal/informal splits
   (e.g. Japanese), pick one and document the choice in a comment.
6. Run `./gradlew :app:assembleDebug` before opening the PR. The
   build fails on malformed XML (mismatched tags, bad `%`
   placeholders, unescaped quotes).
7. Set Ari to your new locale and walk through onboarding +
   conversation + settings to spot truncation, missing translations,
   and copy-tone issues before review.

### CI lint: no half-translated locales

A GitHub Action validates that every `values-{locale}/strings.xml`
declares the same set of `name=` keys as `values/strings.xml`. A
missing key in a locale file silently falls back to English at
runtime, producing mixed-language chrome that's worse than fully
English. The lint catches the drift before it ships.

If you legitimately need to remove a string (deprecation, refactor),
remove it from every `values-{locale}/strings.xml` in the same PR
that removes it from `values/strings.xml`.

### What to translate, what to skip

Translate everything in `values/strings.xml` — every `<string>`
that's there is user-visible chrome. Don't translate:

- Keys (`name="..."` attributes)
- URLs (e.g. `about_github_url`)
- Stable identifiers that happen to be in the strings file
  (currently none, but if any are added in future their comment
  should flag them)
- Brand/product names (`Ari`, `ChatGPT`, `Claude`, `Gemini`,
  `microWakeWord`, `OpenTasks`) — keep them as-is
- Time/date format strings — those go through code-side locale
  formatting, not the strings table

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

466 tests, no device, no emulator, about twelve seconds warm. The HTML
report lands at
`app/build/reports/tests/testDebugUnitTest/index.html`. To run one
class:

```bash
./gradlew :app:testDebugUnitTest --tests '*LogScrubberTest'
```

The translation parity lint runs locally too, and needs nothing but
Python:

```bash
python3 tools/check_translation_parity.py
```

### Keep the logic out of Android

Unit tests run on the JVM against Android's stub `android.jar`, and
`unitTests.isReturnDefaultValues = true` makes every stubbed API return
a default rather than throw. That's what keeps the suite fast, and it's
also why anything that reaches into a real Android class is effectively
untestable here.

So the convention throughout is to put the decision in plain Kotlin and
let the Android or Compose layer do nothing but call it.
`MessageGrouping` is the clearest example: every rule about how the
conversation list groups bubbles is tested without rendering a pixel.
If something feels hard to test, that's usually the design talking, not
the tooling.

One trap that follows from the same setting: it stubs `org.json` too,
so `optString` and friends return `null` instead of `""`. The real
`org.json:json` is therefore a test dependency. If a parser test starts
behaving impossibly, that's the first thing to check.

### No mocking framework, deliberately

There's no Mockito and no MockK, and please don't add one without a
conversation. Tests pass hand-written fakes or plain lambdas —
`AriFfiSettingWriterTest` routes its writes through three one-line
fakes. It keeps tests asserting on behaviour rather than on which
methods got called in what order.

Tests that need real files use JUnit's `TemporaryFolder` rule; see
`AudioClipStoreTest`.

Hilt doesn't appear in the unit tests at all. They construct what they
need directly, which is another reason to keep constructors honest.

### Instrumented tests

`app/src/androidTest/` currently holds nothing but the scaffold AGP
generated. There is **no Compose UI test suite yet** — the dependencies
are wired up (`ui-test-junit4`, `ui-test-manifest`), so adding the first
one needs no ceremony, but nothing has justified one so far. If you
write it, replace this paragraph with the conventions you set.

### Running it on a device

```bash
./gradlew :app:installDebug        # device or emulator attached
```

An emulator is fine for most things, but not for the parts most likely
to break: the wake word wants a real microphone, and background
behaviour depends heavily on the ROM's power management. If you're
touching either, use a physical phone.

## Pull requests

- Direct-to-main is fine for app-side changes. The `ari-skills/`
  skill repo gates everything in `skills/` behind PRs; this repo
  doesn't.
- Run `./gradlew :app:assembleDebug` and the relevant unit tests
  before pushing.
- Keep the diff focused. Mechanical refactors (rename, externalise
  inline strings) belong in their own commits, separate from the
  feature work that motivated them.

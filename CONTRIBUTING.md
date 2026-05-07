# Contributing to ari-android

Thanks for thinking of contributing to Ari. This file covers the bits
that aren't obvious from the code: testing setup, translation policy,
PR conventions.

For project-wide context (architecture, anti-slop rules, vibe-coding
philosophy), see [`../CLAUDE.md`](../CLAUDE.md).

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
is a project-wide rule documented in
[`../CLAUDE.md`](../CLAUDE.md#key-principles). Bad translations are
worse than no translation: they're plausible enough to slip through
review, but they degrade the experience of the people you're claiming
to support.

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

(TODO: build/run/emulator instructions, hilt test setup,
compose-test conventions. For now see existing test files for
patterns.)

## Pull requests

- Direct-to-main is fine for app-side changes. The `ari-skills/`
  skill repo gates everything in `skills/` behind PRs; this repo
  doesn't.
- Run `./gradlew :app:assembleDebug` and the relevant unit tests
  before pushing.
- Keep the diff focused. Mechanical refactors (rename, externalise
  inline strings) belong in their own commits, separate from the
  feature work that motivated them.

# ari-android

The Android app for **[Ari](https://heyari.dev)** — an open-source, on-device
digital assistant. Wake word, speech recognition, intent routing and a small
language model all run on the phone; the cloud is opt-in, per feature, and
always labelled.

This repo is the *frontend*. The brains live in
[ari-engine](https://github.com/ari-digital-assistant/ari-engine) (Rust, linked
in over UniFFI) and the skills live in
[ari-skills](https://github.com/ari-digital-assistant/ari-skills).

## Reporting bugs

**On a testing build, use the bug report button in the app.** It's the round
button floating in the corner — drag it out of the way if it's in the way. It
collects your app and engine versions, device and ROM, installed skills,
granted permissions and the stack trace if Ari crashed, so you don't have to
find any of that yourself.

It comes in two halves, and you choose the second one:

- The **public half** becomes an issue in this repo that anyone can read — your
  description, your versions, your device. No files, and never any audio.
- The **private half** is whatever you tick: a scrubbed log, a screenshot, this
  session's conversation, voice recordings. The ones with your voice in them
  start switched off, and a consent box gates the lot. Say no and the report
  still files perfectly well.

Logs are scrubbed on the phone before anything leaves, and the preview shows
you the scrubbed version rather than the original. Files are held privately for
90 days and then deleted; you can withdraw a report sooner from
**Settings › Debug › My Reports**. The full story, including the bits we can't
promise, is on [heyari.dev/privacy](https://heyari.dev/privacy/#bug-reports).

**Not on a testing build?** [File an issue](https://github.com/ari-digital-assistant/ari-android/issues/new/choose)
and fill in the form. The device and build questions matter more than they
look — a lot of Ari's stranger behaviour comes down to which ROM you're on and
how aggressively it kills background services.

Something wrong with a *skill* rather than the app? That belongs in
[ari-skills](https://github.com/ari-digital-assistant/ari-skills/issues/new/choose).

## Build it

You need a **sibling checkout of `ari-engine`**. The app compiles the Rust
engine as part of its own build and looks for it at `../ari-engine` relative to
this repo, so cloning this one on its own gets you a build failure and not much
else:

```
your-workspace/
├── ari-android/   ← you are here
└── ari-engine/    ← must exist
```

Then:

```bash
git clone https://github.com/ari-digital-assistant/ari-engine
git clone https://github.com/ari-digital-assistant/ari-android
cd ari-android
./gradlew :app:assembleDebug
```

**What you need installed:** JDK 17 or newer, the Android SDK with NDK
`28.0.13004108`, and a Rust toolchain with the `aarch64-linux-android` and
`x86_64-linux-android` targets. Gradle, AGP and Kotlin versions come from the
wrapper and the version catalogue — don't install those by hand.

Only `arm64-v8a` and `x86_64` are built. There's no 32-bit slice because there's
no 32-bit build of the Rust engine, and shipping an ABI without one produces an
app that installs fine and crashes on launch.

### If a release or beta build dies in the Rust step

Use the NDK's own CMake rather than whatever your system has:

```bash
export PATH="$HOME/Android/Sdk/cmake/3.22.1/bin:$PATH"
```

`llama-cpp-sys` is fussy about this and the error it gives you doesn't say so.

### Build variants

| Variant | Signed with | Minified | Testing features |
|---|---|---|---|
| `debug` | shared debug key | no | on |
| `beta` | upload key | **no** | on |
| `release` | upload key | yes | off |

`beta` is what testers get: release-signed and not debuggable, so it behaves
like the shipped app, but deliberately **not** minified — an R8-mangled stack
trace in a bug report is worth nothing. The trade-off is that it isn't
byte-identical to what ships, so it's a build for finding bugs rather than for
final performance numbers.

"Testing features" means the bug report button, the crash prompt, the
onboarding warning and the audio-capture defaults. They're gated on
`BuildConfig.ARI_TESTING`, not on `DEBUG` — the build testers get is
release-signed, so `DEBUG` is false there and gating on it would hide all four
from exactly the people who need them.

Release signing needs a `keystore.properties` at the repo root. Without one you
get an unsigned APK rather than a failure, which is what a fresh clone wants.

## Layout

```
app/src/main/
├── java/dev/heyari/ari/     # the app — Compose, Hilt, one package per concern
│   ├── wakeword/            # microWakeWord + VAD
│   ├── stt/  tts/           # speech in and out
│   ├── skills/              # install, sandbox, route
│   ├── bugreport/           # the reporter, the scrubber, the crash recorder
│   └── ui/                  # screens and navigation
├── cpp/                     # the microfrontend wrapper the wake word needs
├── assets/                  # wake word models, VAD
└── res/values*/strings.xml  # English is canonical; see CONTRIBUTING.md
```

## Contributing

Read **[CONTRIBUTING.md](CONTRIBUTING.md)** first — it covers the translation
policy (short version: **never machine-translate into a language you don't
read**), the CI parity lint, and PR conventions.

App-side changes go straight to `main`. Only `ari-skills` gates things behind
pull requests.

## Licence

[GPL-3.0](LICENSE).

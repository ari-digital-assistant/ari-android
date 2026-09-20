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

## Contributing recordings

Ari's wake word was trained on a handful of voices in a handful of rooms, which
is why it hears some people better than others. **Settings › Developer ›
Recordings** is where you can help with that, and it is two separate decisions:
what Ari *keeps* on the phone, and what it *shares*. Everything starts off, you
cannot share a category you are not keeping, and uploads happen over Wi-Fi only.

Clips travel with the language you have Ari set to and an anonymous contributor
code — no account, no device model, and nothing linking them to your bug
reports. Keep a copy of that code: it is the only way to ask for your
recordings back, and **Delete my shared data** on that screen is what uses it.
Unlike bug reports these are kept until you ask, because a training set that
expires every 90 days is no use. The whole story, caveats included, is on
[heyari.dev/privacy](https://heyari.dev/privacy/#recordings).

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

**Point the Rust build at the NDK.** The engine's llama.cpp dependency needs
`ANDROID_NDK` explicitly and panics without it, and Gradle can't pass it — its
Rust plugin only sets `CARGO_TARGET_DIR`. So either copy
`ari-engine/.cargo/config.toml.example` to `config.toml` and fill in your path,
or export it:

```bash
export ANDROID_NDK="$HOME/Android/Sdk/ndk/28.0.13004108"
```

If you export it, note the Gradle daemon captures the environment it started
with — run `./gradlew --stop` first, or the change won't be seen.

Only `arm64-v8a` and `x86_64` are built. There's no 32-bit slice because there's
no 32-bit build of the Rust engine, and shipping an ABI without one produces an
app that installs fine and crashes on launch.

### If a build dies in the Rust step

Use the SDK's own CMake rather than whatever your system has — on a
distribution that ships no `cmake` at all, it's the only one present:

```bash
export PATH="$HOME/Android/Sdk/cmake/3.22.1/bin:$PATH"
```

`llama-cpp-sys` is fussy about this and the error it gives you doesn't say so.
If it instead says the Android NDK wasn't found, that's `ANDROID_NDK` above.

A stale `app/build/intermediates/rust` can also bake an old absolute cmake path
into its generated Makefiles, which then fails as `make: /usr/bin/cmake: No such
file or directory` no matter what's on `PATH`. Delete that directory and
rebuild.

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
onboarding warning and the deliberate-crash row in Debug settings. They're
gated on `BuildConfig.ARI_TESTING`, not on `DEBUG` — the build testers get is
release-signed, so `DEBUG` is false there and gating on it would hide all four
from exactly the people who need them. The audio-capture toggles used to be on
this list, defaulting on in testing builds; they now default off in every build
and are the user's to switch on, because they can leave the device.

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

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- On-device assistant answers appear in the chat bubble and the voice overlay word by
  word as the model writes them, and Ari starts speaking as soon as the first sentence
  is finished instead of waiting for the whole answer
  ([#37](https://github.com/ari-digital-assistant/ari-android/issues/37)).

### Fixed

- A model download that gets interrupted now resumes where it stopped. A slow download
  of the medium or large model used to start again from zero every ten minutes and
  never finish. It now shows its progress in a notification, and Settings says when
  it's paused and waiting to resume
  ([#36](https://github.com/ari-digital-assistant/ari-android/issues/36)).

## [0.1.2] - 2026-11-15

Changes since 0.1.1, uploaded to Google Play internal testing as versionCode 3.

### Added

- What's new: the first launch after an update shows a short list of what changed in
  this release, once. Testers no longer need an email for each new build.
- "Take me home" in turn-by-turn mode goes to the home saved in Google Maps when the
  navigation skill has no Home address set.

### Fixed

- The chat's "2 minutes ago" labels follow Ari's language rather than the phone's, so an
  Italian Ari on an English phone says "2 minuti fa"
  ([#35](https://github.com/ari-digital-assistant/ari-android/issues/35)).
- The text box's hint shrinks to fit when it is too long for the box, as the Italian
  one was ([#35](https://github.com/ari-digital-assistant/ari-android/issues/35)).

## [0.1.1] - 2026-11-14

Changes since 0.1.0, the closed-test beta uploaded to Google Play on 2026-11-13
(versionCode 2).

### Added

- Parakeet Ultra, the European speech model: the default on-device speech model for
  25 European languages, Italian among them, and an alternative to Kroko for English
  ([#26](https://github.com/ari-digital-assistant/ari-android/issues/26)).
- Settings lists every on-device speech model that can transcribe your language, marks
  the recommended one, and names the model in each description.
- Phone's speech service: on Android 13 and up, Ari can hand its recording to a speech
  recognition app installed on the phone, such as Marmalade STT, from Settings
  ([#28](https://github.com/ari-digital-assistant/ari-android/issues/28)).
- Personality, in Settings > Conversation: describe how Ari should talk, and the
  on-device or cloud assistant answers in that style
  ([#31](https://github.com/ari-digital-assistant/ari-android/issues/31)).
- How long Ari remembers, in Settings > Conversation: Short (five exchanges, forgotten
  after 90 seconds, as before), Medium (ten, 5 minutes) or Long (twenty, 15 minutes)
  ([#31](https://github.com/ari-digital-assistant/ari-android/issues/31)).
- The lock-screen permission in Settings explains how to allow restricted settings when
  Ari was installed from an APK
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- Settings export and import, in Settings > General and on the first-run wizard's
  welcome screen: a JSON file carries your settings, listening places and installed
  skills. Importing installs the skills and downloads the models the settings use, then
  restarts Ari. API keys and sign-ins are left out
  ([#33](https://github.com/ari-digital-assistant/ari-android/issues/33)).
- The home screen reminds you when your listening places need location access, or when
  your assistant or OpenAI transcription has no API key, with a button to fix it.

### Changed

- With the on-device English speech model, a wake stays hidden and silent until Ari
  has heard its name, as it already did with cloud and Whisper transcription. Things
  that only sound like "Hey Ari" no longer beep or show the banner.
- The home screen's "bare-bones" notice also shows when the only skills installed are
  assistants (ChatGPT, Gemini, Claude).
- File sizes are shown in decimal units everywhere
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- Downloaded models are left out of cloud backups.
- The on-device assistant answers two to three times faster on most phones: Ari now
  picks the build of its maths code that suits the phone's processor, instead of one
  built for the oldest. The Medium model answers in about 3 s rather than 8 on a Pixel
  10 Pro Fold ([#32](https://github.com/ari-digital-assistant/ari-android/issues/32)).
- Settings > Debug shows the device, its chip and memory, which processor build the
  assistant runs, and Ari's versions, with a button to copy them. Bug reports carry
  the chip and the assistant's processor build.
- The message box says whether Ari is listening: "Ari is listening. Say “Hey Ari” or
  type" while the wake word is on, "Ari is not listening. Type your query" while it is
  off. The glow behind it holds steady while Ari waits, and only moves during a turn.
- Speech recognition runs on sherpa-onnx 2.0.1. The wake word now uses ONNX Runtime's
  standard CPU backend, since XNNPACK is no longer in the bundled runtime.
- The on-device assistant runs on a newer llama.cpp (llama-cpp-2 0.1.161).

### Fixed

- The Medium and Large on-device assistants (Gemma 4) answer questions instead of
  saying "Sorry, I didn't understand that".
- The on-device assistant works as soon as its model finishes downloading, instead of
  only after Ari restarts.
- The on-device assistant gives the whole of a list, instead of stopping after its
  opening line ([#30](https://github.com/ari-digital-assistant/ari-android/issues/30)).
- A long conversation no longer makes the on-device assistant fail; it drops the oldest
  exchanges to make room.
- An open, idle Ari no longer uses half a processor core: the glow behind the message
  box redrew the screen 120 times a second, even with nothing happening
  ([#32](https://github.com/ari-digital-assistant/ari-android/issues/32)).
- The on-device assistant gives short answers such as "Paris." or "1945." instead of
  saying it didn't understand
  ([#32](https://github.com/ari-digital-assistant/ari-android/issues/32)).
- Assistant replies, on-device and cloud, no longer show markdown such as `**bold**`;
  bullets show as "•"
  ([#30](https://github.com/ari-digital-assistant/ari-android/issues/30)).
- A wake heard as "Hayari" passes the name check.
- Switching transcription back to On device keeps the speech model you chose, instead of
  returning to the recommended one.
- The chat scrolls to a reply that replaces the thinking dots
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- The glow behind the message box no longer pushes the conversation up on some screens
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- The setup card comes back when the microphone permission is missing, for example
  after a reinstall restored Ari's settings from a backup
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).

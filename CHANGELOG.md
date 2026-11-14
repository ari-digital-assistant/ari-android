# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Changes since the closed-test beta uploaded to Google Play on 2026-11-13
(0.1.0, versionCode 2).

### Added

- Parakeet Ultra, the European speech model: the default on-device speech model for
  25 European languages, Italian among them, and an alternative to Kroko for English
  ([#26](https://github.com/ari-digital-assistant/ari-android/issues/26)).
- Settings lists every on-device speech model that can transcribe your language, marks
  the recommended one, and names the model in each description.
- Phone's speech service: on Android 13 and up, Ari can hand its recording to a speech
  recognition app installed on the phone, such as Marmalade STT, from Settings
  ([#28](https://github.com/ari-digital-assistant/ari-android/issues/28)).
- The lock-screen permission in Settings explains how to allow restricted settings when
  Ari was installed from an APK
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).

### Changed

- With the on-device English speech model, a wake stays hidden and silent until Ari
  has heard its name, as it already did with cloud and Whisper transcription. Things
  that only sound like "Hey Ari" no longer beep or show the banner.
- The home screen's "bare-bones" notice also shows when the only skills installed are
  assistants (ChatGPT, Gemini, Claude).
- File sizes are shown in decimal units everywhere
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- Downloaded models are left out of cloud backups.
- Speech recognition runs on sherpa-onnx 2.0.1. The wake word now uses ONNX Runtime's
  standard CPU backend, since XNNPACK is no longer in the bundled runtime.
- The on-device assistant runs on a newer llama.cpp (llama-cpp-2 0.1.161).

### Fixed

- The Medium and Large on-device assistants (Gemma 4) answer questions instead of
  saying "Sorry, I didn't understand that".
- The on-device assistant works as soon as its model finishes downloading, instead of
  only after Ari restarts.
- A wake heard as "Hayari" passes the name check.
- The chat scrolls to a reply that replaces the thinking dots
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- The glow behind the message box no longer pushes the conversation up on some screens
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).
- The setup card comes back when the microphone permission is missing, for example
  after a reinstall restored Ari's settings from a backup
  ([#24](https://github.com/ari-digital-assistant/ari-android/issues/24)).

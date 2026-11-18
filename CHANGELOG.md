# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.4] - 2026-11-18

Changes since 0.1.3, uploaded to Google Play internal testing as versionCode 6.

### Added

- Saying or typing "clear history", "start over" or "new conversation" clears the chat
  and Ari's memory of it, leaving just Ari's "Done, fresh start." Remembered facts stay
  ([#40](https://github.com/ari-digital-assistant/ari-android/issues/40)).
- Testing builds check twice a day for replies to the bug reports sent from the phone,
  and show a notification that opens the issue when someone has replied.
- Skills > Browse has a "Hide installed" filter, which stays on until you turn it off
  ([#41](https://github.com/ari-digital-assistant/ari-android/issues/41)).
- When you ask for something a store skill could do but you don't have it, such as
  "remind me to call Mum" without the Reminder skill, Ari says which skill can and
  shows a button that opens it in Skills to install. It used to hand the request to
  the assistant, which could only say it can't
  ([#44](https://github.com/ari-digital-assistant/ari-android/issues/44)).
- The on-device assistant's models say how much memory each uses while answering,
  measured on a phone, and how that suits this phone: the page says how much memory the
  phone has and marks the biggest model that fits comfortably as recommended. A model
  that would leave little room for other apps asks before downloading, and one that's
  too big for the phone can't be downloaded. Models already downloaded stay
  ([#42](https://github.com/ari-digital-assistant/ari-android/issues/42)).

### Fixed

- A question with "find" or "search" in it, such as "where do you find them in Europe?",
  goes to the assistant, which knows what "them" means from the conversation. It used
  to open a web search with half the words missing. Searches you ask for ("search for
  X", "look up X") keep every word of what you're searching for
  ([#43](https://github.com/ari-digital-assistant/ari-android/issues/43)).
- Leaving Ari's screen switched the on-device assistant off until Ari restarted, and
  questions asked afterwards weren't answered. Ari meant to free the model's memory when
  the phone runs low, but the check also caught the screen closing, and it removed the
  model rather than freeing it. Ari now frees the memory only when Android asks, and the
  next question loads the model again
  ([#42](https://github.com/ari-digital-assistant/ari-android/issues/42)).
- Navigating quickly, such as opening a skill and backing out straight away, could
  leave a screen frozen halfway off the edge. Taps that land while a screen is still
  sliding in or out are now ignored
  ([#39](https://github.com/ari-digital-assistant/ari-android/issues/39)).
- `/clear` now makes the assistant forget the cleared chat too, instead of answering
  follow-ups about it for up to 15 minutes afterwards
  ([#40](https://github.com/ari-digital-assistant/ari-android/issues/40)).

## [0.1.3] - 2026-11-15

Changes since 0.1.2, uploaded to Google Play internal testing as versionCode 5.

### Added

- On-device assistant answers appear in the chat bubble and the voice overlay word by
  word as the model writes them, and Ari starts speaking as soon as the first sentence
  is finished instead of waiting for the whole answer
  ([#37](https://github.com/ari-digital-assistant/ari-android/issues/37)).

### Fixed

- The medium and large on-device models answer questions like "top 5 footballers"
  instead of saying they can't reach live information, and a question about several
  things gets an answer for every one of them
  ([#37](https://github.com/ari-digital-assistant/ari-android/issues/37),
  [#38](https://github.com/ari-digital-assistant/ari-android/issues/38)).
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

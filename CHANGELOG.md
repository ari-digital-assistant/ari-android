# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
  
## [Unreleased]

### Changed

- Answering a card's button or stopping an alarm by voice, installing and updating
  skills, choosing an assistant, reading what a skill asks Ari to show or do (cards,
  alarms, notifications, actions), deciding what pressing a card's button does, and
  finding a skill's sounds and pictures now run in Ari's engine instead of the app, so
  future desktop versions can share them.
  Nothing should behave differently; if any of them does, please report it.

### Fixed

- Tapping a button on a card in the chat, such as a timer's Cancel, no longer reads Ari's
  reply out loud. It shows in the conversation, as replies to typed questions do.
- Turning the phone sideways while talking to Ari no longer ends the conversation and
  starts a new one that hears Ari's own reply as a false wake. An answer's cards left on
  screen no longer close when the phone is turned, either.

## [0.3.0] - 2026-11-23

Changes since 0.2.2, uploaded to Google Play internal testing as versionCode 10.

### Added

- Sensitivity tuning keeps the screen on while it runs, and beeps: a low beep as each
  prompt appears, and a higher one when it's time to speak.

### Changed

- The wake word, speech recognition and the voice session now run in Ari's engine instead
  of the app, so future desktop versions can share them. Nothing should behave
  differently; if anything about talking to Ari does, please report it.
- The empty chat's greeting picks up a name you asked Ari to remember out loud straight
  away, as it already did for a typed one, rather than the next time the app opens.
- When on-device transcription mishears a request so no skill matches it, Ari now tries
  its second transcript before handing the request to the assistant. "Reminded me to buy
  milk" used to reach the assistant, which can't set reminders; the second transcript,
  "remind me to buy milk", reaches the reminders skill.
- With Ari as your default assistant, the assistant gesture (a swipe up from a bottom
  corner) opens Ari already listening, as other assistants do, instead of opening the
  app.
- The background battery link in Settings opens Ari's own battery page, so setting it to
  Unrestricted is two taps instead of hunting for Ari in a list.
- Listening places asks for background location on Ari's own location permission page,
  instead of Ari's app info page, three taps further away. It also asks for precise
  location together with approximate, as Android 12 and up expect.

### Removed

- The "Wait until Ari hears its name" switch. Ari now always stays hidden and silent
  until it has heard its name. With the switch off, the chime played over the end of
  what you were saying, which could make Ari mishear its own name and ignore you.

### Fixed

- Saying a card's button out loud presses it even when speech recognition adds an ending
  to the word. "Cancel" heard as "cancelled" left a timer running.
- In "let's talk" with "Let me interrupt while Ari is speaking" on, Ari no longer takes
  its own voice for your reply. Some of its answer got past the phone's echo cancelling,
  and Ari would cut itself off and answer what it had just said. Ari's answer also stays
  on screen while it speaks, and the "your turn" chime plays when it has finished rather
  than as it starts.
- Cloud speech-to-text works with a self-hosted server at a plain `http://` address,
  including the default one, `http://homeassistant.local:10300/v1`. Android blocked the
  app's unencrypted connections, so those requests always failed. HTTPS addresses are
  now checked against the same list of certificate authorities as cloud assistants.
- Setup downloads the on-device speech model when you continue, as it already did for
  the assistant model. It used to show a Download button, and continuing without
  tapping it left Ari with no speech model.
- A finished sensitivity tuning now takes effect straight away. Before, Ari kept
  listening with the old setting until the microphone next closed.
- Setting a personality no longer stops the assistant formatting its answers. With one
  set, the on-device assistant wrote lists as plain "•" lines with no bold
  ([#58](https://github.com/ari-digital-assistant/ari-android/issues/58)).

## [0.2.2] - 2026-11-22

Changes since 0.2.1, uploaded to Google Play internal testing as versionCode 9.

### Added

- Skill cards, such as the weather and timers, show in the voice overlay as well as in
  the chat. They stay about 10 seconds after Ari finishes speaking, or until closed once
  touched, and their buttons work there. "Hey Ari" works while a card is showing.
- Assistant answers show their formatting: bold, lists, headings, code and tables, as
  the model wrote them. Long-pressing a formatted answer also offers Copy as Markdown;
  Copy still copies plain text
  ([#57](https://github.com/ari-digital-assistant/ari-android/issues/57)).
- Settings > General has a "Pure black in dark mode" switch, which makes Ari's background
  black when your phone is in dark mode, for OLED screens
  ([#56](https://github.com/ari-digital-assistant/ari-android/issues/56)).

### Changed

- The coloured edge on Ari's replies follows the bubble's rounded top corner instead
  of stopping short of it.

### Fixed

- Long-pressing a message while the keyboard was open closed the keyboard, so the
  message and its menu jumped down the screen mid-press. The keyboard now stays open.
- On Android 10 and 11, which have no wallpaper colours, Ari showed a leftover purple
  theme. It now uses an orange theme taken from its logo.

## [0.2.1] - 2026-11-20

Changes since 0.2.0, uploaded to Google Play internal testing as versionCode 8.

### Added

- Long-pressing one of Ari's replies offers Read aloud, so an answer to something you
  typed can be heard too ([#54](https://github.com/ari-digital-assistant/ari-android/issues/54)).

### Changed

- Testing builds: the bug-report button is smaller, faded until you touch it, and starts
  halfway down the right edge instead of over the banners at the top. The first time it
  appears, a hint says it can be dragged out of the way ([#55](https://github.com/ari-digital-assistant/ari-android/issues/55)).

### Fixed

- Tapping a ringing timer's or alarm's notification opened Ari and left it ringing. The
  tap now stops it as Ari opens ([#53](https://github.com/ari-digital-assistant/ari-android/issues/53)).
- Two timers finishing together couldn't both ring: the second silenced the first,
  whose card then showed it ringing at 0 seconds with a Stop that did nothing. They now
  ring together, with Ari saying each one's name in turn, and each has its own Stop. On
  the lock screen, stopping one shows the other.
- "Set a 10 second timer" asked for a duration, because Ari read "second" as the
  number 2. A number followed by "second" is now seconds; "the twenty second of May" is
  still the 22nd.
- On a locked phone, a ringing timer or alarm put the unlock prompt over its Stop
  button, so you had to unlock before you could stop it. Stop now works straight from
  the lock screen, and the phone stays locked.

## [0.2.0] - 2026-11-19

Changes since 0.1.4, uploaded to Google Play internal testing as versionCode 7.

### Added

- The What's new popup ends with a "Full changelog" button that opens this file on
  GitHub.
- Ask a cloud assistant: a switch under the on-device assistant in Settings, off by
  default. When the on-device assistant doesn't know, or a question needs live
  information, Ari offers to ask the cloud assistant you used last (or another one with
  an API key) and, if you say yes, asks it to search the web for the answer. The switch
  stays off until a cloud assistant has a key.
- Typing `/` in the chat box lists Ari's built-in commands (clear the conversation,
  remember or forget something, forget everything, what Ari remembers about you),
  narrowing as you type. Picking one runs it, or fills it in when it needs more words,
  as `/remember` does.
- When Ari forgets the conversation after a quiet spell (90 seconds on the Short
  setting), a faded note in the chat says so. The messages themselves stay until you
  clear them ([#51](https://github.com/ari-digital-assistant/ari-android/issues/51)).

### Changed

- Ari no longer reads its replies out when you type to it, since you're already looking
  at the screen. Anything you say, including dictation into the chat box, is still
  answered aloud.
- The on-device assistant now runs on Google's LiteRT-LM and answers sooner: on a Pixel
  10 Pro Fold, Small in 1.3 seconds instead of 2.3, Medium in 1.5 instead of 4.3 and
  Large in 4.3 instead of 9, with answers as good. Each tier downloads a new model file,
  offered as an update.
  Medium and Large need far less memory, so Medium now suits 6 GB phones and Large 8 GB.
  The new files take more room on the phone once set up: Small 2.1 GB, Medium 3.4 GB,
  Large 5.9 GB ([#52](https://github.com/ari-digital-assistant/ari-android/issues/52)).
- An experimental "Use the GPU" switch under the on-device models runs the assistant on
  the phone's graphics chip. It's off by default: on a Pixel 10 Pro Fold it answered no
  sooner and took longer to load, but other phones' graphics chips may do better. If the
  model won't load there, Ari uses the processor
  ([#52](https://github.com/ari-digital-assistant/ari-android/issues/52)).
- Ari no longer runs the old on-device models (GGUF files, run by llama.cpp). If your
  tier still has its old file, Ari offers the update and can't answer on the device
  until you take it. The debug screen no longer shows a CPU build
  ([#52](https://github.com/ari-digital-assistant/ari-android/issues/52)).
- The on-device models in Settings and onboarding are easier to compare: each shows
  its download size, the memory it uses while answering, and its speed and accuracy
  as five-step bars, in place of a paragraph of text.

### Fixed

- An answer from the on-device assistant ran on below the screen while it was being
  written, so the newest words had to be scrolled to by hand. The chat now follows it,
  unless you have scrolled up to read.
- The assistants got your words as Ari cleans them up for its skills, with number words
  turned into digits: "who came second?" reached them as "who came 2", and the
  on-device assistant asked what you meant. They now get what you said.
- The on-device assistant forgot its own answers when you pushed back on one, such as
  "I think you are wrong". Ari asked it to mark each reply as starting a new topic or
  not, and a new topic wiped the conversation. Ari no longer asks, so a conversation
  ends only after the quiet spell or when you clear it
  ([#48](https://github.com/ari-digital-assistant/ari-android/issues/48)).
- Ari's replies could show that marker as "[new]", sometimes where your name would go
  ([#46](https://github.com/ari-digital-assistant/ari-android/issues/46)).
- The Medium and Large on-device assistants claimed you had asked things you never did,
  such as what colour the sky is, and stuck to it. They took the example questions in
  their own instructions for yours; those are now marked as examples. Small still can
  do this: marking them made its answers worse
  ([#49](https://github.com/ari-digital-assistant/ari-android/issues/49),
  [#50](https://github.com/ari-digital-assistant/ari-android/issues/50)).
- A sentence with a number and "times" in it, such as "you repeated cheetah 3 times",
  went to the calculator, which said it couldn't work it out. The calculator now only
  answers a sum it can do or one you asked for
  ([#47](https://github.com/ari-digital-assistant/ari-android/issues/47)).
- "Search for it" searched the web for the word "it". It now searches for the question
  you just asked, and with nothing to go on, Ari asks what to search for
  ([#51](https://github.com/ari-digital-assistant/ari-android/issues/51)).

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

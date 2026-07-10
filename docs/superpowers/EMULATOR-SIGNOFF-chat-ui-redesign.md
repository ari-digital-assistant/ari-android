# Chat UI Redesign — Emulator Sign-off Checklist

All code + unit tests + compile are verified. This is the **visual/behavioural** pass that couldn't be automated (no Compose UI test harness). Run on **emulator-5554** (not the Pixel). Build: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -Dorg.gradle.java.installations.auto-detect=false :app:installDebug`.

Tick each; note anything off.

## Typography
- [ ] Whole app renders in **Manrope** (chat, settings, cards). Weather card's monospace countdown digits unaffected.

## Bubbles ("Ari with a face")
- [ ] User bubbles: accent container, tail on bottom-right of the group's last message.
- [ ] Ari bubbles: the `A:` avatar shows on the **newest** message of a group; a 3dp accent edge runs down the left; earlier grouped rows indent consistently (no horizontal jump).
- [ ] Consecutive same-sender messages tuck together (tight spacing); a quiet centered **timestamp** appears only after a real time gap.
- [ ] Attached rich cards (e.g. weather) still render, indented under Ari.

## Empty state (adaptive)
- [ ] **Fresh install / 0 skills:** "Hi, I'm Ari 👋 … bare-bones" + a **Browse skills** card (tapping it opens the skills browser) + "…or just type below".
- [ ] **With skills installed:** greeting + suggestion **chips built from real skill examples**. Tapping a chip (e.g. "set a reminder") **submits it** and starts a real turn.
- [ ] Name unknown → neutral "Hi, I'm Ari" **and** a **"Remember my name"** chip present. After teaching your name → time-aware "Good morning, Keith", chip gone.
- [ ] ⚠️ *Reviewer flag:* on a returning user WITH skills but an empty log, watch for a brief **FirstRun flash** before the async read lands. Note if it's noticeable.

## Ambient Field (presence)
- [ ] **Idle:** faint slow breath at the base.
- [ ] **Listening** (start a voice turn): input-bar border/aura livens.
- [ ] **Thinking** (type a slow query, e.g. a cloud-assistant question): dots-bubble appears where the reply lands; aura shimmers.
- [ ] **Speaking:** aura pulses while Ari talks.
- [ ] ⚠️ *Reviewer flag:* the aura reserves ~120dp above the composer — confirm the **balance** looks right, not a big empty band.

## "Still working" dual-channel (the important one)
- [ ] Type a query that takes >4s. **Visual:** the three-dots indicator appears (NOT a "please wait" text bubble). **Audio:** you still **hear** the spoken filler. When the reply lands, the dots vanish and **no filler bubble is left in the log** (scroll back to confirm).
- [ ] ⚠️ *Reviewer flag:* auto-scroll targets the last real message — confirm the dots indicator is still visible (not just below the fold).

## Chrome
- [ ] Composer: mic icon when empty; **swaps to a send arrow** the moment you type.
- [ ] "Hey Ari" wake control is a **Switch**: OFF = grey/mic-off; ON = **steady** (non-pulsing) lit halo. The old "Not listening / Listening" text is gone.

## Tap-to-talk (new feature — exercise carefully)
- [ ] **Always-listening ON:** tap the composer mic → a voice turn starts (overlay appears) without saying "Hey Ari"; after the turn, the switch **stays on** (still listening).
- [ ] **Always-listening OFF:** tap the mic → permission prompt if needed → a one-shot voice turn; after it ends, the mic **stands down** (switch stays OFF, no lingering mic).
- [ ] **Stress it:** double-tap the mic fast on a cold start; tap during an active turn. Confirm the mic never gets **stuck hot** and the switch never turns itself **on** by itself. (This was a fixed Critical — verify the fix holds.)
- [ ] If permission denied, the turn doesn't start and there's no crash.

## Motion
- [ ] New messages **spring-rise** in (gentle overshoot), not a jarring bounce.
- [ ] **Reduce-motion:** in Developer Options set "Animator duration scale" = Off. Re-open chat: messages appear **instantly** (no spring), the aura is **static**, and the thinking dots are **static** (no blink).

## Dynamic Material You
- [ ] Change the device wallpaper → the chat recolours from it (colour is intentionally still wallpaper-driven).

---
When done, tell Claude which items pass/fail. Green items ship; any fails become follow-up fixes before push.

# Listening Modes — Design

**Date:** 2026-08-06
**Component:** ari-android
**Status:** Implemented

## Problem

Ari listens 24 hours a day and costs roughly 15% of a Pixel 10 Pro Fold's
battery doing it. The four optimisations already landed — bigger audio buffers,
30 ms reads, zero per-frame allocation, a dedicated capture thread — are
percentages off that number. They shave the cost of an hour of listening.

This one is a multiple. Most people are asleep for eight of those hours, and
their phone is in a pocket for another eight. Listening for three hours a day
instead of twenty-four is not a 10% saving, it's a 7× one, and no amount of
buffer tuning gets near it.

## The platform constraint that shapes everything

The obvious design — "when the charger goes in, start the listening service" —
is unbuildable on Android 14 and later.

A `microphone` foreground service is subject to the **while-in-use**
restriction. From
[the docs](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions):

> If your app is in the background, and it tries to create a foreground service
> of type camera, location, or microphone, the system sees that your app doesn't
> currently have the required permissions, and it throws a `SecurityException`.

The while-in-use exemption list is short and we are not on it. Critically, the
exemptions we *do* hold are the wrong ones —
[the troubleshooting page](https://developer.android.com/develop/background-work/services/fgs/troubleshooting)
is explicit that the system throws

> even if the app has an exemption to start a foreground service from the
> background.

So `SYSTEM_ALERT_WINDOW` (which we hold, for BAL), battery-optimisation
exemption, and exact-alarm callbacks all buy us nothing here. Android 15
restates the same rule for `BOOT_COMPLETED`, which is why `BootReceiver` already
posts a tap-to-start notification on API 34+ instead of starting the service.

**What we can do instead:** a microphone FGS that is *already running* keeps
`PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` for its entire life, derived from its
declared `foregroundServiceType` and recomputed on every oom-adj pass with no
reference to app visibility (`OomAdjuster.java`, AOSP `main`). Once
`ActiveServices.setFgsRestrictionLocked` latches the allowance it is only
cleared when the service leaves foreground state. So the service can release its
`AudioRecord` and create a new one, in the background, indefinitely.

And the microphone privacy indicator follows the *app-op*, not the service — it
clears on `finishOp(RECORD_AUDIO)` after a 5–10 s minimum hold
([AOSP privacy indicators](https://source.android.com/docs/core/permissions/privacy-indicators)).
Standing down genuinely turns the green dot off, which is exactly the honesty we
want from a feature whose entire pitch is "Ari isn't listening right now".

### Therefore

**The service stays resident. The microphone cycles.**

A resident FGS with no open `AudioRecord` and no detector costs essentially
nothing — the whole measured cost is the mic, the HAL wake-ups and the
inference. We keep the full multiple, and we never have to start a mic FGS from
the background, because we never stopped it.

The one thing we must never do is call `stopForeground()`. The moment we do, the
capability is gone and only a visible activity can get it back.

## Modes

```
Always   listen whenever the service is up          (default)
Never    never listen; summon Ari by hand
Custom   listen when ANY ticked condition holds
           1. screen is on
           2. charging
           3. a headset is connected
           4. on a schedule
           5. at specific places
```

Always and Never are exclusive — picking either clears the custom conditions
from the UI. Custom with nothing ticked never listens; the settings page says so
plainly rather than pretending otherwise.

### Mode → service lifecycle

| state | service | mic |
| --- | --- | --- |
| Always | resident | hot |
| Never | **stopped** | — |
| Custom, a condition holds | resident | hot |
| Custom, no condition holds | resident | **cold** |

Never stops the service outright, so the notification goes away too. A
persistent "standing by" notification for a user who has explicitly turned
listening off would be obnoxious. It is only ever entered from the foreground
(a settings tap, a top-bar tap), and only ever *left* from the foreground, so
restarting the FGS is always legal.

## The top-bar control

The top-bar `ListeningModeSwitch` is a three-segment icon-only control —
mic-off / calendar / mic — that sets [ListeningMode] directly:

| segment | icon | sets |
| --- | --- | --- |
| off | `MicOff` | `NEVER` |
| on a schedule | `CalendarMonth` | `CUSTOM` |
| always | `Mic` | `ALWAYS` |

There is deliberately no separate pause flag layered on top of the mode (an
earlier draft of this design had one, keyed off a `listeningPaused` boolean —
retired once the segmented control replaced the original two-state switch).
Tapping the off segment sets `NEVER` outright, but that costs nothing: it never
touches the stored Custom conditions, schedules or places, so tapping the
calendar segment afterwards restores exactly what was configured. One
persisted source of truth (`ListeningMode`) rather than two that could
disagree.

It survives a reboot, which the original two-state switch did not: at present
"always listening" was nothing but "the service happens to be running", so the
state was lost on every process death.

## Architecture

```
                     ┌─ screen on/off ───────┐
                     ├─ power connected ─────┤
  SettingsRepository ┼─ AudioDeviceCallback ─┼─→ ListeningController ─→ WakeWordService
   mode, conditions, ├─ ScheduleAlarms ──────┤   (pure decideListening)   hot ⇄ cold
   schedules, places ├─ PlaceGeofences ──────┤                            notification
                      └───────────────────────┘
```

`ListeningController` is a `@Singleton` exposing a single
`Flow<ListeningDecision>`. Each condition source is a `callbackFlow`, so
registration and teardown ride the collector's lifecycle — the receivers exist
only while the service is collecting, and there is nothing to leak.

The decision itself is a framework-free function, tested directly, per the house
pattern set by `LocationLogic`, `VoiceTurnDecision` and `EmptyStateLogic`:

```kotlin
internal fun decideListening(
    mode: ListeningMode,
    conditions: Set<ListeningCondition>,
    signals: ConditionSignals,
): ListeningDecision
```

`ListeningDecision` carries the boolean *and* the standby reason, because the
notification has to say something truthful about why Ari has gone quiet.

### Condition sources

**Screen** — runtime-registered receiver for `ACTION_SCREEN_ON` /
`ACTION_SCREEN_OFF` (these cannot be manifest-registered), seeded from
`PowerManager.isInteractive`. Screen on while locked counts as on.

**Charging** — `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`, seeded
from a one-shot sticky `ACTION_BATTERY_CHANGED` read. Not a `BATTERY_CHANGED`
receiver: that fires constantly and we'd be burning battery to save battery.

**Headset** — `AudioManager.registerAudioDeviceCallback`, not
`ACTION_HEADSET_PLUG` plus a pile of Bluetooth intents. One platform API covers
wired, USB, BT SCO/A2DP, BLE and hearing aids, needs no permission, and doesn't
break when the next audio transport ships (antislop 3). Counted as connected:
`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`,
`TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`,
`TYPE_HEARING_AID`. A2DP is in deliberately — a car stereo is exactly when you
want to talk to Ari.

**Schedule** — `AlarmManager`, one alarm at a time, set to the next boundary.
Firing recomputes the state and arms the next one. Re-armed on
`ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` and `ACTION_DATE_CHANGED`.
Follows the `CardAlarmScheduler` pattern including the `SecurityException`
fallback to inexact; the app already holds `USE_EXACT_ALARM` and
`SCHEDULE_EXACT_ALARM`.

The maths is pure and tested: `isWithinAnySchedule(now, schedules)` and
`nextBoundaryAfter(now, schedules)`. Windows that cross midnight (22:00→06:00)
belong to the day their *start* falls on, which is what people mean when they
say "Friday night".

**Places** — Play Services `GeofencingClient`. Requires
`ACCESS_FINE_LOCATION` *and* `ACCESS_BACKGROUND_LOCATION`; coarse is not
sufficient. Registered with `INITIAL_TRIGGER_ENTER` so arming reports
immediately if we're already inside, and with
`setNotificationResponsiveness(5 min)` — the docs are explicit that a larger
value saves significant power, and five minutes is nothing against a listening
window measured in hours. Geofences are registered when the service starts and
removed when it stops; there is no point holding them while we're incapable of
acting on them.

Latency is 2–6 minutes and the API is network-location-only. That's the deal,
and it's fine for "start listening when I get home".

Geofences are lost on reboot. Since the service can't restart itself after a
reboot on API 34+ anyway, they're re-registered whenever the service comes up.

## Permissions

This feature adds three manifest permissions: `ACCESS_FINE_LOCATION`,
`ACCESS_BACKGROUND_LOCATION`, and (for the geofence receiver) nothing else.

**This changes an existing promise.** `LocationProvider`'s header comment
currently reads "Coarse only — never requests fine location." Once fine is
granted for geofencing it is granted app-wide, so a weather skill that asked for
coarse will also be able to read a fine fix. That comment gets corrected rather
than quietly left to rot, and `LocationProvider` keeps requesting
`PRIORITY_BALANCED_POWER_ACCURACY` so it doesn't *use* the extra precision.

Fine location is requested only when the user opens the Places screen —
capability-driven and at the honest moment of consent, matching how skill
installs already request coarse (`SkillDetailScreen`).

Background location **cannot** be obtained from a runtime dialog on Android 11+.
[The docs](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
are explicit: the system dialog has no "Allow all the time" option and the user
must enable it on a settings page. The flow is therefore: grant fine first, then
an explanatory card naming the exact option using
`PackageManager.getBackgroundPermissionOptionLabel()` (which is localised by the
platform, so we get it right in every language for free), then a deep link to
app settings. With a clear decline path — places simply don't work, and the
screen says so.

If Play Services is absent, the Places condition is shown as unavailable on this
device rather than silently doing nothing. `LocationProvider` already checks
`GoogleApiAvailability` this way.

## Data model

All persisted in `SettingsRepository` alongside everything else. Lists are JSON
arrays via `org.json`, matching the `rememberedFacts` precedent — the project has
no serialisation library and this doesn't justify adding one.

```kotlin
enum class ListeningMode { ALWAYS, NEVER, CUSTOM }        // slug-persisted
enum class ListeningCondition { SCREEN_ON, CHARGING, HEADSET, SCHEDULE, PLACE }

data class ListeningSchedule(
    val id: String,
    val days: Set<DayOfWeek>,
    val startMinute: Int,   // minutes from midnight
    val endMinute: Int,
)

data class ListeningPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Float,   // 100 m floor, per the geofencing docs
)
```

Default mode is `ALWAYS` — that is today's behaviour, and an upgrade must not
silently stop listening on someone.

## UI

**Settings → Listening** (new row in the existing "Listening" section, above
Wake word): three radio cards for the modes, then five condition rows when
Custom is picked. Schedule and Places rows carry a count and navigate onward.

**Settings → Listening → Schedules**: a list of windows with add/edit/delete.
Editing is a dedicated full-screen destination, not a dialog — a dialog's
fixed width forced Mon–Sun onto a scrolling row, which read as broken rather
than compact. Day selection is a `FlowRow` of `FilterChip`s (wraps, never
scrolls); times use Material3's `TimePicker` inside a small confirm dialog,
which is the right size for a single time field. Both are in the stable
material3 1.4.0 the project already resolves — verified against the resolved
AAR, not assumed.

**Settings → Listening → Places**: a list of places with add/edit/delete, a
permission gate at the top, and a map picker with a radius slider. Also a
dedicated full-screen destination rather than a dialog, so the map gets
`weight(1f)` of real screen space instead of a cramped fixed-height box. Two
circles share the same `CenterState`: a translucent one sized in real metres
(via `metresToPixelRadius`) showing the geofence's actual ground area, and a
small solid dot on top marking the exact centre — the translucent circle alone
didn't read as "here is the precise point" at a glance.

**Onboarding**: a new Listening step right after Wake word (step 5), with the
same mode cards and condition checkboxes as the Settings page — ticking
Schedule or Places here is allowed without configuring one, same as Settings.
This pushed STT and Assistant from 5/6 to 6/7, filling the wizard's old hole at
step 7; `TOTAL_STEPS` stays 9.

**Post-wizard reminder**: picking Schedule or Places in the wizard, without
configuring any, sets `pendingListeningSetup`. The conversation screen shows a
card exactly like `CloudAssistantSetupCard`, cleared once a schedule or place
exists — or once the condition is unticked. This only fires in practice
because the onboarding screen exposes the condition checkboxes at all — an
earlier draft that showed only the three mode cards left no way to tick
Schedule/Places during onboarding, so the reminder could never trigger.

## Map picker

MapLibre Native (`org.maplibre.gl:android-sdk:13.0.2`, via `ramani-maplibre:0.13.0`,
BSD-2/MPL-2.0) against OpenFreeMap's `liberty` style — keyless, no per-load
billing, commercial and mobile use explicitly permitted in writing.

Google Maps was ruled out even though the native Android SDK is genuinely free
and unlimited (SKU `6DE1-4D9C-5B67`): it needs an API key restricted by
package + signing certificate, so anyone who rebuilds and re-signs Ari — not
just an F-Droid build, any contributor building locally with their own debug
key — gets a silent grey map. `play-services-location` already being an
unconditional dependency doesn't change that calculus: it has a working
platform-`LocationManager` fallback baked in; a key-gated map has no fallback
at all.

osmdroid was ruled out on inspection: the GitHub repo was archived on
2024-11-20, no successor fork exists, and it has never been built against
API 35/36.

The style URL lives in one constant
(`ListeningPlacesPage.MAP_STYLE_URL`), not scattered inline, so swapping to
VersaTiles if OpenFreeMap's single-operator, donation-funded service ever goes
dark is a one-line change.

The drawn circle is a visual aid, not the source of truth: MapLibre's
`circle-radius` paint property is in screen pixels, not metres, so the radius
in metres is converted to pixels from the live zoom and latitude on every
recomposition (`metresToPixelRadius`). The persisted [ListeningPlace.radiusMetres]
is what actually feeds the geofence.

## Testing

Pure and directly tested: `shouldListen` across every mode/condition
combination; `isWithinAnySchedule` and `nextBoundaryAfter` including
midnight-crossing, multi-day and empty cases; the headset device-type
predicate; JSON round-trips for schedules and places. Exact assertions, no
mocking library — the project has neither MockK nor Robolectric and this
doesn't change that.

Not unit-tested, needs a device: geofence transitions, alarm firing under Doze,
and that the mic actually goes cold and the green dot clears.

## Follow-ups

- **Italian copy** was drafted alongside the English under the repo's existing
  one-time exception (`values-it/strings.xml`'s own header: AI-drafted, native
  speaker verifies) — spot-check before the next release, same as every other
  string that landed under that arrangement.
- **Device testing** — everything here compiles and the pure logic is unit
  tested, but geofence transitions, exact-alarm firing under Doze, and the mic
  actually going cold with the privacy indicator clearing all need a real
  device pass.

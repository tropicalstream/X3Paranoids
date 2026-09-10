# X3Paranoids

<img src="art/icon-512.png" width="128" align="right" alt="X3Paranoids icon: a phosphor-green wireframe Recognizer inside the tank sight's brackets">

A first-person vector tank in a maze of light, hunting Recognizers — a 1982 arcade-cabinet homage
to Tron's *Space Paranoids*, for the RayNeo X3 Pro glasses. Your **head is the periscope**, the
temple pad is the hull, a tap is the cannon. Green phosphor wireframes on black, a stroke-font HUD,
two voices that talk to each other, and the IO Tower cover looping underneath.

> Contains flashing lights (damage flashes, lock-on warnings, muzzle flare, derez, capture). Stop
> if you feel unwell.

Kotlin + OpenGL ES 3, zero dependencies, zero permissions, no network. Package `com.x3paranoids`.

## How it plays

- **Look around by moving your head.** The game-rotation sensor drives the periscope; the maze
  stays world-locked. Tapping to start (or the first tap of a run) sets "forward" to wherever
  you're looking, level. Triple-tap re-centres at any time.
- **Swipe up / down** on the right temple pad drives forward / backward. A quick **flick** is a
  dash — one impulse, exactly like the old single-swipe drive. **Hold your finger down** after the
  swipe and the tank keeps driving in that direction, ramping smoothly up to top speed, until you
  lift — a cruise, not a series of taps.
- **Swipe left / right** quarter-turns the hull 90°, eased rather than snapped. The maze is built
  on right angles, so a turn is always exactly one gesture.
- **Tap** = fire. Shells fly where the sight points, ricochet off walls, and can shoot an incoming
  Recognizer disc out of the air.
- **Recognizers** patrol the corridors in green, sweeping and pausing at junctions — they're always
  hunting, even when they haven't found you. One that sees you turns **red** and must physically
  swing its cab to face you before it can fire (**TRACKING** = still turning, you can still move;
  **WARNING** = it has you, move *now*). Its shot is a spinning disc that grows as it closes.
- **Get too close and a Recognizer captures you** — the Tron stomp: it rises, comes down on the
  tank from above, and its legs rotate in together beneath the cab. The camera pulls back to watch
  the whole thing from outside. Break the clamp by tapping fast enough, or it carries the tank off
  and drops it elsewhere in the maze, a life down. On your last life, a capture ends the game
  outright — the tank is taken, not destroyed.
- **Energy pools** slowly refill, and drawing from one restores your three-charge energy shell to
  full — then **the well surfaces somewhere else**, at least four cells away and away from the Bit.
  A well that stayed put turned the shell into a timer: drink, kill time, walk back down a corridor
  you already know. Moving it asks "energy or the Bit?" fresh every time, at the moment the answer
  is hardest — you have just stood still for a second at a known point with every machine walking a
  path to you. The shell absorbs a capture attempt too, at a cost.
- **The Bit** hides somewhere in the maze every wave (*FIND THE BIT*): touching it is +500 and an
  extra life (max 5). It chatters yes/no while you look for it, and the minimap gives it a distance
  bearing once you're close.
- The **minimap** (top-right) is a threat display, not omniscience: Recognizers hunting or nearby
  show as solid marks; others leave a dim, stale "ghost" at their last-known cell so the plate never
  reads as broken even when nothing is close enough to track live. `CONTACTS n/m` states exactly
  how many of the wave's machines the plate is actually showing.
- Clear every Recognizer to clear the wave; every third wave the maze regenerates. Later waves add
  Recognizers, speed, fire rate and armour, and the walls drift from phosphor green toward white.
- 3 lives. Score, best wave and total games are remembered.

## Controls (right temple pad)

| Gesture | In play | In the settings menu |
|---|---|---|
| Tap | fire (or fight free of a capture) | adjust / toggle the selected row |
| Swipe up / down, flick | dash forward / backward | move the selection |
| Swipe up / down, hold | cruise forward / backward until released | — |
| Swipe left / right | quarter-turn the hull 90° | adjust the selected value |
| Double-tap | — (settings are off the arena; see below) | close |
| Triple-tap | re-centre the head | — |

There is no long-press (the glasses reserve it for the system shade). The left temple pad is the
system volume pad and is ignored.

## Settings (double-tap — on the title or the game-over card only)

The menu is **not** available while a run is live. A tap fires, so in a firefight two fast shots are
indistinguishable from the double-tap that means "pause" — the game could stop dead because you shot
twice quickly. Everything the menu holds is a between-runs decision anyway.


Music · Volume · Voice · Head Look (off = left/right swipes turn the hull instead) · Turn (Normal /
Reversed — the pad's horizontal sign is a matter of taste) · Difficulty (Easy / Normal / Hard) ·
Reset Settings (confirm twice). Records are kept on reset.

## The two voices

Every system line is pre-rendered by `tools/generate_voice.sh`: macOS's **Zarvox** synthesiser
voice pushed through a ring-modulator / echo / bit-crusher chain in ffmpeg — flat, machine,
indifferent. It's answered by a second voice, **the pilot** — the program who wrote the game that
was stolen, talking back — rendered through a real TTS model via `tools/generate_hero_voice.py`
(fish.audio's free developer tier). The two are scheduled so they never talk over each other; the
pilot's lines are rate-limited so it never becomes wallpaper. "Monopoly Control Protocol" is this
universe's name for the villain.

## Music

`app/src/main/assets/music/io_tower.mp3` — the IO Tower cover shared with x3cycles, looping under
the title and the maze.

## Build and install

Requires JDK 17, the Android command-line tools (`sdk.dir` in `local.properties`) and the bundled
Gradle wrapper (Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21; compileSdk 35, minSdk 29).

```sh
cd X3Paranoids
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.x3paranoids/.MainActivity
```

The app declares itself to the RayNeo launcher (`com.rayneo.mercury.app`, category
`com.rayneo.intent.category.AR_APP`) and renders a 1280×480 side-by-side surface (the left eye is
the left 640 px of a screenshot). The launcher icon and `art/icon-512.png` are rendered by
`tools/make_icon.py`.

To regenerate the pilot's voice lines yourself, put a fish.audio API key in `tools/fish.config`
(gitignored — see `tools/generate_hero_voice.py` for the format) and run the script; it uses the
free tier and falls back to a local macOS voice if no key is present.

tropicalstream · no network, no permissions.

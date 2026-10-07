# Rocket Launcher

Android rocket game (Kotlin, Jetpack Compose UI, OpenGL ES 3 renderer). Built from Rocket Ascent v2.
Free, no ads, no internet permission, no tracking.

**New here? Read [0_START_HERE.md](0_START_HERE.md)** — how to build the signed update and publish it on Google Play.

**1.2 (versionCode 4)** — current. Earlier: 1.1 (versionCode 2; Google Play build versionCode 3), 1.0 (versionCode 1).

## What the game is

- **Auto launcher:** the rocket flies itself. You research upgrades in the tech tree and press LAUNCH. The
  autopilot burns at full throttle (stages/boosters separate automatically); the Digital Flight Computer throttles
  through max-Q; Deep-Space Navigation cuts the engines once the coast reaches the target, saving fuel for the
  capture and landing burns. Hazards are avoided by the autopilot: radar/guidance/AI decide how far ahead it sees,
  fins/gimbals/RCS how hard it can turn, hull upgrades how many hits it survives. Hazard layouts are deterministic
  per flight number, so the outcome depends only on upgrades.
- **One mission, 9 steps:** space → orbit → past the Moon → Mars transfer → Mars orbit → Mars landing →
  past Mars into the asteroid belt → Jupiter orbit → Europa landing. No destination picker: flights target Mars until
  the first Mars landing, then every flight continues past Mars (a visible flyby) to Jupiter.
- **Real distances:** Moon 384,400 km, Mars 55 million km (close approach), Jupiter 588 million km (close approach).
  A Mars flight takes ~2 minutes of real time, a Jupiter flight ~2.5 minutes (time warp).
- **Money** comes only from height/distance (altitude pay, distance pay past Mars, one-off milestones). No coins or
  collectibles.
- **Hidden bonus:** 16.769% (`Economy.INTEREST_RATE`) on money left unspent at launch is folded into each launch's
  "Flight performance" pay. Never shown or named in the game. Exact BigDecimal math; whole dollars are paid (rounded
  down) and the sub-dollar remainder is carried in the save to the next payout.
- **Tech tree:** 80 upgrades over 12 tiers. Tiers 1–6 Earth/Moon, 7–9 the Mars program, 10–12 the Jupiter program
  (opens after the first Mars landing).
- **Upgrades change the rocket's look**, including the exhaust: solid motor → kerosene (gas-generator soot trail) →
  staged combustion → full-flow → blue methalox clusters (1/3/9/13/20/33 engines); upper stages from vacuum hydrolox to
  nuclear to a violet fusion-plasma beam. Plus gimbals, grid fins, RCS/ion thrusters, solar wings that unfold in space,
  RTGs, a gold-foil radiation vault, the Europa lander and sky-crane, Whipple shields, graphene glow bands and more.
- **Mute button** (🔊/🔇) on every screen and in the pause menu; Settings keeps the sound switch and graphics quality
  (Low / Medium / High). All visuals and audio are generated in code.

## Pacing (simulated, `ProgressionTest`)

A player who always buys the cheapest upgrade: past the Moon at flight ~31 (25–35 required), Mars landing ~54,
Jupiter ~83, Europa landing ~89. Saving money pays off later because of the hidden bonus (Europa ~66).

## Saves (progress carries across updates)

Package `com.brandonwallace.rocketlauncher`, SharedPreferences file `rocket_launcher`, key `save` — never change these.
Saves carry a schema version: `v=4` (1.2). Older saves are migrated on load without losing money, upgrades,
milestones or badges (1.0 = `v=2`, 1.1 = `v=3`); unknown keys from newer builds are preserved; the untouched
pre-migration save is kept once as `save_backup_v<n>`. The save is written on every change and synchronously on
pause/stop, and an unreadable save is never overwritten with a blank one.

## Build

- Debug: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Tests: `./gradlew testDebugUnitTest` (43 tests: economy, saves/migrations, progression, a headless engine +
  renderer smoke test that flies whole campaigns to the Moon, Mars and Europa).
- Release (Google Play): `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, signed with the
  Play **upload key**, read from an external properties file (`-ProcketLauncherKeystoreProps=<path>`, env
  `ROCKETLAUNCHER_KEYSTORE_PROPS`, or the default `C:/Users/Brandon/AndroidKeys/rocketlauncher-keystore.properties`).
  Keystores and that file are never committed. Play App Signing is used.

## Store listing

Ready-to-paste Play Store text and the screenshot shot list are in [`store-listing/`](store-listing/).

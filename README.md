# Rocket Launcher

Android rocket game (Kotlin, Jetpack Compose UI, OpenGL ES 3 renderer). Built from Rocket Ascent v2.
Free, no ads, no internet permission, no tracking.

**New here? Read [0_START_HERE.md](0_START_HERE.md)** — how to build the signed update and publish it on Google Play.

**1.3 (versionCode 5)** — current. Earlier: 1.2 (versionCode 4), 1.1 (versionCode 2; Google Play build versionCode 3),
1.0 (versionCode 1).

## What the game is

- **Auto launcher:** the rocket flies itself. You research upgrades in the tech tree and press LAUNCH. The
  autopilot burns at full throttle (stages/boosters separate automatically); the Digital Flight Computer throttles
  through max-Q; Deep-Space Navigation cuts the engines once the coast reaches the target, saving fuel for the
  capture and landing burns. Hazards are avoided by the autopilot: radar/guidance/AI decide how far ahead it sees,
  fins/gimbals/RCS how hard it can turn, hull upgrades how many hits it survives. Hazard layouts are deterministic
  per flight number, so the outcome depends only on upgrades.
- **One mission, 18 steps in two chapters.** To Europa (1.2): space → orbit → past the Moon → Mars transfer →
  Mars orbit → Mars landing → asteroid belt → Jupiter orbit → Europa landing. The Saturn chapter (1.3): Jupiter
  slingshot → halfway to Saturn → 1 billion km → reach Saturn → cross the rings → Saturn orbit → Enceladus plumes →
  Titan probe → fly on Titan. No destination picker: flights target Mars until the first Mars landing, then Jupiter
  until the first Europa landing, then Saturn (past Mars and a Jupiter flyby on the way).
- **Real distances:** Moon 384,400 km, Mars 55 million km (close approach), Jupiter 588 million km, Saturn
  1.2 billion km (2.04× Jupiter). A Mars flight takes ~2 minutes of real time, Jupiter ~2.5 minutes, Saturn ~5 minutes
  (time warp).
- **1.3 doubled the game in proportion to the real distance:** Saturn is 2.04× as far as Jupiter, so the mission
  steps (9 → 18), upgrades (80 → 163), tiers (12 → 24), milestones (17 → 35), badges (14 → 29) and flights to finish
  (~89 → ~182) all roughly double, and Saturn pays 2.04× Jupiter.
- **Money** comes only from height/distance (altitude pay, distance pay past Mars, one-off milestones). No coins or
  collectibles.
- **Hidden bonus:** 16.769% (`Economy.INTEREST_RATE`) on money left unspent at launch is folded into each launch's
  "Flight performance" pay. Never shown or named in the game. Exact BigDecimal math; whole dollars are paid (rounded
  down) and the sub-dollar remainder is carried in the save to the next payout.
- **Tech tree:** 163 upgrades over 24 tiers. Tiers 1–6 Earth/Moon, 7–9 the Mars program, 10–12 the Jupiter program
  (opens after the first Mars landing), 13–24 the Saturn program (opens after the first Europa landing): Jupiter
  slingshot upgrades, solar/ion/electric/magnetic/laser sails for the cruise, Next-Gen RTGs, a ring-plane dust
  shield, aerocapture, a plume mass spectrometer, a Titan aeroshell + Huygens probe and the Dragonfly rotorcraft,
  plus 7- and 8-stage stacks, six heavy side cores, 42- and 60-engine detonation clusters, and upper-stage drives
  from direct fusion up to an antimatter beam core.
- **Saturn physics:** past Jupiter the Sun's pull falls off with distance, a Jupiter flyby adds a slingshot kick,
  and sails/ion engines give a gentle cruise push; the nav computer's coast prediction includes both. Tanks only fill
  as far as the engines can still lift off (thrust-to-weight 1.1), so engines and tanks have to grow together.
- **Hangar forecast:** flies the coming launch in a throwaway engine (same rocket, same flight number, so the same
  hazards and the same fuel spent dodging them), so it shows exactly what LAUNCH will do. The offline
  `simulateAutopilot` (no hazards) is kept for the balance tests.
- **Upgrades change the rocket's look**, including the exhaust: solid motor → kerosene (gas-generator soot trail) →
  staged combustion → full-flow → blue methalox clusters (1/3/9/13/20/33 engines, then 42/60 rotating detonation engines); upper stages from vacuum hydrolox to
  nuclear to a violet fusion-plasma beam. Plus gimbals, grid fins, RCS/ion thrusters, solar wings that unfold in space,
  RTGs, a gold-foil radiation vault, the Europa lander and sky-crane, Whipple shields, graphene glow bands and more.
  1.3 adds new hardware for every Saturn upgrade (sails that unfold in space, ring shield collar, Huygens saucer,
  Dragonfly rotorcraft, 8-stage stacks …) and a real-time Saturn with its rings, Titan and Enceladus in the sky.
- **Mute button** (🔊/🔇) on every screen and in the pause menu; Settings keeps the sound switch and graphics quality
  (Low / Medium / High). All visuals and audio are generated in code.

## Pacing (simulated, `ProgressionTest`)

A player who always buys the cheapest upgrade: past the Moon at flight ~31 (25–35 required), Mars landing ~54,
Jupiter orbit ~84, Europa landing ~89, Saturn ~148, flying on Titan ~182. Saving money pays off later because of the
hidden bonus (Europa ~66, Titan ~115).

## Saves (progress carries across updates)

Package `com.brandonwallace.rocketlauncher`, SharedPreferences file `rocket_launcher`, key `save` — never change these.
Saves carry a schema version: `v=5` (1.3). Older saves are migrated on load without losing money, upgrades,
milestones or badges (1.0 = `v=2`, 1.1 = `v=3`, 1.2 = `v=4`); unknown keys from newer builds are preserved; the untouched
pre-migration save is kept once as `save_backup_v<n>`. The save is written on every change and synchronously on
pause/stop, and an unreadable save is never overwritten with a blank one.

## Build

- Debug: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Tests: `./gradlew testDebugUnitTest` (54 tests: economy, saves/migrations, progression, physics, a headless
  engine + renderer smoke test that flies whole campaigns to the Moon, Mars, Europa and Titan).
- Release (Google Play): `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, signed with the
  Play **upload key**, read from an external properties file (`-ProcketLauncherKeystoreProps=<path>`, env
  `ROCKETLAUNCHER_KEYSTORE_PROPS`, or the default `C:/Users/Brandon/AndroidKeys/rocketlauncher-keystore.properties`).
  Keystores and that file are never committed. Play App Signing is used.

## Store listing

Ready-to-paste Play Store text and the screenshot shot list are in [`store-listing/`](store-listing/).

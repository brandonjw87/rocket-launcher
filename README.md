# Rocket Launcher

Android rocket game (Kotlin, Jetpack Compose UI, OpenGL ES 3 renderer). Built from Rocket Ascent v2.

**1.1 (versionCode 2)**

- **Auto launcher:** the rocket flies itself. No throttle, steering or tilt controls: you research upgrades in the
  tech tree and press LAUNCH. The autopilot burns at full throttle (stages/boosters separate automatically), the
  Digital Flight Computer throttles through max-Q, Deep-Space Navigation cuts the engines once on course for Mars
  (saving capture/landing fuel). Hazards are avoided by the autopilot: radar/guidance decide how far ahead it sees,
  fins/avionics how hard it can turn, hull upgrades how many hits it survives. Hazard layouts are deterministic
  per flight number, so the outcome depends only on upgrades.
- **One mission:** launch → space → orbit → past the Moon → Mars transfer → Mars orbit → Mars landing, one flight
  path. No destination picker. Milestones along the way pay a one-off height/distance bonus.
- Money comes only from height/distance (altitude pay plus one-off milestones). No coins or collectibles.
- A hidden 6.769% (`Economy.INTEREST_RATE`) bank interest on money left unspent at launch is folded into each
  launch's "Flight performance" pay. Exact BigDecimal math; whole dollars are paid (rounded down) and the
  sub-dollar remainder is carried in the save to the next payout.
- 54-node tech tree over 9 tiers (tiers 7-9 are the Mars program).
- Graphics quality: Low / Medium / High. All visuals and audio are generated in code.
- No ads, no internet permission, no tracking.

Package `com.brandonwallace.rocketlauncher`, save file `rocket_launcher` (key `save`). Saves carry a schema
version (`v=3`); older saves (1.0 = `v=2`) are migrated on load without losing money/unlocks, unknown keys from
newer builds are preserved, and the untouched pre-migration save is kept once as `save_backup_v<n>`. The save is
written on every change and synchronously on pause/stop.

Build: `./gradlew assembleDebug` (tests: `./gradlew testDebugUnitTest`).

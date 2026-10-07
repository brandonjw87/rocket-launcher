# Rocket Launcher

Android rocket game (Kotlin, Jetpack Compose UI, OpenGL ES 3 renderer). Built from Rocket Ascent v2.

- Money comes only from launch height (altitude pay plus one-off height milestones). No coins or collectibles.
- A hidden 2.5% bank interest on unspent money is folded into each launch's pay.
- 54-node tech tree over 9 tiers (tiers 7-9 are the Mars program).
- Mission line: space, orbit, Moon, Moon landing, Mars transfer, Mars orbit, Mars landing.
- Graphics quality: Low / Medium / High. All visuals and audio are generated in code.
- No ads, no internet permission, no tracking.

Package `com.brandonwallace.rocketlauncher`, save file `rocket_launcher`.

Build: `./gradlew assembleDebug` (tests: `./gradlew testDebugUnitTest`).

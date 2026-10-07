# 0 — START HERE: publishing Rocket Launcher 1.3 to Google Play

This page is for you, Brandon. It covers getting the new version from GitHub to players on Google Play,
step by step, keeping the **same signing key**, so everyone's update installs over the old version and keeps their
money and upgrades.

**This update:** version **1.3** (versionCode **5**). It's the same app (`com.brandonwallace.rocketlauncher`) and stays
**free with no ads**. What's new: after Europa the mission continues to **Saturn**: a Jupiter slingshot, the rings,
Saturn orbit, the ice geysers of Enceladus, a probe on Titan and a rotorcraft flight on Titan. Saturn is 2.04× as far
as Jupiter in real life, so the whole game doubles: 18 mission steps (was 9), 163 upgrades (was 80) over 24 tiers
(was 12), 35 milestones, 29 badges, and about 182 flights to finish (was about 89). The hidden bonus stays 16.769%
(still never shown in the game or the store text).

---

## Step 0: things you need (check once)

| Thing | Where | Why |
|---|---|---|
| Your PC project | `C:\Users\Brandon\AndroidProjects\RocketLauncher` | Where you build |
| Upload keystore settings | `C:\Users\Brandon\AndroidKeys\rocketlauncher-keystore.properties` | Signs the release with your **upload key** |
| The keystore file it points to (`.jks`) | Same folder (the `storeFile=` line in that file) | The key itself |
| Play Console | https://play.google.com/console | Where you upload |

> 🔑 **Never lose, replace or re-create the upload keystore**, and never put it on GitHub (the project's
> `.gitignore` already blocks it). Keep a backup copy on a USB stick or in Google Drive. Play App Signing is on, so
> if it's ever lost Google can reset the *upload* key. That takes days, so a backup saves you the trouble.

---

## Step 1: get the new code onto your PC

Open **PowerShell** and run:

```powershell
cd C:\Users\Brandon\AndroidProjects\RocketLauncher
git fetch origin
git checkout main
git pull
```

Check it's the new version. This line should print `versionCode = 5`:

```powershell
Select-String -Path app\build.gradle.kts -Pattern "versionCode"
```

## Step 2: run the tests (about 2 minutes)

```powershell
.\gradlew testDebugUnitTest
```

It must end with **BUILD SUCCESSFUL**. If it fails, stop and send me the last 30 lines.

## Step 3: build the signed release bundle (.aab)

```powershell
.\gradlew clean bundleRelease
```

The file appears at: `app\build\outputs\bundle\release\app-release.aab`

### Check it's signed with YOUR upload key (important)

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -printcert -jarfile app\build\outputs\bundle\release\app-release.aab
```

- If it prints **"Not a signed jar file"**, the build couldn't find your keystore settings file. Check that
  `C:\Users\Brandon\AndroidKeys\rocketlauncher-keystore.properties` exists, then build again.
- Otherwise copy the **SHA256** line. In Play Console go to your app → **Test and release → Setup → App
  signing** (on some screens it's **App integrity → App signing**) and compare it with the **Upload key certificate**
  SHA-256. They must match. If they match, you're signed correctly.

Copy the `.aab` somewhere safe, for example `Desktop\GrokBot\Android Apps\Games\RocketLauncher-1.3.aab`, and to
Google Drive as a **new file**. Don't put it on GitHub.

## Step 4: upload it to Play Console

1. Open https://play.google.com/console and click **Rocket Launcher**.
2. In the left menu open **Test and release**.
3. Pick the **same track you used for 1.2**:
   - If 1.2 is **live for everyone**, choose **Production**.
   - If 1.2 is only in **Internal testing** or **Closed testing**, choose that track.
   - If you never uploaded 1.2, that's fine: 1.3 includes everything from 1.2. Use the track you used for 1.1.
     (New personal developer accounts must run a **closed test with at least 12 testers for 14 days** before Google
     allows Production. If the Production page asks for this, stay on the closed test for now.)
4. Click **Create new release**.
5. Under **App bundles**, click **Upload** and pick `app-release.aab`.
   It should show **versionCode 5 (1.3)**. If it says the version code is already used, tell me and I'll bump it.
6. **Release name:** leave the default (`5 (1.3)`).
7. **Release notes:** paste the text from [`store-listing/release-notes-1.3.txt`](store-listing/release-notes-1.3.txt)
   between the `<en-US>` and `</en-US>` tags.
8. Click **Next**. Warnings in yellow are OK to read and continue. Errors in red must be fixed (send them to me).
9. Click **Save**, then go to **Publishing overview** (left menu) → **Send changes for review**.

Review usually takes a few hours to a few days. You'll get an email. Players' phones then update automatically
and **keep their progress**: the game moves 1.0, 1.1 and 1.2 saves to the new format and keeps a backup copy.

> Optional but safest: upload to **Internal testing** first, install it from the Play Store on your own phone
> (it updates over 1.2), check your money and upgrades are still there, then on that release click
> **Promote release** → Production (or your closed track).

> ⚠️ Don't install a **debug** build on the phone that has the Play version. The signatures differ, so Android
> makes you uninstall first, and uninstalling deletes your save. Use the emulator for debug builds.

## Step 5: update the store listing (text + screenshots)

In Play Console: **Grow users → Store presence → Main store listing**.

| Field | Paste from | Limit |
|---|---|---|
| App name | [`store-listing/title.txt`](store-listing/title.txt) | 30 |
| Short description | [`store-listing/short-description.txt`](store-listing/short-description.txt) | 80 |
| Full description | [`store-listing/full-description.txt`](store-listing/full-description.txt) | 4,000 |
| Phone screenshots | Follow [`store-listing/SCREENSHOTS.md`](store-listing/SCREENSHOTS.md) | 2–8 |

Click **Save**. Listing changes also go through review, together with the release if you send them at the same time.

## Step 6: things that do NOT need changing (leave them as they are)

- **Privacy policy:** your page from the `rocket-launcher-privacy` repo (normally
  https://brandonjw87.github.io/rocket-launcher-privacy/) is still correct. The app still collects nothing and has
  no internet permission.
- **Data safety:** still "No data collected / No data shared".
- **Ads:** still "No, my app does not contain ads".
- **Content rating, target audience, app access, pricing (Free):** unchanged. No new questionnaire needed.

## Step 7: after it's live

```powershell
git tag v1.3-play
git push origin v1.3-play
```

That marks exactly which code is on Google Play.

---

## Quick answers

- **"Will players lose their money?"** No. Same app id and same save file; the save is upgraded on first launch
  (schema 4 → 5) and the old one is kept as a backup. The automated tests check this, including real 1.1 and 1.2
  saves. Players who already landed on Europa go straight on to the Saturn chapter.
- **"Is it still free with no ads?"** Yes. There are no ads, no purchases and no internet permission.
- **"Next update?"** Change `versionCode` to **6** and `versionName` to "1.4" in `app\build.gradle.kts`, then repeat
  steps 2–7. Every upload needs a higher versionCode than the last one.
- **"The build says it can't find the SDK."** Android Studio sets this up the first time it opens the project.
  Open the folder once in Android Studio and let it finish syncing, then run the commands again.
- **Where's everything?** Source: GitHub `brandonjw87/rocket-launcher` (private). Builds (`.apk`/`.aab`): Google Drive and
  `Desktop\GrokBot\Android Apps\Games`. Notes and history: GitHub `brandonjw87/android-apps-instructions`.

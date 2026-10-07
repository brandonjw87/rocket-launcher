# Screenshots for the Play Store (version 1.3)

Your 1.2 screenshots stop at Jupiter, so replace them with the Saturn ones below. This page lists **what to
capture**, **how to capture it** and **what size Play accepts**.

## What Google Play accepts (phone screenshots)

- **2 to 8** screenshots. PNG or JPEG, no transparency.
- Each side between 320 and 3,840 pixels, and the **long side can be at most 2× the short side**.
- ⚠️ Raw screenshots from a Pixel 8 emulator (1080 × 2400) or a Pixel 10 Pro XL are **too tall** (more than 2:1) and
  Play will reject them. Crop each one to **9:16** (for example 1080 × 1920) before uploading:
  open it in the Windows **Photos** app → **Edit** → **Crop** → aspect ratio **9:16** → drag the box over the part
  you want → **Save as copy**.
- Use 1080 px wide or more so Play can feature them.
- You don't need a new **feature graphic** (1024 × 500) or **app icon** (512 × 512). Keep the ones you have.

## Shot list (in store order)

| # | What | Save to load | When to press the shortcut |
|---|------|--------------|-----------------------|
| 1 | **Liftoff**: the huge 8-stage Saturn rocket with six side cores, steam on the pad | `saturn.xml` | 1–3 s after "LIFTOFF!", camera still on the ground |
| 2 | **Hangar** with the 8-stage rocket, forecast "🚁 Titan flight" | `saturn.xml` | Main menu → PLAY |
| 3 | **Tech tree** zoomed out over the Saturn tiers | `saturn.xml` | Hangar → TECH TREE, pinch out, tap one node (e.g. Dragonfly Rotorcraft) so its detail panel shows |
| 4 | **Early rocket in the clouds** (orange kerosene exhaust with shock diamonds) | `early.xml` | ~5–8 km up, inside the cloud layer |
| 5 | **Space**: stage separation over the curved Earth | `mars.xml` | 150–2,000 km, right after a "Stage separation" banner |
| 6 | **Jupiter slingshot**: Jupiter sweeping past close by | `saturn.xml` | Banner "🌀 JUPITER SLINGSHOT" (the warp slows down near Jupiter) |
| 7 | **Saturn and its rings** filling the sky ahead | `saturn.xml` | Banner "⭐ Titan's Orbit" or "⭐ Saturn Arrival", a few seconds before arrival |
| 8 | **Results**: "Flew on Titan!" | `saturn.xml` | When the results screen appears (the Saturn flight takes about 5 minutes) |

Tips: set **Settings → Graphics: High** first (the saves already do). Don't pause to take a shot, because the pause
screen darkens the picture. Just take it live. The ⭐ milestone banners only show the first time, so if you miss
one, load the save again (step 2 below) and fly again. `jupiter.xml` is still there if you'd rather show the Jupiter and
Europa part of the mission (it flies to Jupiter and lands on Europa).

## How to take them

### Option A: on the emulator (easiest, and you can load the saves above)

The four files in `store-listing/screenshot-saves/` are ready-made game saves (an early game, Mars program done,
the full Jupiter rocket, and the full Saturn rocket with every upgrade). They work only with a **debug** build on the **emulator**.

> ⚠️ **Never do this on your own phone.** Loading a screenshot save replaces the progress on that device.

In PowerShell, from `C:\Users\Brandon\AndroidProjects\RocketLauncher`:

```powershell
# 1. start the Pixel_Emulator (from the Tools shortcut or Android Studio), then install the debug build
.\gradlew installDebug

# 2. load a save (change saturn.xml to early.xml, mars.xml or jupiter.xml as needed)
adb -s emulator-5554 shell am force-stop com.brandonwallace.rocketlauncher
adb -s emulator-5554 push store-listing\screenshot-saves\saturn.xml /data/local/tmp/rl.xml
adb -s emulator-5554 shell "run-as com.brandonwallace.rocketlauncher mkdir -p shared_prefs"
adb -s emulator-5554 shell "cat /data/local/tmp/rl.xml | run-as com.brandonwallace.rocketlauncher sh -c 'cat > shared_prefs/rocket_launcher.xml'"

# 3. open Rocket Launcher on the emulator and play to the moment in the table

# 4. capture the screen to a file on the PC (run this at the right moment; change the file name each time)
adb -s emulator-5554 exec-out screencap -p > shot1.png
```

PowerShell 5.1 can garble binary output when you use `>`. If a PNG won't open, take the screenshot this way instead:

```powershell
adb -s emulator-5554 shell screencap -p /sdcard/shot1.png
adb -s emulator-5554 pull /sdcard/shot1.png
```

You can also click the **camera** button on the emulator's side toolbar. It saves to your Desktop.

### Option B: on your phone

Play normally and press **Power + Volume Down** together. Screenshots land in Google Photos → Screenshots. You can
get shots 4 and 5 this way early on. The Saturn shots need a late-game save, so use Option A for those.

## Before uploading

1. Crop every shot to 9:16 (see above).
2. In Play Console: **Grow users → Store presence → Main store listing → Phone screenshots**. Delete the old ones,
   upload the new ones in the order above, then **Save**.

# Screenshots for the Play Store (version 1.2)

Your 1.1 screenshots show the old 6-step mission and no mute button, so replace them. This page lists **what to
capture**, **how to capture it** and **what size Play accepts**.

## What Google Play accepts (phone screenshots)

- **2 to 8** screenshots. PNG or JPEG, no transparency.
- Each side between 320 and 3,840 pixels, and the **long side can be at most 2× the short side**.
- ⚠️ Raw screenshots from a Pixel 8 emulator (1080 × 2400) or a Pixel 10 Pro XL are **too tall** (more than 2:1) and
  Play will reject them. Crop each one to **9:16** (for example 1080 × 1920) before uploading:
  open it in the Windows **Photos** app → **Edit** → **Crop** → aspect ratio **9:16** → drag the box over the part
  you want → **Save as copy**.
- Use 1080 px wide or more so Play can feature them.
- You don't need a new **feature graphic** (1024 × 500) or **app icon** (512 × 512). Keep the 1.1 ones.

## Shot list (in store order)

| # | What | Save to load | When to press the shortcut |
|---|------|--------------|-----------------------|
| 1 | **Liftoff**: big rocket, methalox exhaust, steam on the pad | `jupiter.xml` | 1–3 s after "LIFTOFF!", camera still on the ground |
| 2 | **Hangar** with the huge 6-stage rocket, forecast "🧊 Europa landing" | `jupiter.xml` | Main menu → PLAY |
| 3 | **Tech tree** zoomed out over the Mars and Jupiter tiers | `jupiter.xml` | Hangar → TECH TREE, pinch out, tap one node (e.g. Fusion Plasma Drive) so its detail panel shows |
| 4 | **Early rocket in the clouds** (orange kerosene exhaust with shock diamonds) | `early.xml` | ~5–8 km up, inside the cloud layer |
| 5 | **Space**: stage separation over the curved Earth | `mars.xml` | 150–2,000 km, right after a "Stage separation" banner |
| 6 | **Mars flyby** on the way to Jupiter (banner "MARS FLYBY — on to Jupiter!") | `jupiter.xml` | When Mars grows ahead (the warp slows down for a few seconds) |
| 7 | **Jupiter** growing ahead, its four big moons as bright dots, solar wings unfolded | `jupiter.xml` | Banner "Jupiter approach — orbit insertion ahead" (a few seconds before arrival) |
| 8 | **Results**: "Landed on Europa!" | `jupiter.xml` | When the results screen appears |

Tips: set **Settings → Graphics: High** first (the saves already do). Don't pause to take a shot, because the pause
screen darkens the picture. Just take it live.

## How to take them

### Option A: on the emulator (easiest, and you can load the saves above)

The three files in `store-listing/screenshot-saves/` are ready-made game saves (an early game, Mars program done,
and the full Jupiter rocket). They work only with a **debug** build on the **emulator**.

> ⚠️ **Never do this on your own phone.** Loading a screenshot save replaces the progress on that device.

In PowerShell, from `C:\Users\Brandon\AndroidProjects\RocketLauncher`:

```powershell
# 1. start the Pixel_Emulator (from the Tools shortcut or Android Studio), then install the debug build
.\gradlew installDebug

# 2. load a save (change jupiter.xml to early.xml or mars.xml as needed)
adb -s emulator-5554 shell am force-stop com.brandonwallace.rocketlauncher
adb -s emulator-5554 push store-listing\screenshot-saves\jupiter.xml /data/local/tmp/rl.xml
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
get shots 1, 2, 4 and 5 this way early on. The Jupiter shots need a late-game save, so use Option A for those.

## Before uploading

1. Crop every shot to 9:16 (see above).
2. In Play Console: **Grow users → Store presence → Main store listing → Phone screenshots**. Delete the old ones,
   upload the new ones in the order above, then **Save**.

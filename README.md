# AURELIUS7

One-tap 3D wigglegram camera for Android, inspired by the K4MERA 4-lens stereo camera,
done in software: tap once, slide the phone, get a looping 3D GIF / MP4.

## How it works
1. **Sweep capture**: after one tap the app locks AE/AF/AWB and records ~1–2 s of YUV frames while you slide.
2. **Parallax tracking**: the subject patch and the background are tracked separately (SAD block matching);
   their difference is the parallax, so turning the phone in place doesn't count as depth.
3. **4 virtual lenses**: picks 4 evenly spaced, sharpest frames along the sweep.
4. **Composer**: aligns every view on the focus point (coarse-to-fine), crops to a shared window (9:16 / 4:5 / 1:1).
   Drag on the result to refocus live.
5. **Export**: looping GIF (median-cut palette + LZW, own encoder) or H.264 MP4 (MediaCodec, ≥4 s for Reels).

## Build (no Gradle)
Needs `aapt`, `dalvik-exchange` (dx), `zipalign`, `apksigner`, JDK and the `android-23` platform jar
(Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`).

```
./build.sh          # -> out/AURELIUS7-v<version>.apk
```

The signing key (`aurelius7.keystore`) is **not** in git. Keep your own copy safe: every release must be
signed with the same key or Android refuses to update over the installed app.
If it's missing, `build.sh` generates a new one (fresh installs only).

## Layout
- `CameraRig.java`: Camera2 preview + YUV stream, AE/AF/AWB lock
- `SweepRecorder.java`: frame capture, subject/background tracking, coaching
- `WiggleBuilder.java`: frame selection, YUV→RGB, upright rotation
- `Composer.java`: refocusable alignment + cropping
- `GifEncoder.java` / `Mp4Encoder.java`: exporters
- `Ui.java`: brutalist widget kit · `MainActivity.java`: screens

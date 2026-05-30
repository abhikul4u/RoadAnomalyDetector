# RADS Detector — Android App

Real-time on-device road anomaly detection (Pothole / Waterlogged Pothole /
Open Manhole) using YOLOv8 + TFLite.

This is the Layer 4 (Online Production) implementation from Chapter 4 of the
thesis. The backend (Layer 5) and civic portal are deferred — reports queue
locally in SQLite for now.

---

## Project structure

```
RoadAnomalyDetector/
├── app/
│   ├── build.gradle.kts                ← All dependencies
│   └── src/main/
│       ├── assets/
│       │   ├── model.tflite            ← **YOU MUST ADD THIS** (see below)
│       │   └── labels.txt              ← Already included (MH/PH/WLPH)
│       ├── java/com/rads/detector/
│       │   ├── config/                 ← All tunables in one file
│       │   ├── detection/              ← TFLite inference + NMS
│       │   ├── camera/                 ← CameraX setup
│       │   ├── overlay/                ← Bounding-box drawing
│       │   ├── severity/               ← Section 4.3 severity logic
│       │   ├── alerts/                 ← Audio + haptic alerts
│       │   ├── location/               ← GPS via FusedLocationProvider
│       │   ├── reporting/              ← Report builder + persistence
│       │   ├── storage/                ← Room database
│       │   ├── util/                   ← FPS meter, perf timer, logger
│       │   ├── upload/                 ← Backend stub (Phase 2)
│       │   ├── MainActivity.kt
│       │   ├── PermissionActivity.kt
│       │   ├── SettingsActivity.kt
│       │   └── RadsApplication.kt
│       ├── res/                        ← Layouts, strings, colors, icons
│       └── AndroidManifest.xml
├── conversion/                         ← Run this BEFORE building the app
│   ├── convert_to_tflite.py
│   ├── requirements.txt
│   ├── labels.txt
│   └── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew                             ← Unix gradle wrapper
├── gradlew.bat                         ← Windows gradle wrapper
└── README.md                           ← This file
```

---

## Build instructions

### Step 1: Convert your model to TFLite

This is a one-time step. See `conversion/README.md` for full details.
Quick version, FP16 (no calibration images needed):

```bash
cd conversion/
python3.11 -m venv venv
source venv/bin/activate           # or venv\Scripts\activate on Windows
pip install -r requirements.txt
python convert_to_tflite.py --model best.pt
```

When done, copy the resulting file into the Android app:

```bash
cp best_saved_model/best_float16.tflite \
   ../app/src/main/assets/model.tflite
```

### Step 2: Open in Android Studio

1. Download Android Studio (Hedgehog | 2023.1.1 or later — supports compileSdk 35).
2. **File → Open** → select the `RoadAnomalyDetector` folder.
3. Wait for Gradle sync to complete (downloads dependencies — first time
   takes 5-10 minutes).
4. Connect your phone via USB with **Developer Mode + USB debugging** enabled
   (Settings → About phone → tap "Build number" 7 times → back → Developer
   options → enable USB debugging).
5. Click the green **Run** button (or `Shift+F10`).

### Step 3: Use the app

1. First launch: grant Camera, Location, Notification permissions.
2. Point camera at the road. Detections appear as colored bounding boxes:
   - 🔴 **Red** — Critical (open manhole)
   - 🟠 **Orange** — High (waterlogged pothole)
   - 🟡 **Yellow** — Medium (pothole)
3. Alerts trigger via audio + haptic when an anomaly persists for 3+ frames.
4. All detections logged to local SQLite with GPS coordinates.
5. Top-left shows inference time + delegate (GPU or CPU+XNNPACK).
6. Tap the settings icon (top-right) to see model info, clear reports, etc.

---

## Tuning

All thresholds live in **one file**: `app/src/main/java/com/rads/detector/config/Config.kt`

Common adjustments:

- `CONFIDENCE_THRESHOLD` — lower = more detections, more false positives
- `IOU_THRESHOLD` — NMS overlap threshold for duplicate suppression
- `TARGET_INFERENCE_FPS` — higher = more responsive, more heat. Default 10.
- `MIN_FRAMES_TO_REPORT` — frames an anomaly must persist before reporting.
  Higher = fewer false reports but slower alerting. Default 3.

For model swaps (different `.tflite`), update:
- `MODEL_FILENAME`
- `INPUT_WIDTH` / `INPUT_HEIGHT`
- `NUM_CLASSES` (if classes change — also update `labels.txt`)

Nothing else needs to change. The detector is fully model-agnostic within
the YOLOv8 family.

---

## Verifying it works

When the app launches successfully you should see:

1. **Camera preview** filling the screen.
2. **Top-left**: "Inference: XX ms" — should be ~50-100 ms on Edge 60 Pro,
   ~80-150 ms on Edge 60 Fusion (FP16 model). With INT8 quantization
   expect roughly 2× faster.
3. **Top-right**: "Delegate: GPU" (most likely). Falls back to
   "CPU+XNNPACK" on devices where the GPU delegate is incompatible.
4. **Bottom**: GPS coordinates (once GPS locks — may take 30s outdoors).
5. **Bottom**: "Reports queued: N" — increments as detections persist.

If you see "Failed to load model" in red:
- The .tflite file is missing from `app/src/main/assets/`, OR
- The model's input size or output shape doesn't match `Config.kt`.
  Verify with `python convert_to_tflite.py --model best.pt` output.

---

## Performance expectations

| Phone | Model variant | Realistic FPS |
|---|---|---|
| Edge 60 Pro | FP16 @ 640 | 10–18 FPS |
| Edge 60 Pro | INT8 @ 640 | 18–25 FPS |
| Edge 60 Fusion | FP16 @ 640 | 6–12 FPS |
| Edge 60 Fusion | INT8 @ 640 | 12–18 FPS |

Effective detection FPS is capped at `TARGET_INFERENCE_FPS` (default 10) to
manage thermal load. The app drops frames if the detector falls behind —
the camera preview stays smooth regardless.

---

## What's NOT in this version

These were intentionally deferred per Chapter 4 phasing:

- Backend API uploads (reports queue locally instead)
- Civic portal / hotspot cache for look-ahead alerts
- OTA model updates (replace `model.tflite` manually for now)
- CSV export of reports (one-liner addition if needed)

All deferred items can plug in without touching the detection pipeline.

---

## Troubleshooting

**"Model output shape unexpected" runtime error**
The .tflite has a different layout than expected. Make sure you exported
with `--nms` flag OFF (we do NMS in Kotlin — gives more control + ~2 ms
saving). Re-run the conversion script.

**App crashes on first launch with "model.tflite not found"**
You forgot Step 1. The `assets/` folder needs `model.tflite`.

**Camera preview is sideways or upside down**
The phone is in landscape mode; the app is locked to portrait. Rotate the
phone. (Adding landscape support is a layout-only change if needed.)

**Inference is very slow (>500 ms)**
Either the GPU delegate failed to initialize (check Logcat for the message
"GPU delegate init failed") or the model is FP32 (no quantization). Re-run
the conversion script with `--int8` flag for production-grade speed.

**No GPS coordinates ever appear**
You're indoors or in poor sky view. Walk outside and wait ~30s for a fix.
Inside a vehicle near a windshield, GPS usually locks within a minute.

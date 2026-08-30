# MediaCompressor — Cyber Hacker Obsidian Edition

Offline Android media workstation by **Vr3tH🇵🇸**.

## Design direction
- Cyber Hacker Obsidian: deepest-void obsidian base, frosted specular glass cards, all-green Matrix HUD accents.
- All status, error, rejected, and cancelled text/borders render in Cyber Green (#00FF9D); Cyber Rose (#FF2B8D) survives only as the secondary select/laser-highlight accent.
- Lightweight motion: 0.95x touch compression with a spring release, fade/slide/scale and in-place progress transitions only.
- No heavy background effects or unnecessary particles.
- Technical information is shown only when it is supported by the engine.

## New in this build
- **Redesigned Home dashboard** (strict 01–07 vertical rhythm: Header → Time of Day → Realtime Clock → LIVE Monitor → System Monitor → Akses Lumi → Command Center). Built from a text-only design brief — no reference screenshots were available this pass, so visual fidelity to any specific reference mockup is not verified; only the described structure/behavior is implemented.
- **LIVE Monitor** — one integrated card (label + pulsing dot + animated scanner + engine state) with an IDLE → PROCESSING → COMPLETED → IDLE state machine. The scanner keeps moving calmly at all times (near-zero CPU, one ValueAnimator) and only speeds up during a real operation. Tapping it while a task is running/just finished reopens the full Processing screen.
- **Broom icon removed from the visible UI.** Temp-file cleanup is now silent, automatic background maintenance — runs on app launch and on every Home visit, always gated on `!taskRunning` so it can never touch a file an active operation is still writing, and it only ever matches files under the app's own `.tmp_`-prefixed cache/work directory (never original or finished-output files).
- **Folder icon removed from the header** to keep it minimal (☰ / identity / status capsule only). The Media Vault feature itself is unchanged — reachable from the Drawer menu and from the new "Akses Lumi" row.
- **Time-of-day system** (Pagi / Siang / Sore / Malam — 04–10 / 11–14 / 15–17 / 18–03) computed from device system time, equal visual weight across all four.
- **Real System Monitor** — CPU (this app's own process CPU time via `Process.getElapsedCpuTime()`, sampled safely with no `/proc` parsing), RAM (`ActivityManager.MemoryInfo`), Storage (`StatFs`), Battery (`BatteryManager.BATTERY_PROPERTY_CAPACITY`) — every reader is wrapped in try/catch and degrades to "N/A" rather than crashing; nothing is hardcoded or randomized. Refreshes every 4s, only while the Activity is actually resumed.
- **Activity-lifecycle-aware tickers** — the clock, LIVE Monitor scanner animation, and System Monitor poll all check a shared `activityActive` flag (set in `onPause`/`onResume`), so backgrounding the app stops the real work (formatting, system calls, animation) even if the Home screen view is still technically attached underneath.
- **"Akses Lumi"** compact secondary-action row (Vault, About) replacing the old header icons.
- **Background-surviving processing + Home HUD** — system/gesture back no longer aborts a running task; it just leaves a live progress card on Home (view or cancel from there). Only the explicit `[ ✕ Cancel ]` button aborts. Finalize + MediaStore publish always run in the worker's own completion handler, exactly once, regardless of which screen is visible when a task finishes.
- **Dedicated Audio Compress engine** — "Auto Smart" AAC VBR re-encode: derives a target bitrate from the source's own average bitrate (halved, bounded 48–96 kbps), guarded so the output is never larger than the original.
- **Universal Adaptive Parameter Telemetry badges** — live, source-derived estimate cards on Speed, Rotate/Flip, Image Converter, Video/Audio Trim & Reverse, Split & Reverse PDF, Extract Frame, Video-to-GIF, Photo-to-GIF, Audio Compress, Audio Silence Trimmer, Compress PDF, and PDF-to-Grayscale.
- **Flip H/V engine** — true pixel-matrix mirroring (decode → flip → re-encode with audio passthrough), alongside the existing lossless 90°/180°/270° rotation.
- **Cyber Matrix Green & Diamond White palette** — Cyber Rose survives strictly as the secondary select/laser-highlight accent and in secondary telemetry captions; everything else (active borders, checkmarks, toggles, error/rejected states) renders in Matrix Cyber Green.
- **Adaptive bilingual UI** — auto-detects Indonesian vs. English from the device locale (`Locale.getDefault()`), no manual toggle.
- **Interactive `[ ? ]` tooltips** across Rotate/Flip, Video→GIF, Speed, Image Converter, Photo→GIF, and the newer parameter screens, explaining each option in plain language.
- **Universal batch queue** — single-file engines accept multiple files at once and process them in sequence under one configured setting, each producing its own output.
- **Instant re-entry file header card** — tapping anywhere on the file card re-opens Feature Configuration with prior settings retained.
- **In-app Media Vault** (`[ 📁 ]` in the top bar) — browse everything published to `Downloads/MediaCompressor/`, with Select All and per-item/bulk delete, organized into the `Photos/GIF/Videos/Audio/Documents/Archives/Extracted` taxonomy.
- **Dynamic origin back-stack** — entering a category from Home vs. from the Drawer returns you to the right place on back.
- Universal SAF picker (`ACTION_GET_CONTENT`, `CATEGORY_OPENABLE`, `EXTRA_LOCAL_ONLY = false`) so every installed file manager/gallery app appears in the picker's side drawer.
- **Cancellation audit** — every engine loop that could otherwise run for a perceptible amount of time (frame encode/decode, GIF decode, sample-level audio gain/reverse/silence-scan, PDF merge per-page, ZIP entry read) now has a bounded-latency cancellation checkpoint, so the `[ ✕ Cancel ]` button aborts near-instantly instead of only after the current phase finishes.

## UI architecture
**Command Center → Category → Media Pick → Feature Workspace → Processing HUD → Result**

Only operations that need user parameters receive custom controls. Simple operations use a clean input → process flow.

## Engine-aligned feature catalogue
### Video
- Video Compress & Mute *(batch)*
- Video Trim & Reverse *(batch)*
- Video Speed *(batch)*
- Video to Audio (M4A extraction) *(batch)*
- Video to GIF *(batch)*
- Extract Frame *(batch)*
- Video Merge
- Video Rotate & Flip *(batch; true 90°/180°/270° rotation + Flip H/V pixel-matrix mirroring)*

### GIF
- GIF to Video *(batch)*
- GIF Compress *(batch)*

### Image
- Photo Compress *(batch)*
- Batch Photo Compress
- Image Converter (JPG / PNG / WEBP) *(batch)*
- Remove EXIF *(batch)*
- Photo to GIF

### Audio
- Audio Trim & Reverse *(batch)*
- Audio Merge
- Audio Compress (Auto Smart AAC VBR) *(batch)*
- Audio Volume Booster *(batch)*
- Audio Silence Trimmer *(batch)*

### PDF
- Photo to PDF
- PDF to Photo *(batch)*
- Merge PDF
- Split & Reverse PDF
- Compress PDF *(batch)*
- PDF to Grayscale *(batch)*
- Watermark PDF *(batch)*
- Lock PDF
- Unlock PDF

### Archive
- Create ZIP
- Extract ZIP *(batch)*
- ZIP Recompress *(batch)*

## Developer links
- WhatsApp: https://wa.me/6288229456210
- Instagram: https://www.instagram.com/rsx_xt/
- YouTube: https://youtube.com/@oficial_tzy

## Build
This project uses Android Gradle Plugin + Kotlin and targets SDK 35 (minSdk 24). The supplied environment does not contain a Gradle wrapper/Android SDK, so the APK must be built in Android Studio or CI (see `.github/workflows/build.yml`).

**Note:** this build was hand-edited outside Android Studio/Gradle (no compiler was available in that environment), so do a local `./gradlew assembleDebug` (or open in Android Studio) before treating it as final — see the accompanying chat summary for exactly what changed and what to double-check.


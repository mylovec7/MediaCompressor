# MediaCompressor — Cyber Hacker Obsidian Edition

Offline Android media workstation by **Vr3tH🇵🇸**.

## Design direction
- Cyber Hacker Obsidian: deepest-void obsidian base, frosted specular glass cards, all-green Matrix HUD accents.
- All status, error, rejected, and cancelled text/borders render in Cyber Green (#00FF9D); Cyber Rose (#FF2B8D) survives only as the secondary select/laser-highlight accent.
- Lightweight motion: 0.95x touch compression with a spring release, fade/slide/scale and in-place progress transitions only.
- No heavy background effects or unnecessary particles.
- Technical information is shown only when it is supported by the engine.

## New in this build
- **Adaptive bilingual UI** — auto-detects Indonesian vs. English from the device locale (`Locale.getDefault()`), no manual toggle.
- **Interactive `[ ? ]` tooltips** on Rotate/Flip, Video→GIF, Speed, Image Converter, and Photo→GIF, explaining each option in plain language.
- **Universal batch queue** — 20 single-file engines (compress, trim, speed, rotate, GIF conversion, EXIF strip, PDF tools, ZIP tools, etc.) now accept multiple files at once and process them in sequence under one configured setting, each producing its own output.
- **Instant re-entry file header card** — tapping anywhere on the file card (not just "↻ Change File") re-opens Feature Configuration with prior settings retained.
- **Real-time clock widget** on Home — live HH:mm:ss + localized date/timezone.
- **In-app Media Vault** (`[ 📁 ]` in the top bar) — browse everything published to `Downloads/MediaCompressor/`, with Select All and per-item/bulk delete.
- **Dynamic origin back-stack** — entering a category from Home vs. from the Drawer returns you to the right place on back.
- Universal SAF picker (`ACTION_OPEN_DOCUMENT`, `EXTRA_LOCAL_ONLY = false`) so every installed file manager/gallery app appears in the picker's side drawer.

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
- Video Rotate *(batch; engine operation named `Video Rotate & Flip`; current engine supports rotation only)*

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


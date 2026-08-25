# MediaCompressor — Rose Noir Final UI

Offline Android media workstation by **Vr3tH🇵🇸**.

## Design direction
- Rose Noir: obsidian black, graphite surfaces, soft rose-pink accents.
- Lightweight motion: fade, slide, scale and progress transitions only.
- No heavy background effects or unnecessary particles.
- Technical information is shown only when it is supported by the engine.
- Active processing/status accents use rose-pink; success/error states remain distinct.

## UI architecture
**Command Center → Category → Media Pick → Feature Workspace → Processing HUD → Result**

Only operations that need user parameters receive custom controls. Simple operations use a clean input → process flow.

## Engine-aligned feature catalogue
### Video
- Video Compress & Mute
- Video Trim & Reverse
- Video Speed
- Video to Audio (M4A extraction)
- Video to GIF
- Extract Frame
- Video Merge
- Video Rotate (engine operation named `Video Rotate & Flip`; current engine supports rotation only)

### GIF
- GIF to Video
- GIF Compress

### Image
- Photo Compress
- Batch Photo Compress
- Image Converter (JPG / PNG / WEBP)
- Remove EXIF
- Photo to GIF

### Audio
- Audio Trim & Reverse
- Audio Merge
- Audio Volume Booster
- Audio Silence Trimmer

### PDF
- Photo to PDF
- PDF to Photo
- Merge PDF
- Split & Reverse PDF
- Compress PDF
- PDF to Grayscale
- Watermark PDF
- Lock PDF
- Unlock PDF

### Archive
- Create ZIP
- Extract ZIP
- ZIP Recompress

## Developer links
- WhatsApp: https://wa.me/6288229456210
- Instagram: https://www.instagram.com/rsx_xt/
- YouTube: https://youtube.com/@oficial_tzy

## Build
This project uses Android Gradle Plugin + Kotlin and targets SDK 35. The supplied environment does not contain a Gradle wrapper/Android SDK, so the APK must be built in Android Studio or CI.

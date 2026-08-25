# MediaCompressor Core (Vr3tH)

A 100% offline Android media toolkit — 30 real transformation engines built
entirely on the pure Android Framework SDK. No FFmpeg, no Media3/ExoPlayer,
no Jetpack Compose, no external native binaries.

## Architecture

- `android.app.Activity` (no AppCompat), fully programmatic UI in a
  `ScrollView`, dp-based sizing, no XML layouts.
- `android.media.MediaCodec` / `MediaExtractor` / `MediaMuxer` for
  hardware-accelerated video/audio decode, encode, and lossless remuxing.
- `android.graphics.pdf.PdfDocument` / `PdfRenderer` for PDF read/write.
- A from-scratch, pure-Kotlin GIF codec (`GifEncoder.kt` / the `GifDecoder`
  object in `MediaEngine.kt`): median-cut quantizer, LZW compressor/decompressor,
  duplicate-frame dropper.
- `java.util.zip` for content-aware ZIP archiving with Anti-Zip-Slip
  extraction safety.
- `javax.crypto` (AES/CBC + PBKDF2) for PDF password lock/unlock.
- `androidx.exifinterface` — the one small, framework-adjacent dependency,
  used for robust EXIF orientation/metadata handling.

## Module groups (30 engines)

| Group   | Engines |
|---------|---------|
| Video   | Compress & Mute, Trim & Reverse, Speed, Video→Audio, Video→GIF, Extract Frame, Merge, Rotate & Flip |
| GIF     | Photo→GIF, GIF→Video, GIF Compress |
| Photo   | Compress, Batch Compress, Image Converter, Remove EXIF |
| Audio   | Trim & Reverse, Merge, Volume Booster, Silence Trimmer |
| PDF     | Photo→PDF, PDF→Photo, Merge, Split & Reverse, Compress, Grayscale, Watermark, Lock/Unlock |
| Archive | Create ZIP, Extract ZIP, Recompress ZIP |

## Cross-cutting systems

- **OutputVault (True Size Guard)** — rejects/rolls back any output that
  isn't smaller than its input, reporting `> ORIGINAL PRESERVED`.
- **MimeDetector** — hybrid extension + magic-byte sniffing, so files with
  missing/misleading extensions still route correctly.
- **MediaStoreExporter** — scoped-storage publishing to
  `Download/MediaCompressor/<Type>` using `IS_PENDING` for atomic writes.
- **TempSweeper** — clears orphaned `.tmp_*` files on startup.

## Engineering honesty notes

A few places trade some sophistication for a codebase that's actually
readable and buildable on pure framework APIs — documented in code comments,
summarized here:

- **Reverse video/audio** buffers decoded frames/PCM in memory before
  re-emitting in reverse order. Fine for short clips; long HD reverses will
  use meaningfully more RAM than the aspirational 15–30 MB target in the
  original spec — that target is unrealistic for in-memory reverse-buffering
  on any pure-SDK implementation, not just this one.
- **Speed change audio** uses compressed-sample passthrough with rescaled
  timestamps rather than true pitch-preserving time-stretching (which would
  require a dedicated DSP/phase-vocoder implementation well beyond
  `MediaCodec`'s scope).
- **Heterogeneous video merge** (mismatched resolutions/codecs) falls back
  to a fixed-fps bitmap-sampling re-encode without audio; same-format merges
  use true lossless remux.

## Build

```
./gradlew assembleRelease
```

Or push to `main` / tag a release — `.github/workflows/build.yml` builds
and uploads the release APK automatically.

**This project was generated with AI assistance and has not been
build-verified in a real Android SDK environment.** Build it locally or
via the included GitHub Actions workflow before relying on it, and expect
to fix a handful of real compile errors — a codebase this size essentially
always has some on a first pass.

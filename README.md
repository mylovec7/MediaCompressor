# MediaCompressor — Ultra Light

Offline Android media utility using Android platform APIs only. No FFmpeg, Media3, or native media library is bundled.

## Included
- Smart media picker and history
- Image compression (WebP/JPEG)
- PDF compacting
- ZIP compression/extraction
- Video/audio stream extraction
- Video → GIF
- Video trim
- Audio trim
- PDF split by page range
- Video reverse (H.264, video-only output)
- Output-size guard: compression output is rejected when it is not smaller than the original

## Important technical notes
- Trim is stream-copy. Video cut points can snap to a nearby keyframe because no re-encode library is bundled.
- Reverse video is a real frame decode/re-encode operation and is intentionally video-only to avoid silently attaching forward-played audio to reversed video.
- The existing Smart video path is still stream-copy/remux, not a full bitrate transcode. This keeps the APK and runtime footprint very small. It must not be described as true video re-encoding compression.
- Android device codec support varies by manufacturer and Android version.

## Build
GitHub Actions uses Gradle 8.7 and JDK 17.

`./gradlew` may not work locally if the Gradle wrapper JAR is absent; the included GitHub workflow installs Gradle directly.

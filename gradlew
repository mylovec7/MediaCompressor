#!/bin/sh
# MediaCompressor uses Gradle via GitHub Actions' Gradle setup.
# A Gradle Wrapper JAR is intentionally not bundled in this lightweight source package.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle is not installed. On GitHub Actions, use the included workflow, which installs Gradle 8.7." >&2
exit 127

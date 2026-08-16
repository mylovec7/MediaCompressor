# Build fixes in this package

Fixed the compile errors reported by GitHub Actions:
- MediaStore picture/movie directory constants now use `Environment.DIRECTORY_*`.
- PDF/ZIP publisher no longer passes `MEDIA_TYPE` where a string was required.
- Added output validators for image, video, PDF and ZIP operations.

Re-run the existing GitHub Actions workflow after replacing the repository contents with this package.

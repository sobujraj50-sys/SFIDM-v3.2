# SFIDM - Version 3.2

## Fixes in v3.2
- **Direct Binary `yt-dlp` Updater:** Bypasses GitHub API (`api.github.com`) rate limits by downloading the latest official `yt-dlp` release binary directly and replacing the local executable on Android.
- **YouTube Android Player Client Extractor (`android_vr,ios,android`):** Fixes `WARNING: [youtube]` / JS runtime errors on Android devices without Deno/Node.js, with automatic fallback to `tv_embedded,mweb`.
- **Clear Error Prioritization:** Surfaces the actual `ERROR:` line first on failed cards while preserving the full log in the tap-to-view dialog.

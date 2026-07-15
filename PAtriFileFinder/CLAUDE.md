# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**PAtriFileFinder** — a personal Android app that indexes and searches on-device documents
from WhatsApp, Telegram, and Downloads. Kotlin + Jetpack Compose (Material 3), MVVM +
Repository, Hilt DI.

### Non-negotiable constraints (product intent, not just style)
- **Everything runs on-device. No file data or AI inference ever leaves the phone.** Do not
  add network calls, analytics, crash reporting, or cloud services to the indexing/search
  path. The semantic model is bundled as an asset and runs locally.
- **Read-only w.r.t. the user's files.** The app scans and reads files; it must never move,
  modify, or delete them.
- **Never published to the Play Store.** It's a personal hobby app, so it uses
  `MANAGE_EXTERNAL_STORAGE` and `minSdk = 36` freely — don't "fix" these for store policy.

## Build & run

All Gradle commands run from this `PAtriFileFinder/` directory. On Windows use `gradlew.bat`
(PowerShell); the Bash tool can use `./gradlew`.

```powershell
.\gradlew.bat assembleDebug        # build debug APK
.\gradlew.bat installDebug         # build + install on connected device/emulator
.\gradlew.bat lint                 # Android lint
.\gradlew.bat test                 # JVM unit tests (app/src/test)
.\gradlew.bat connectedAndroidTest # instrumented tests (needs a device; app/src/androidTest)
```

Run a single unit test class / method:
```powershell
.\gradlew.bat test --tests "com.pab.patrifilefinder.SomeTest"
.\gradlew.bat test --tests "com.pab.patrifilefinder.SomeTest.someMethod"
```

Debugging on the physical device (Samsung Galaxy S24, serial `RZCY80X5B7W`) — tail the app's
structured logs:
```
C:\Users\atrip\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s \
  FileScanner:I FileScanWorker:I EmbeddingEngine:* FileRepository:D ProfileRepository:I
```

### Build gotchas baked into config (don't undo without reason)
- **`abiFilters = ["arm64-v8a"]`** (`app/build.gradle.kts`): MediaPipe ships no x86_64 native
  lib. This forces the installer to pick the arm64 lib so the text embedder loads on both the
  emulator (arm64 via binary translation) and real phones. Removing it breaks semantic search.
- **`noCompress += "tflite"`**: MediaPipe opens the model via an asset file descriptor, which
  requires it stored uncompressed in the APK.
- **Release build has R8/optimization disabled** — this is intentional for this hobby app.
- Dependency versions are centralized in `gradle/libs.versions.toml` (version catalog). Add
  deps there, reference via `libs.*`. KSP (not kapt) drives Room + Hilt codegen.

## Architecture

Data flows in one direction: **scan → index (Room) → search → UI**. There are two entry
points into the pipeline, and they must not step on each other.

### The indexing pipeline (`data/scanner/FileScanner.kt`)
`FileScanner.scan()` is the heart of the app and runs in **two phases**, both cooperative
with cancellation (`ensureActive()`) and resumable:
1. **Discover + index (fast).** Files are found via `MediaStore` first, with a direct
   filesystem walk as a fallback for files MediaStore hasn't indexed. Results are keyed by
   path (MediaStore entry wins over filesystem). Only **documents** pass `isScannable()` — an
   extension allowlist (`DOCUMENT_EXTENSIONS`) plus `text/*` mimes; images/video/audio/app-data
   are excluded. New files get their name + extracted text snippet inserted in batches.
2. **Embed (slow).** After files are keyword-searchable, semantic embeddings are backfilled
   for rows still missing one (`getFilesWithoutEmbedding()`). This is the resumable part — an
   interrupted run just continues next time.

Stale rows (backing file gone, or a type no longer indexed) are deleted each scan, so the
index **self-heals**.

**A single `Mutex` (`scanMutex`) serializes all scans app-wide.** There are two schedulers —
the periodic background `FileScanWorker` and the manual "Scan now" one-time work — and both
call the same `FileScanner` singleton. Without the mutex they overlap and each embeds every
file, doubling CPU/battery. Keep this invariant if you touch scanning.

### On-device semantic search (`data/embedding/EmbeddingEngine.kt`)
Wraps MediaPipe's `TextEmbedder` (Universal Sentence Encoder, ~6 MB, 100-dim). Loads lazily
and **degrades gracefully**: if the model asset is missing or the device has < ~2 GB RAM,
`isAvailable()` is false and the app silently falls back to keyword search. Embeddings are
stored as little-endian float BLOBs in the `FileRecord.embedding` column (`toBytes`/`toFloats`
helpers). `similarity()` returns **raw cosine clamped to 0..1** — deliberately not remapped
from -1..1, so the threshold actually separates related from unrelated files.

> The model file `app/src/main/assets/universal_sentence_encoder.tflite` is committed to the
> repo. Without it the app still runs (semantic search just stays "Unavailable").

### Search & ranking (`data/repository/FileRepository.kt`)
- `search()` — keyword-only via Room FTS4.
- `semanticSearch()` — blends `SEMANTIC_WEIGHT (0.6) × cosine + KEYWORD_WEIGHT (0.4) × keyword`.
  Semantic-only hits must clear `SEMANTIC_THRESHOLD (0.35)`. Results are bucketed by
  `SCORE_EPSILON (0.05)` for relevance, with recency (`dateAdded` desc) breaking ties.
- **Personalization**: when a query is first-person ("my medical records"), `personalize()`
  keeps files mentioning one of the user's names, drops files mentioning other people, and
  keeps un-attributed files. First-person filler words are also stripped from FTS queries.
- These scoring constants are the main tuning knobs — see the project memory file for the
  current fine-tuning state before changing them.

### Identity config (`data/profile/ProfileRepository.kt`)
`assets/profile.json` is seeded on first run to the app's external files dir
(`/sdcard/Android/data/com.pab.patrifilefinder/files/profile.json`) so it can be edited
on-device without rebuilding. The `me` key is the user; **every other key** (wife/appa/amma/
others/…) is treated as "someone else" to exclude in first-person searches. The parse is
cached for the process; call `reload()` or restart the app after editing.

### Persistence (`data/db/`)
Room DB `patrifilefinder.db` — `FileRecord` entity + `FileRecordFts` (FTS4 mirror for keyword
search). `FileRecord` overrides `equals`/`hashCode` because its `ByteArray` embedding column
would otherwise use referential equality. A unique index on `path` makes the DB itself reject
duplicate rows even if two scans race. **Migrations are intentionally destructive**
(`fallbackToDestructiveMigration`): the DB is a rebuildable index, so on any schema change bump
`AppDatabase.version`, drop everything, and re-scan — don't write migration code.

### DI & app wiring
- `PAtriFileFinderApp` (`@HiltAndroidApp`) provides a custom `WorkManager` `Configuration` via
  `HiltWorkerFactory`. WorkManager's default auto-init is **disabled in the manifest** so Hilt
  can inject workers — keep that manifest `<provider tools:node="remove">` block.
- It also inits `PDFBoxResourceLoader` (needed before any PDF text extraction) and schedules
  the periodic scan on startup.
- Hilt modules live in `di/`; everything shared (DB, DAO, scanner, engine, repositories) is
  `@Singleton`.

### UI (`ui/search/`)
Single-screen Compose app. `SearchViewModel` exposes StateFlows for query, results, recents,
scan state, and the AI-search toggle. Search is debounced (300 ms) and re-runs on query or
toggle change, routing to `semanticSearch` vs `search`. `isScanning` combines the periodic and
manual WorkManager job states (a periodic job sitting ENQUEUED between runs must not count).
`FilterState` applies source/type/date filters on top of results with AND semantics.

## Where to look
- Planning / design history: `../FileFinder_v1_plan.md` (repo root).
- Current search-tuning state and open work: the project memory file
  `patrifilefinder-search-tuning.md` (in Claude's memory dir) — check it before touching
  scoring constants or text-extraction quality.

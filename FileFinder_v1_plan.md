# FileFinder — v1 app plan

## What it is

A single Android app that indexes files across WhatsApp, Telegram, and browser downloads, then lets you find any file instantly using natural-language AI search or keyword search. All processing happens on-device — no cloud, no data leaving your phone.

---

## Features — v1 (core)

### 1. Universal file search
- Single search bar that queries all indexed sources simultaneously
- Supports keyword search (exact and partial match via FTS4)
- Supports AI semantic search — find files by describing what they contain, not just the filename
- Results show: file name, source badge (WhatsApp / Telegram / Downloads), file type icon, date added

### 2. Sources indexed
| Source | How |
|---|---|
| WhatsApp | Scans `/WhatsApp/Media/` — images, videos, docs, audio |
| Telegram | Scans `/Telegram/Telegram Documents/`, `/Telegram/Telegram Images/` |
| Browser downloads | Monitors standard `/Download/` folder |
| Full file system | `MANAGE_EXTERNAL_STORAGE` permission scans all accessible folders |

### 3. AI semantic search (on-device)
- Uses MiniLM-L6 TFLite model (~22 MB, bundled in the app)
- Generates a 384-dimension embedding for each file's name and extracted text snippet
- At query time, embeds the user's query and ranks results by cosine similarity
- Blended ranking: `score = 0.6 × semantic + 0.4 × keyword`
- Falls back to keyword-only on devices with less than 2 GB RAM

### 4. Smart filters
- Filter by source (All / WhatsApp / Telegram / Downloads)
- Filter by file type (image, video, PDF, document, audio)
- Filter by date range
- Filters are combinable

### 5. File preview and open
- Tap any result to preview inline: images via Coil, PDFs via Android's built-in `PdfRenderer`
- Tap to open with the native app via `Intent`
- Long press to share or copy file path

### 6. Background indexer
- `WorkManager` periodic job runs every 15 minutes
- Incremental: only processes files newer than last scan timestamp
- Runs only when battery is not low and device is not in a critical state
- First-time full scan shows a progress notification

---

## What is deferred to v2
- Gmail API integration
- Amazon order search
- Voice search
- Duplicate file detection
- Cloud sources (Google Drive, Dropbox)
- Share sheet target

---

## Architecture

### Pattern: MVVM + Repository + Clean layers

```
┌─────────────────────────────────┐
│         UI layer                │
│   Jetpack Compose screens       │
│   SearchScreen, FilterSheet,    │
│   PreviewScreen, HomeScreen     │
└────────────┬────────────────────┘
             │ StateFlow / events
┌────────────▼────────────────────┐
│       ViewModel layer           │
│   SearchViewModel               │
│   IndexViewModel                │
│   FilterState (sealed class)    │
└────────────┬────────────────────┘
             │ suspend functions
┌────────────▼────────────────────┐
│       Repository layer          │
│   FileIndexRepository           │
│   LocalSearchRepository         │
│   EmbeddingRepository           │
└────────────┬────────────────────┘
             │
┌────────────▼────────────────────┐
│     Data / System layer         │
│   Room DB (SQLite + FTS4)       │
│   FileScanner (MediaStore +     │
│     java.io.File)               │
│   TFLite Interpreter (MiniLM)   │
│   WorkManager (periodic scan)   │
│   FileObserver (real-time)      │
└─────────────────────────────────┘
```

### Room database schema

**`FileRecord` entity**
| Column | Type | Notes |
|---|---|---|
| id | Long (PK) | Auto-generated |
| name | String | File name |
| path | String | Absolute path on device |
| source | Enum | WHATSAPP, TELEGRAM, DOWNLOADS, OTHER |
| mimeType | String | e.g. `image/jpeg`, `application/pdf` |
| sizeBytes | Long | File size |
| dateAdded | Long | Unix timestamp |
| textSnippet | String? | First 500 chars of content (PDFs, TXT, DOCX) |
| embedding | ByteArray? | 384-float MiniLM embedding, stored as BLOB |
| openCount | Int | Tracks frequency for "recent/frequent" ranking |

**`FileRecordFts` virtual table**
- FTS4 on `name` + `textSnippet`
- Enables fast full-text keyword search via `MATCH` queries

### Key libraries
| Purpose | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Local DB | Room + FTS4 |
| Background jobs | WorkManager |
| Image loading | Coil |
| AI model runtime | TensorFlow Lite |
| Preferences | DataStore |
| Async | Kotlin Coroutines + Flow |

---

## Implementation plan — 4 weeks with Claude Code

### Week 1 — Foundation
**Goal:** App runs, files are indexed, basic search works.

1. Create Android project in Android Studio — min SDK 26, Kotlin, Compose, Material 3
2. Add Gradle dependencies: Room, Hilt, WorkManager, DataStore, Coil, TFLite
3. Define `FileRecord` entity + `FileRecordFts` virtual table + DAOs
4. Write `FileScanner` — walks `/Download/`, `/WhatsApp/Media/`, `/Telegram/` using `MediaStore` and `java.io.File`
5. Wire up `WorkManager` `PeriodicWorkRequest` (every 15 min, incremental)
6. Request `MANAGE_EXTERNAL_STORAGE` permission with rationale dialog
7. Build basic `SearchScreen` in Compose — search bar + `LazyColumn` result list

**Done when:** You can type a filename and see results from at least one source.

---

### Week 2 — Search quality + filters
**Goal:** Search is reliable, filters work, files open correctly.

1. Add FTS4 full-text search — keyword search across `name` and `textSnippet`
2. Add content extraction for PDFs (Android `PdfRenderer`) and TXT files → populate `textSnippet`
3. Build `FilterState` sealed class — source, file type, date range
4. Build filter bottom sheet UI — chips for source and type, date range picker
5. Wire filters into Room DAO queries
6. Build `PreviewScreen` — image preview (Coil), PDF first page, open-with Intent
7. Long press on result → share / copy path bottom sheet

**Done when:** You can filter by type and date, preview images and PDFs, and open any file.

---

### Week 3 — AI semantic search
**Goal:** Natural-language queries return meaningful results.

1. Download MiniLM-L6-v2 TFLite model, add to `assets/`
2. Build `EmbeddingEngine` — wraps `TFLite Interpreter`, handles tokenisation (BertTokenizer) and inference
3. During indexing: generate embedding for each file's `name + textSnippet`, store as BLOB in `FileRecord`
4. At search time: embed the query, load stored embeddings, compute cosine similarity in Kotlin
5. Blend scores: `final = 0.6 × semantic + 0.4 × FTS keyword score`
6. Add RAM check — skip embedding on devices with less than 2 GB RAM, fall back to keyword-only
7. Add "AI search" toggle in settings so user can switch modes

**Done when:** Searching "tax document last year" returns the right PDF even without exact filename match.

---

### Week 4 — Polish + stability
**Goal:** App feels complete, handles edge cases, ready for daily use.

1. Build `HomeScreen` — recent files (last 20 by date) + frequent files (by `openCount`)
2. Build onboarding flow — 3 screens, permission requests with rationale, source selection, first-index progress bar
3. Add `FileObserver` for real-time detection of new files (supplements the periodic job)
4. Handle edge cases: permission denied gracefully, missing files in index (stale records), empty search state
5. Add Timber logging throughout for debuggability
6. Profile with Android Profiler — check memory during embedding generation, CPU during large scans
7. Set `WorkManager` constraints: `requiresBatteryNotLow()`, test on a real device

**Done when:** App works reliably across a full day of normal phone use.

---

## Testing and debugging tools

### Development tools
| Tool | Purpose |
|---|---|
| Android Studio (Hedgehog+) | Main IDE. Use Compose Preview for live UI feedback without running the app |
| AVD Manager | Create a Pixel 6 API 33 emulator. Push test files via `adb push` |
| `adb shell` | Verify folder access: `adb shell ls /sdcard/WhatsApp/Media`. Push dummy files to emulator storage |
| Android Profiler | Memory tab during indexing, CPU tab during embedding. Built into Android Studio |
| DB Browser for SQLite | Pull the Room DB from emulator: `adb pull /data/data/com.yourapp/databases/`. Open locally to inspect rows and FTS index |

### Testing tools
| Tool | What to test |
|---|---|
| JUnit 5 + MockK | ViewModel logic, search score blending, query prefix routing, filter state |
| Room in-memory DB | `Room.inMemoryDatabaseBuilder` in `@RunWith(AndroidJUnit4)` tests — test FTS queries, DAO inserts, schema migrations |
| Compose UI Test | Search bar input, filter chip toggles, `LazyColumn` scroll, file open Intent dispatch |
| `TestWorkerBuilder` | Run `WorkManager` indexer synchronously in tests without scheduling delays (from `work-testing` library) |
| Hilt testing | `@HiltAndroidTest` swaps real repos with fakes — test full search flow end-to-end |
| Firebase Crashlytics | Add from day 1. Catches permission crashes, Room migration errors, and stale-file exceptions in the wild |

### Debugging specific problems
| Problem area | Tool and approach |
|---|---|
| Room schema issues | DB Browser for SQLite — pull DB, inspect tables and FTS virtual table content directly |
| FTS search returning wrong results | Run raw SQL in DB Browser: `SELECT * FROM FileRecordFts WHERE FileRecordFts MATCH 'query'` |
| Embedding quality | Log cosine similarity scores to Logcat (with Timber tag `EmbeddingEngine`) and verify top-K results make sense |
| WorkManager not firing | Use `adb shell cmd jobscheduler run -f com.yourapp <job_id>` to force-trigger the job |
| File scanner missing folders | `adb shell ls` to verify folder paths exist and are readable with current permissions |
| TFLite inference slow | TFLite Benchmark Tool APK — run on the actual target device before committing to the model |
| Memory pressure during scan | Android Profiler → Memory tab → watch heap during first full index of a large file system |

---

## Key gotchas

- **Room migrations** — every schema change needs a `Migration` object. Test with `MigrationTestHelper`. A bad migration crashes the app on update for all existing users. Write migrations as you go, not at the end.
- **First-time index on large phones** — users with 10k+ files will wait minutes. Run the first scan as a foreground `Service` with a progress notification so Android does not kill it.
- **`MANAGE_EXTERNAL_STORAGE` on Play Store** — requires a privacy policy and a clear use-case declaration in the Data Safety form. Write the privacy policy before submitting.
- **MiniLM on low-RAM devices** — check `ActivityManager.getMemoryInfo()` before loading the model. Gracefully degrade to keyword-only search below 2 GB.
- **FTS4 vs FTS5** — Room supports FTS4 by default on all API levels. FTS5 is faster but requires API 30+. Stick with FTS4 for v1 to keep compatibility broad.
- **Stale index entries** — files can be deleted from the file system but remain in the Room index. Add a cleanup pass in the indexer: for every `FileRecord`, check `File(path).exists()` and delete the record if false.

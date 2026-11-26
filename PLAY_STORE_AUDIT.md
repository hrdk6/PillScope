# PillScope — Pre-Play-Store Launch Audit & Flaw Inventory

> **Scope & Method:** Static end-to-end walkthrough of the entire PillScope monorepo (~17.5k lines across `android/`, `backend/`, `ml/`, `eval/`, and `infra/`) conducted from five launch perspectives: **Mobile QA / First-Time User**, **Play Store Release Engineer**, **Clinical Safety & ML Lead**, and **Backend Security & Infra/SRE**.
> *(Note: APK build/emulator and Docker runtime execution were not run in the audit environment because JDK, Android SDK/`adb`, `uv`, and Docker were not installed on the host.)*

**Executive Verdict:** PillScope has strong architectural ideas (monotonic safety banner locking, deterministic checks ahead of ML/LLM, offline Room caching, Murcko-scaffold DDI evaluation gates), **but the app is currently unshippable to the Google Play Store and non-functional end-to-end for a real user.** Every backend API requires a JWT, yet the Android client has **no authentication UI**, hardcodes `http://10.0.2.2:8000/` even in release builds, and silently swallows HTTP `401`/`422`/`500` errors as "Offline mode."

---

## Table of Contents

1. [P0 Play Store & Release Blockers](#1-p0-play-store--release-blockers)
2. [First-Time User & UX Walkthrough (Tab-by-Tab Flaws)](#2-first-time-user--ux-walkthrough-tab-by-tab-flaws)
3. [Clinical Safety, Edge-Vision, ML & RAG Flaws](#3-clinical-safety-edge-vision-ml--rag-flaws)
4. [Backend Security, API Contract & Concurrency Flaws](#4-backend-security-api-contract--concurrency-flaws)
5. [Database, Worker & Production Infra/Docker Flaws](#5-database-worker--production-infradocker-flaws)
6. [Dead / Half-Baked Code Inventory](#6-dead--half-baked-code-inventory)
7. [Prioritized Pre-Release Fix Checklist](#7-prioritized-pre-release-fix-checklist)

---

## 1. P0 Play Store & Release Blockers

These issues either make the app fail immediately on a real device or violate mandatory Google Play Store policies:

1. **Zero Login / Register / Token Refresh Flow in the Android App**
   - [PillScopeApp.kt](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L48-L150) launches straight to `Routes.SCAN` with only 4 bottom tabs (`scan`, `analysis`, `cabinet`, `schedule`).
   - [PillScopeApi.kt](android/app/src/main/java/com/pillscope/app/data/remote/PillScopeApi.kt#L35-L45) defines `register`, `login`, `refresh`, `getMe`, and [AuthTokenStore.kt](android/app/src/main/java/com/pillscope/app/data/remote/AuthTokenStore.kt#L14-L37) implements `SharedPrefsAuthTokenStore.saveTokens()`, **but zero screens or ViewModels call them**.
   - Meanwhile, [deps.py](backend/app/api/deps.py#L16-L48) (`get_current_user`) requires a valid Bearer access token on `/api/v1/profiles`, `/api/v1/drugs/*`, `/api/v1/cabinet/*`, and `/api/v1/scans/*`.
   - [AppModules.kt](android/app/src/main/java/com/pillscope/app/di/AppModules.kt#L50-L65) has **no OkHttp `Authenticator`** to call `/api/v1/auth/refresh` on `401` (access tokens expire in 30 minutes per [config.py](backend/app/core/config.py#L37)).

2. **Hardcoded Emulator URL + Cleartext HTTP in Release Builds**
   - [android/app/build.gradle.kts](android/app/build.gradle.kts#L21) sets `buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/\"")` globally in `defaultConfig` (not scoped to `debug`), so a Play Store release build points at an unreachable emulator IP.
   - [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml#L18) enables `android:usesCleartextTraffic="true"`, transmitting prescription, allergy, and scan data over unencrypted HTTP.

3. **Missing Launcher Icon (`mipmap` Assets)**
   - [android/app/src/main/res/](android/app/src/main/res) contains **only** `values/strings.xml` and `values/themes.xml`.
   - [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml#L12-L18) does not declare `android:icon` or `android:roundIcon` and has no adaptive launcher icons.

4. **Missing In-App & Server-Side Account / Health-Data Deletion (Play Store Policy)**
   - Google Play requires apps with account creation to provide an in-app and web-accessible way to delete the account and associated user data.
   - [auth.py](backend/app/api/auth.py#L40-L126) has no `DELETE /api/v1/auth/me` endpoint, and [profiles.py](backend/app/api/profiles.py#L141-L160) explicitly rejects deleting the user's default profile (`400 Bad Request`).

5. **Health Data / Third-Party AI Disclosure & Consent Missing**
   - [clinical_briefing.py](backend/app/services/clinical_briefing.py#L285-L314) sends `submitted_query`, `allergy_alerts` (including reactions), `duplicate_salts`, and interacting cabinet medications to third-party Gemini/OpenAI APIs, and [config.py](backend/app/core/config.py#L74) defaults Langfuse tracing to `https://cloud.langfuse.com`.
   - The Android app has **no onboarding privacy notice, medical disclaimer acknowledgment, privacy policy link, or AI-processing disclosure** before scanning.

6. **Release Build Hardening & Secret Storage Missing**
   - [android/app/build.gradle.kts](android/app/build.gradle.kts#L24-L32) sets `isMinifyEnabled = false` for `release` and defines no `signingConfig`.
   - [AuthTokenStore.kt](android/app/src/main/java/com/pillscope/app/data/remote/AuthTokenStore.kt#L14-L37) stores JWTs in plaintext `SharedPreferences` instead of `EncryptedSharedPreferences`.
   - [Theme.kt](android/app/src/main/java/com/pillscope/app/ui/theme/Theme.kt#L49-L170) only implements `lightColorScheme` with hardcoded light hex colors across screens—users with Android Dark Mode get bright white cards and hardcoded dark text.
   - **Toolchain mismatch:** [android/app/build.gradle.kts](android/app/build.gradle.kts#L11) sets `compileSdk = 37` with AGP `9.1.0` and Gradle `9.4.1` ([gradle-wrapper.properties](android/gradle/wrapper/gradle-wrapper.properties#L3)), while [android-builder.Dockerfile](infra/docker/android-builder.Dockerfile#L5-L21) still pins `GRADLE_VERSION=8.11.1` and `platforms;android-35`.

---

## 2. First-Time User & UX Walkthrough (Tab-by-Tab Flaws)

### Silent Failure Across All Repositories
Every Android repository wraps network calls in `runCatching { ... }.getOrNull()` ([ProfileRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt#L90-L180), [CabinetRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/CabinetRepository.kt#L177-L347), [ScanRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScanRepository.kt#L92-L174), [ScheduleAndRecallRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScheduleAndRecallRepository.kt#L35-L81)). HTTP `401`, `403`, `422`, and `500` errors look identical to being offline and never prompt the user to sign in or retry.

### Tab 1: Scan ([CameraScanScreen.kt](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt), [ScanViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt))
- **"Add to Cabinet" mode never adds the drug to the Cabinet:**
  - `ScanMode.ADD_TO_CABINET` ([ScanViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt#L26-L29)) only passes `persist = true` to `POST /api/v1/scans/analyze`.
  - [scan_analyzer.py](backend/app/services/scan_analyzer.py#L470-L495) only writes to `scan_logs`, and [ScanRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScanRepository.kt#L134-L141) only writes to `cached_scan_reports`. Neither calls `CabinetRepository.addMedicationOfflineFirst` or `POST /api/v1/cabinet/medications`, and `ProgressiveAnalysisScreen` has no "Save to Cabinet" button.
- **Sticky ghost OCR state:** [ScanViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt#L75-L80) preserves previous `rawOcrText` and `localSaltChips` whenever a subsequent frame is empty/unrecognized. Scanning Medicine A then typing Medicine B can silently attach Medicine A's `rawOcrText` to the analysis request.
- **Misleading salt chips:** Tapping one `AssistChip` in [CameraScanScreen.kt](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L364-L365) runs `buildAnalyzeRequestForLocalChips()`, which sends **all** `localSaltChips` together, with no way to edit or remove a misread chip.
- **Camera UX & lifecycle issues:**
  - Auto-launches the system `CAMERA` permission dialog immediately on launch ([L139-L143](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L139-L143)) with no rationale, and tapping `"Enable camera"` does nothing if permanently denied (no App Settings redirect).
  - [CameraPreviewWithAnalyzer](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L405-L458) lacks `cameraProvider.unbindAll()` on `DisposableEffect.onDispose`, has **no flashlight/torch toggle** (crucial for foil strips) and **no tap-to-focus**, and overlays OCR bounding boxes from the cropped analysis bitmap onto `PreviewView` without accounting for `PreviewView` scaling/cropping mismatch.

### Tab 2: Safety ([ProgressiveAnalysisScreen.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt), [AnalysisViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/AnalysisViewModel.kt))
- **Displays `"No interactions found"` in green during `CONNECTING` or `ERROR`:**
  - [ProgressiveAnalysisScreen.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L313-L319) falls into `else -> "No interactions found"` for any stage other than `IDLE` when `overallRisk == NONE`. If the SSE stream fails with `HTTP 401` or network drop (`AnalysisStage.ERROR`), the hero gauge claims `"No interactions found"` in green above a small amber error box.
- **Scan history is dead/unwired:** [AnalysisViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/AnalysisViewModel.kt#L51-L73) exposes `cachedHistory` and `openCachedReport()`, but `ProgressiveAnalysisScreen` never renders them.
- **Cross-profile & timestamp bugs in cache:** [ScanRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScanRepository.kt#L147-L199) does not filter `observeCachedReports` by `profileId` (leaking other family members' scans), fabricates `createdAtEpochMs = now - (idx * 1000L)` instead of parsing `log.createdAt`, and doesn't cache `activeRecalls` in `CachedScanReportEntity`.
- **Client-side recall filter false positives & false negatives:** [AnalysisViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/AnalysisViewModel.kt#L38-L48) filters recalls cached *for active cabinet meds* using `recall.matchedMedicationName.lowercase().contains(queryLower)`. Searching `"tab"` falsely flags any recalled `"* Tablet"` in the cabinet, while scanning a recalled medicine **not yet in the cabinet** shows zero recall warnings.
- **Non-clickable citations:** `citation.sourceUrl` in [ExpandableCitationCard](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L869-L873) is plain text, not an interactive link.

### Tab 3: Cabinet ([CabinetScreen.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt), [CabinetViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt), [CabinetNetworkGraph.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetNetworkGraph.kt))
- **Manual salt entry required for `+` Add Medication:** [AddMedicationDialog](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L402-L447) forces patients to manually type `"Generic salts (comma separated)"`—`PillScopeApi.searchDrugs` is completely unused.
- **Network Graph shows fake "Safe" links for manually added drugs:** [CabinetViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L76-L94) builds graph edges from `cached_scan_reports` and `dose_schedule`. Adding `Warf 5` (`warfarin`) and `Ecosprin 75` (`aspirin`) via `+` draws a **gray dashed `"Safe"` line** until the user also scans them or refreshes the schedule.
- **`MINOR` interactions downgraded to `SAFE`:** [CabinetGraphState.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetGraphState.kt#L324-L328) maps `MINOR` severity to `GraphEdgeSeverity.SAFE`.
- **Graph overlap & dead node taps:** Circular layout in a 380dp canvas ([CabinetNetworkGraph.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetNetworkGraph.kt#L92-L235)) overlaps 112dp pill nodes once a cabinet has ~6+ drugs; dragged positions aren't cleared when switching profiles; tapping a node shows no detail sheet (so clicking a pulsing recall dot gives no recall info); and tapping cards in List view opens nothing (`getMedicationDetail` API is unused).
- **Silent errors & accidental deletes:** [CabinetViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L174-L184) ignores the `Boolean` return values of `toggleMedicationActive` (which also returns `false` on `PENDING_CREATE` items at [CabinetRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/CabinetRepository.kt#L226-L228)) and `deleteMedication`. Delete has no confirmation dialog.

### Tab 4: Schedule ([ScheduleScreen.kt](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt), [ScheduleViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleViewModel.kt))
- **Schedule goes stale:** [ScheduleViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleViewModel.kt#L40-L46) only refreshes on `selectedProfileId` changes—adding/removing meds or editing allergies never refreshes the schedule unless the user manually taps Refresh.
- **Silent offline blank screen:** `ScheduleUiState.statusNotice` ([L59-L64](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleViewModel.kt#L59-L64)) is never displayed in [ScheduleScreen.kt](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt), so offline/unauthed failures render 5 empty `"No doses scheduled"` cards.
- **No reminders, check-offs, or custom meal/sleep times:** No `AlarmManager`/`WorkManager` notifications, no "Taken/Skipped" tracking, and slot times (`07:00`, `08:00`, `13:00`, `18:30`, `22:00`) are hardcoded in [dose_scheduler.py](backend/app/services/dose_scheduler.py#L22-L28).

### Profile Switcher ([ProfileSwitcherSheet.kt](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt), [ProfileRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt))
- **Cannot edit, rename, promote, or delete profiles in UI:** `ProfileViewModel.deleteProfile()` ([ProfileViewModel.kt](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileViewModel.kt#L94-L98)) and `statusMessage` are never wired into `ProfileSwitcherSheet`.
- **Pluralization bug:** `ActiveProfilePill` ([ProfileSwitcherSheet.kt](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L103)) hardcodes `"${profile.allergies.size} allergy"`.
- **Offline profile data loss & `422` loops:**
  - `ProfileEntity` has no `syncStatus`; [ProfileRepository.refreshProfiles](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt#L95-L96) runs `profileDao.clearAll(); profileDao.upsertAll(entities)`, silently deleting offline-created profiles and offline allergy changes.
  - Switching `_selectedProfileId` from `"default-local-profile"` to the server UUID strands offline medications/scans stored under `"default-local-profile"`.
  - [ProfileRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt#L156-L180) and [ScheduleAndRecallRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScheduleAndRecallRepository.kt#L35-L81) send non-UUID `"default-local-profile"` / `"local-profile-*"` IDs to UUID-validated `/api/v1/profiles/{profile_id}` endpoints (`422` errors swallowed).
  - [ProfileResponseDto.extractedAllergyLabels](android/app/src/main/java/com/pillscope/app/data/remote/dto/ApiDtos.kt#L98-L109) discards `reaction` and `severity` from structured `AllergyEntry` objects, and editing allergies overwrites them with plain strings.

---

## 3. Clinical Safety, Edge-Vision, ML & RAG Flaws

1. **Mid-Stream `MissingGreenlet` Crash in Safety SSE Pipeline**
   - In [scan_analyzer.py](backend/app/services/scan_analyzer.py#L375-L382), if `rag_retriever.retrieve(...)` raises an exception, the handler runs `await session.rollback()`, which expires `active_profile` in SQLAlchemy's identity map.
   - Accessing `active_profile.id` later at [L474](backend/app/services/scan_analyzer.py#L474) and [L500](backend/app/services/scan_analyzer.py#L500) on an `AsyncSession` triggers a synchronous lazy reload and raises `sqlalchemy.exc.MissingGreenlet` right before emitting `complete`.
   - Furthermore, `stream_scan_analysis` has no outer `try/except` to emit SSE `event: error` (which [ScanSseClient.kt](android/app/src/main/java/com/pillscope/app/data/remote/sse/ScanSseClient.kt#L86) expects).

2. **Empty Knowledge Base & Unbuilt ML Artifacts on Cold Start**
   - `ml/artifacts/` contains only `.gitkeep` (no `ddi_ensemble.joblib` in repo or Docker build), so `get_optional_ml_service` ([scans.py](backend/app/api/scans.py#L70-L81)) silently disables Tier 2 ML scoring.
   - `brand_salt_mappings`, `ddi_pairs`, `monograph_chunks`, and `recall_alerts` start empty (`data/` does not exist; only tiny sample fixtures of 20 active Indian brands, 3 drug labels, 4 recalls, and 40 SMILES exist in test fixtures).
   - Default config ([config.py](backend/app/core/config.py#L40-L43)) uses local `BAAI/bge-base-en-v1.5` and `BAAI/bge-reranker-base`, neither pre-cached in Docker—causing the **first live scan** to try downloading ~1.5 GB from HuggingFace inside the SSE stream.

3. **Edge Vision & On-Device OCR Gaps**
   - `label_quality_cropper.tflite` is trained exclusively on synthetic flat rectangles and dashes ([export_label_quality_model.py](ml/edge_vision/export_label_quality_model.py#L47-L108)), not real blister foil strips. Because [MedicineLabelAnalyzer.kt](android/app/src/main/java/com/pillscope/app/ml/MedicineLabelAnalyzer.kt#L62-L72) drops OCR completely when quality fails, shiny strips can block scanning with no manual override.
   - `extractPrimaryBrandGuess` ([MlKitOcrEngine.kt](android/app/src/main/java/com/pillscope/app/ml/MlKitOcrEngine.kt#L169-L176)) blindly uses the first OCR line (`"Rx"`, `"MRP"`, `"Batch"`), and `genericSaltSegmentRegex` ([L76-L79](android/app/src/main/java/com/pillscope/app/ml/MlKitOcrEngine.kt#L76-L79)) fails whenever strength isn't inline with the drug name.
   - Kotlin's [OnDeviceSaltChipExtractor](android/app/src/main/java/com/pillscope/app/ml/MlKitOcrEngine.kt#L11-L177) duplicates and diverges from Python's [drug_normalizer.py](backend/app/services/drug_normalizer.py#L62-L173) (fewer inorganic salts and counterions; both carry `"esomperazole"` typo).

4. **Combination Drugs & Safety Check Blind Spots**
   - **Combo monograph indexing bug:** `_extract_canonical_salt_from_openfda` ([monograph_ingestion.py](backend/app/services/monograph_ingestion.py#L152-L175)) returns only `extracted[0]`, so combination drugs like `Amoxicillin and Clavulanate` are never indexed under the second salt.
   - **Reactivating inactive meds skips safety checks:** `PATCH /api/v1/cabinet/medications/{id}` ([cabinet.py](backend/app/api/cabinet.py#L236-L273)) does not run `evaluate_duplicate_safety` when `is_active` is flipped back to `True`.
   - **Same-class therapeutic duplication missed:** [deterministic_safety.py](backend/app/services/deterministic_safety.py#L27-L65) only checks identical `canonical_salt` equality—not same-class duplication (e.g., two different NSAIDs or PPIs unless both are in `ddi_pairs`).
   - **Allergy checker limitations:** [allergy_checker.py](backend/app/services/allergy_checker.py#L10-L390) covers 25 static drug classes with exact canonical normalization (typos like `"penicilin"` silently fail to match `"amoxicillin"`), and the Android client does zero offline allergy checks.
   - **Unrecognized scans report `"NONE"`:** When an unrecognized brand has no `raw_ocr_text`, [scan_analyzer.py](backend/app/services/scan_analyzer.py#L179-L201) yields `resolved_salts = []` and emits `overall_risk = "NONE"`.

5. **Dose Scheduler Pharmacological & Frequency Bugs ([dose_scheduler.py](backend/app/services/dose_scheduler.py))**
   - `_parse_daily_doses` ([L322-L329](backend/app/services/dose_scheduler.py#L322-L329)) only parses `BID` (2x) and `TID` (3x). `QID`, `every 6 hours`, `every 8 hours`, and **`"1 tablet weekly"`** (e.g., alendronate) all collapse to 1 dose **daily**.
   - `simvastatin`, `lovastatin`, `pravastatin`, `fluvastatin`, and `montelukast` are placed inside `_SEDATING_SALTS` ([L147-L151](backend/app/services/dose_scheduler.py#L147-L151)), generating misleading rationale text calling statins *"Sedating or evening-optimal"*.

6. **Recall Watchdog False Positives & Permanent Stale Recalls ([recall_watchdog.py](backend/app/services/recall_watchdog.py))**
   - `_match_medication_to_recall` ([L521-L539](backend/app/services/recall_watchdog.py#L521-L539)) matches US FDA enforcement recalls by `canonical_salt` without country, manufacturer, or lot matching—triggering US FDA banners on Indian brands while ignoring Indian CDSCO recalls.
   - The ARQ cron ([settings.py](backend/app/workers/settings.py#L46-L48)) fetches only 5 pages (500 rows) of `status="Ongoing"` without date sorting and **never syncs `Terminated`/`Completed` status updates**, leaving ended recalls flagged as `"Ongoing"` forever.

7. **Silent LLM Fallback & Regex-Only Lifestyle Triggers**
   - When `GEMINI_API_KEY`/`OPENAI_API_KEY` are unset, `get_briefing_generator` ([clinical_briefing.py](backend/app/services/clinical_briefing.py#L442-L456)) silently drops to string-template `DeterministicGroundedBriefingGenerator` while still reporting `citation_verified = True`.
   - [lifestyle_triggers.py](backend/app/services/lifestyle_triggers.py#L76-L348) relies on narrow regexes and returns `[]` whenever `monograph_chunks` is empty for a salt.

---

## 4. Backend Security, API Contract & Concurrency Flaws

- **Stateless refresh tokens with no revocation:** [security.py](backend/app/core/security.py#L36-L68) includes `jti`, but [auth.py](backend/app/api/auth.py#L96-L121) never stores or invalidates `jti`s in Redis/Postgres. There is no `/logout`, password reset, or email verification; stolen refresh tokens remain valid for 14 days. Email enumeration is possible via `409 Conflict` on `/register` (`L48-52`) and `401` vs `403` on `/login` (`L79-88`).
- **Zero rate limiting / anti-abuse protection:** [main.py](backend/app/main.py) has no rate limiting on Argon2id endpoints (`/register`, `/login`), expensive scan/LLM streams (`/scans/analyze`), or `/internal/jobs/*` (which is mounted on the public FastAPI router at `L57`).
- **Weak production config validation:** [config.py](backend/app/core/config.py#L76-L92) skips secret checks unless `APP_ENV` is `staging`/`production` (Compose defaults `APP_ENV=development`), and still accepts `.env.example` placeholder strings (`"replace-with-at-least-32-bytes-random-secret"` is 44 chars), `pillscope_dev_password`, unauthenticated Redis, and empty LLM keys.
- **Validation & schema gaps:**
  - `MedicationCreateRequest.display_name` ([schemas/cabinet.py](backend/app/schemas/cabinet.py#L25)) accepts `"   "`, which [cabinet.py](backend/app/api/cabinet.py#L185) strips to `""` and persists. Passing `brand_mapping_id` + `generic_salts: []` triggers a `422` ([cabinet.py](backend/app/api/cabinet.py#L189-L200)).
  - `ScanAnalyzeRequest.edge_quality_metrics` ([safety.py](backend/app/schemas/safety.py#L28)) and list inputs (`confirmed_salts`, `allergies_json`, `generic_salts`) have no size bounds.
  - `ScanAnalyzeRequest` lacks `country_code` ([safety.py](backend/app/schemas/safety.py#L21-L28)).
  - `ScanLogResponse.resolved_salts` (`list[dict]`, [scans.py](backend/app/schemas/scans.py#L18)) conflicts with SSE `resolved_salts` (`list[str]`, [safety.py](backend/app/schemas/safety.py#L129)).
  - `/api/v1/profiles`, `/api/v1/cabinet/medications`, and `/api/v1/profiles/{id}/recalls` are unpaginated; `_hydrate_medications` ([cabinet.py](backend/app/api/cabinet.py#L73-L100)) reloads monograph chunks and recalls on every `GET`.
- **Race conditions & missing commits:**
  - Concurrent registration ([auth.py](backend/app/api/auth.py#L47-L62)) or concurrent `is_default=True` profile creation/update ([profiles.py](backend/app/api/profiles.py#L50-L120)) can throw unhandled `500 IntegrityError`.
  - `ensure_user_default_profile` ([profiles.py](backend/app/services/profiles.py#L72-L75)) calls `flush()` without `commit()` when called from `GET /api/v1/profiles`.

---

## 5. Database, Worker & Production Infra/Docker Flaws

- **No migrations or seeding on startup:** [backend.Dockerfile](infra/docker/backend.Dockerfile#L44) and [docker-compose.yml](infra/docker-compose.yml#L65) (`infra/docker-compose.yml` lines 65, 97) start `uvicorn`/`arq` without running `alembic upgrade head` or any catalog/DDInter seed job.
- **Broken telemetry in Docker:** `opentelemetry-exporter-otlp-proto-http` is only pulled transitively via `langfuse` in `[project.optional-dependencies] eval` ([pyproject.toml](pyproject.toml#L38-L42), [uv.lock](uv.lock#L1299-L1312)). Because [backend.Dockerfile](infra/docker/backend.Dockerfile#L11) runs `uv sync --frozen --no-dev --extra ml` (without `eval`), [otel.py](eval/tracing/otel.py#L46-L53) catches `ImportError` and silently disables OTLP and Langfuse export.
- **Docker image & Compose hygiene:**
  - Missing `.dockerignore`; `backend.Dockerfile` copies `backend/tests` and `eval/` into production containers.
  - [docker-compose.yml](infra/docker-compose.yml#L4-L35) (also lines 58, 95) exposes Postgres (`5432`) and unauthenticated Redis (`6379`) on host ports, uses `pillscope_dev_password`, and bind-mounts `../data:/app/data` (created as `root:root` if absent, breaking non-root `appuser` UID `10001`).
  - `api` healthcheck hits `/healthz` (always `200 ok`), `worker` has no healthcheck, and `/readyz` ([health.py](backend/app/api/health.py#L42-L53)) exposes raw exception strings publicly.
- **Schema & SQLite runtime traps:**
  - [0001_initial_schema.py](backend/app/db/migrations/versions/0001_initial_schema.py#L22-L24, L250) runs superuser `CREATE EXTENSION` and hardcodes `Vector(768)` ignoring `Settings.embedding_dim`.
  - `SmilesCacheStore` ([data_prep.py](ml/ddi/data_prep.py#L86-L109)) runs `CREATE TABLE IF NOT EXISTS` + `commit()` per lookup inside `asyncio.to_thread` without WAL mode, and [scan_analyzer.py](backend/app/services/scan_analyzer.py#L73-L119) loads all matching `DdiPair` rows instead of one SMILES per salt.
  - `WorkerSettings.job_timeout = 300` ([settings.py](backend/app/workers/settings.py#L53)) can kill 50-drug ingestion jobs mid-batch, and file-ingestion tasks ([tasks.py](backend/app/workers/tasks.py#L66-L158)) accept unrestricted local file paths.

---

## 6. Dead / Half-Baked Code Inventory

- **Android dead code:** `PillScopeApi.register`, `login`, `refresh`, `getMe`, `searchDrugs`, `getMedicationDetail`, `SharedPrefsAuthTokenStore`, `AllergyEntryDto`, `ProfileViewModel.deleteProfile`, `ProfileUiState.statusMessage`, `AnalysisViewModel.cachedHistory`, `AnalysisViewModel.openCachedReport`, `ScheduleUiState.statusNotice`.
- **Backend dead / test-only leakage:** `MedicationDetailResponse` ([schemas/cabinet.py](backend/app/schemas/cabinet.py#L70-L71)), `ingest_recalls_from_payload` ([recall_watchdog.py](backend/app/services/recall_watchdog.py#L343-L362)), `DdiMlInferenceService.score_pair` ([ddi_ml_inference.py](backend/app/services/ddi_ml_inference.py#L155-L156)), `HashingEmbeddingProvider`, `LexicalCrossReranker`, and runtime fallback to test fixture `ml/ddi/fixtures/drug_smiles_seed.csv` in [scan_analyzer.py](backend/app/services/scan_analyzer.py#L57-L59).
- **Room schema exports disabled:** [PillScopeDatabase.kt](android/app/src/main/java/com/pillscope/app/data/local/PillScopeDatabase.kt#L31) sets `exportSchema = false`.

---

## 7. Prioritized Pre-Release Fix Checklist

### Phase 1 — Make the App Work End-to-End
- [ ] Add Login / Register / Logout UI and route guard in `PillScopeApp`, backed by `EncryptedSharedPreferences` + OkHttp `Authenticator` calling `/api/v1/auth/refresh`.
- [ ] Stop swallowing HTTP errors (`401`/`422`/`500`) as offline mode; surface actionable auth/network banners.
- [ ] Wire `Add to Cabinet` scan mode (and a Safety screen CTA) to actually call `CabinetRepository.addMedicationOfflineFirst`, and replace manual salt typing in `AddMedicationDialog` with `/api/v1/drugs/search` autocomplete.
- [ ] Fix `ProgressiveAnalysisScreen` so `CONNECTING` and `ERROR` states never display `"No interactions found"`.
- [ ] Wire `cachedHistory` + `openCachedReport` into the UI, filter cached scans by `profileId`, and persist `activeRecalls`.
- [ ] Clear stale `rawOcrText` / `localSaltChips` when camera/query resets; add torch toggle, tap-to-focus, `unbindAll()`, and manual OCR fallback.

### Phase 2 — Google Play Store & Security Blockers
- [ ] Implement `DELETE /api/v1/auth/me` (plus web deletion URL) and in-app "Delete Account" + "Delete Profile" UI.
- [ ] Configure HTTPS production `API_BASE_URL` via build flavors/types, remove `android:usesCleartextTraffic="true"`, enable `isMinifyEnabled = true`, and add release signing config.
- [ ] Add adaptive launcher icons (`mipmap-*`) and `android:icon` / `android:roundIcon` in `AndroidManifest.xml`.
- [ ] Add onboarding medical disclaimer, privacy policy link, and consent before sending user queries/allergies to Gemini/OpenAI/Langfuse.
- [ ] Add Dark Mode color scheme (`isSystemInDarkTheme()`), and align `android-builder.Dockerfile` with `compileSdk = 37` / Gradle `9.4.1`.

### Phase 3 — Clinical Safety & Production Hardening
- [ ] Fix `session.rollback()` -> `MissingGreenlet` crash and emit SSE `error` events in `scan_analyzer.py`.
- [ ] Pre-ingest brand, DDI, and monograph datasets, bundle/train `ddi_ensemble.joblib`, and pre-bake or externally host embedding/reranker models.
- [ ] Fix combo-salt indexing in `monograph_ingestion.py`, enforce `evaluate_duplicate_safety` on medication reactivation (`PATCH`), and fix `dose_scheduler.py` (`QID`, `weekly`, statin rationale).
- [ ] Fix recall matching/termination sync, add backend rate limiting + refresh-token revocation (`jti`), harden `validate_production_secrets()`, run `alembic upgrade head` on boot, and fix Docker OTel dependencies (`opentelemetry-exporter-otlp-proto-http`).

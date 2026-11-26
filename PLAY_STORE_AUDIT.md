# PillScope — Pre-Play-Store Launch Audit & Flaw Inventory

> **Audited revision:** Commit `6a1eceb` (updating baseline audit `d87dd3f` after production hardening commits `1959a4a` & `6a1eceb`).
> **Scope & Verification:** Static audit across `android/`, `backend/`, `ml/`, `eval/`, and `infra/`, plus GitHub Actions CI run `45854081211` (all 4 CI jobs passed on `6a1eceb`: `android-build-and-test`, `lint-typecheck-and-pytest`, `docker-validate`, `safety-and-citation-regression-gate`). Local runtime builds/emulator tests were not executed on the audit host (no JDK, Android SDK, `uv`, or Docker installed), and CI did not run Android `lint` or `bundleRelease`.

**Executive Verdict:** Commits `1959a4a` and `6a1eceb` resolved almost all baseline P0 Play Store and end-to-end blockers (onboarding consent, auth/refresh/deletion, encrypted token storage, Dark Mode palette, core scan/cabinet/schedule UX bugs, `MissingGreenlet` crash, combo monograph indexing, scheduler fixes, and backend hardening). **However, PillScope is not yet safe to submit to Google Play:** there are still **two high-severity cross-user/sync & data-persistence bugs**, **a rate-limit bypass & dark-mode contrast defect**, **several offline/UX traps**, and **operational release blockers** (optional/silent unsigned AAB builds, empty `ml/artifacts/` & unbaked HF models, and no live HTTPS backend / web account-deletion form yet).

---

## Table of Contents

1. [What Was Fixed Since the Baseline Audit](#1-what-was-fixed-since-the-baseline-audit)
2. [New & Critical Bugs in Current Code (`6a1eceb`)](#2-new--critical-bugs-in-current-code-6a1eceb)
3. [Dark Mode Contrast & Android UI Polish Defects](#3-dark-mode-contrast--android-ui-polish-defects)
4. [Remaining Backend Security, ML & Infra Release Gaps](#4-remaining-backend-security-ml--infra-release-gaps)
5. [Prioritized Remaining Fix Checklist](#5-prioritized-remaining-fix-checklist)

---

## 1. What Was Fixed Since the Baseline Audit

- **Auth, Consent & Play Store Policy:** Added [OnboardingConsentScreen.kt](android/app/src/main/java/com/pillscope/app/ui/auth/OnboardingConsentScreen.kt#L46-L188), [AuthScreen.kt](android/app/src/main/java/com/pillscope/app/ui/auth/AuthScreen.kt#L64-L290), route gating in [PillScopeApp.kt](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L70-L120), Keystore AES-256-GCM encryption + `TokenRefreshAuthenticator` in [AuthTokenStore.kt](android/app/src/main/java/com/pillscope/app/data/remote/AuthTokenStore.kt#L55-L268), refresh-token revocation (`RevokedRefreshToken`) + `POST /logout` + `DELETE /api/v1/auth/me` in [auth.py](backend/app/api/auth.py#L44-L172), hosted `/privacy` & `/account-deletion` routes in [legal.py](backend/app/api/legal.py#L1-L92), release HTTPS enforcement + R8 minification in [build.gradle.kts](android/app/build.gradle.kts#L70-L90), [network_security_config.xml](android/app/src/main/res/xml/network_security_config.xml#L1-L4), adaptive launcher icons, Dark Mode palette in [Theme.kt](android/app/src/main/java/com/pillscope/app/ui/theme/Theme.kt#L79-L105), and JDK 21 / Gradle `9.4.1` in [android-builder.Dockerfile](infra/docker/android-builder.Dockerfile#L1-L24).
- **Android Tab UX & Offline-First Wiring:** Replaced silent error swallowing with [ApiErrorClassifier.kt](android/app/src/main/java/com/pillscope/app/data/remote/ApiErrorClassifier.kt#L11-L70); wired `"Add to Cabinet"`, unrecognized-drug state, retry button, cached scan history, and clickable monograph links in [ProgressiveAnalysisScreen.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L146-L358); added Settings redirect on permanent denial, torch, tap-to-focus, `Force scan`, `unbindAll()`, OCR noise-line skipping, and removable salt chips in [CameraScanScreen.kt](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L244-L650); added `/api/v1/drugs/search` autocomplete, medication detail sheet, delete confirmation, dual-ring graph layout, `MINOR` edge severity, and [OfflineAllergyChecker.kt](android/app/src/main/java/com/pillscope/app/data/repository/OfflineAllergyChecker.kt#L11-L188); wired profile edit/delete, allergy metadata preservation, and [DoseAdherenceStore](android/app/src/main/java/com/pillscope/app/data/repository/ScheduleAndRecallRepository.kt#L38-L69).
- **Clinical Safety, Backend & Infra:** Fixed mid-stream `session.rollback()` `MissingGreenlet`, added SSE error emission and scan-time recalls in [scan_analyzer.py](backend/app/services/scan_analyzer.py#L169-L345); indexed multi-salt openFDA combo monographs in [monograph_ingestion.py](backend/app/services/monograph_ingestion.py#L171-L276) and [0005_production_hardening.py](backend/app/db/migrations/versions/0005_production_hardening.py#L45-L55); added reactivation safety checks in [cabinet.py](backend/app/api/cabinet.py#L234-L263); added same-class therapeutic duplication in [deterministic_safety.py](backend/app/services/deterministic_safety.py#L113-L203); added fuzzy allergy checks in [allergy_checker.py](backend/app/services/allergy_checker.py#L533-L590); fixed `QID`/`weekly`/statin scheduling in [dose_scheduler.py](backend/app/services/dose_scheduler.py#L217-L420); added `Terminated`/`Completed` recall reconciliation + `ALLOWED_INGEST_DIR` guards in [tasks.py](backend/app/workers/tasks.py#L25-L185); added [RateLimitMiddleware](backend/app/core/rate_limit.py#L16-L111), placeholder secret rejection in [config.py](backend/app/core/config.py#L91-L122), OTLP exporter dependency in [pyproject.toml](pyproject.toml#L25), and Alembic/bootstrap startup in [entrypoint.sh](infra/docker/entrypoint.sh#L1-L14).

---

## 2. New & Critical Bugs in Current Code (`6a1eceb`)

### High Severity

1. **Cross-User Local Data Leakage & Account-Switch Crash/Sync Corruption**
   - [AuthenticatedPillScopeShell](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L124-L145) obtains `ProfileViewModel`, `ScanViewModel`, `AnalysisViewModel`, `CabinetViewModel`, and `ScheduleViewModel` via Activity-scoped `hiltViewModel()`, while [ProfileRepository._selectedProfileId](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt#L67) is an in-memory `@Singleton`.
   - [AuthRepository.logout](android/app/src/main/java/com/pillscope/app/data/repository/AuthRepository.kt#L172-L212) calls `database.clearAllTables()` but **never resets `ProfileRepository._selectedProfileId`, `DoseAdherenceStore`, or ViewModel UI state**.
   - Worse, when a session expires (`401` on refresh or `restoreSession`), [AuthRepository.restoreSession](android/app/src/main/java/com/pillscope/app/data/repository/AuthRepository.kt#L70-L73) and [AuthRepository.handleSessionExpired](android/app/src/main/java/com/pillscope/app/data/repository/AuthRepository.kt#L202-L205) clear tokens without wiping Room DB, and [AuthRepository.login](android/app/src/main/java/com/pillscope/app/data/repository/AuthRepository.kt#L122-L140) does not clear prior user data when a different account logs in. Account B can see Account A's cached Room records/ViewModels and upload Account A's `PENDING_*` items into Account B's cabinet.

2. **Login Startup Profile/Cabinet Sync Race & Cross-Profile Medication Reassignment Bug**
   - In [PillScopeApp.kt](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L142-L145), `LaunchedEffect(user.id)` launches `profileViewModel.refreshProfiles()` and `cabinetViewModel.syncCabinet()` concurrently (non-suspending `viewModelScope.launch` calls).
   - If `syncCabinet()` runs before `refreshProfiles()` finishes migrating offline profiles (`LOCAL_DEFAULT` or `local-profile-*`), [CabinetRepository.syncWithBackend](android/app/src/main/java/com/pillscope/app/data/repository/CabinetRepository.kt#L402-L426) sees `entity.profileId.startsWith("local-")` and rewrites `targetProfile` to `serverProfileId` (`?: null`), **silently uploading a secondary offline profile's prescriptions (e.g., `"Mom"`) into the `"Self"` server profile**.
   - Additionally, [ProfileRepository.refreshProfiles](android/app/src/main/java/com/pillscope/app/data/repository/ProfileRepository.kt#L201-L215) pushes offline `LOCAL_DEFAULT` allergies with `PATCH` to `serverDefault`, overwriting existing server allergies rather than unioning them.

3. **Backend Does Not Persist or Return `active_recalls` on `ScanLog` History**
   - Live SSE events ([DeterministicSafetyEvent](backend/app/schemas/safety.py#L106) and [ScanCompleteEvent](backend/app/schemas/safety.py#L145)) and Android's [ScanLogResponseDto](android/app/src/main/java/com/pillscope/app/data/remote/dto/ApiDtos.kt#L522) and Room schema include `active_recalls`.
   - However, [ScanLog](backend/app/db/models.py#L335-L389), [ScanLogResponse](backend/app/schemas/scans.py#L8-L33), and [stream_scan_analysis](backend/app/services/scan_analyzer.py#L539-L564) omit `active_recalls` entirely. Fetching scan history via `GET /api/v1/scans` (fresh install, second device, or post-logout sync) silently drops active FDA recall flags from historical reports.

### Medium Severity

4. **IP Rate-Limiter Bypassable via `X-Forwarded-For` Spoofing**
   - [RateLimitMiddleware._client_key](backend/app/core/rate_limit.py#L37-L45) trusts the leftmost IP in `X-Forwarded-For` from any client (demonstrated in [test_production_hardening.py](backend/tests/test_production_hardening.py#L423)), letting callers bypass auth/scan/admin limits by rotating fake headers when exposed directly or behind permissive proxies.
   - When Redis is unavailable, `_memory_buckets` ([rate_limit.py](backend/app/core/rate_limit.py#L63-L70)) never prunes stale client keys, and `/api/v1/drugs/*` + `/api/v1/cabinet/*` are excluded from throttling ([rate_limit.py](backend/app/core/rate_limit.py#L26-L35)).

5. **Profile Sheet UX Traps: Dropped Allergen Text & Unconfirmed Offline Data Wipe**
   - In [ProfileSwitcherSheet.kt](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L482-L520), typing an allergen into `"Add allergen or drug class"` (`customAllergen`) and tapping `"Save allergies"` without first pressing the small `+` icon button silently discards the typed allergen.
   - Tapping `"Sign Out"` ([ProfileSwitcherSheet.kt](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L638-L646)) triggers immediate sign-out (`database.clearAllTables()`) without a confirmation dialog, permanently deleting any unsynced `PENDING_CREATE`/`PENDING_UPDATE`/`PENDING_DELETE` queue entries if offline.

---

## 3. Dark Mode Contrast & Android UI Polish Defects

- **Low-contrast white text/icons on bright dark-mode accents:** [DarkPalette](android/app/src/main/java/com/pillscope/app/ui/theme/Theme.kt#L79-L104) defines `clinicalTeal = #4FD1C5` (light cyan) and `safeEmerald = #34D399` (bright mint), but multiple screens still hardcode `Color.White` on those backgrounds:
  - Cabinet `+` `FloatingActionButton`: `containerColor = PillScopeColors.ClinicalTeal`, `contentColor = Color.White` ([CabinetScreen.kt](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L91-L95)).
  - Schedule active timeline dots & `"Taken"` chips: `Color.White` on `ClinicalTeal` / `SafeEmerald` ([ScheduleScreen.kt](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L258-L268), [L385-L411](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L385-L411)).
  - Camera `"Force scan"` chip: `Color.White` on `ClinicalTeal` ([CameraScanScreen.kt](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L336-L343)).
- **Scan -> Cabinet workflow friction:**
  - In [PillScopeApp.kt](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L245-L250) and [CabinetViewModel.addMedicationFromScan](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L242-L259), `"Add to Cabinet"` discards `brandMappingId`/strengths and always sets `dosageSchedule = null`. Meanwhile [MedicationDetailBottomSheetContent](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L481-L621) is read-only—users cannot edit `dosageSchedule` or `displayName` afterward without deleting and re-adding via `+`.
  - [ProgressiveAnalysisScreen.kt](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L112) stores `addedForScanKey` in local composable `remember`, so navigating to another tab resets `"Saved"` back to `"Add to Cabinet"`, and [AddMedicationDialog](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L625-L736) saves before displaying duplicate/allergy alerts instead of warning inline.
  - [MedicationDetailBottomSheetContent](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L548) prints `"Firm: null"` when nullable `recall.recallingFirm` is null.
- **Navigation, camera & Auth minor UX issues:**
  - [PillScopeApp.kt](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L180-L221) calls `navController.navigate(route) { launchSingleTop = true }` without `popUpTo` / `saveState` / `restoreState`, stacking every bottom-tab tap onto the System Back stack.
  - [CameraScanScreen.kt](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L607-L694) overlays normalized OCR boxes onto full container dimensions while `PreviewView` uses `ScaleType.FIT_CENTER` (causing misalignment if aspect ratios differ), and `torchEnabled` ([L145-L285](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L145-L285)) stays `true` after background pause shuts off the hardware flash.
  - [AuthScreen.kt](android/app/src/main/java/com/pillscope/app/ui/auth/AuthScreen.kt#L228-L231) configures `ImeAction.Done` on the password field without `keyboardActions` to submit the form.

---

## 4. Remaining Backend Security, ML & Infra Release Gaps

- **Silent unsigned release builds:** [android/app/build.gradle.kts](android/app/build.gradle.kts#L58-L85) skips assigning `signingConfig` when `PILLSCOPE_KEYSTORE_*` env/Gradle properties are missing, so `./gradlew bundleRelease` silently outputs an unsigned AAB instead of failing fast.
- **Keystore fallback stores plaintext prefix:** [EncryptedAuthTokenStore.encrypt](android/app/src/main/java/com/pillscope/app/data/remote/AuthTokenStore.kt#L112-L123) silently falls back to `plain:v1:<token>` in `SharedPreferences` if Android Keystore throws an exception.
- **ML evaluation threshold lowered & production data/weights still not bundled:**
  - `ml/artifacts/` still holds only `.gitkeep`; commit `6a1eceb` lowered CI `--min-macro-f1` from `0.75` to `0.65` in [.github/workflows/ci.yml](.github/workflows/ci.yml#L105-L114) because the 40-row fixture model scored `0.6859` in CI run `45852262517` (still failing the clinical `0.75` target on full data).
  - [docker-compose.yml](infra/docker-compose.yml#L54-L55) defaults `RUN_BOOTSTRAP_SEED=false`; [bootstrap_db.py](backend/scripts/bootstrap_db.py#L21-L82) falls back to tiny test fixtures when `data/` is absent and does not ingest openFDA monographs; default `BAAI/bge-base-en-v1.5` & `BAAI/bge-reranker-base` ([reranker.py](backend/app/services/reranker.py#L22-L32), [embeddings.py](backend/app/services/embeddings.py)) are not pre-cached in Docker.
  - [get_briefing_generator](backend/app/services/clinical_briefing.py#L460-L480) still falls back silently to string templates if LLM keys are missing while marking summaries `citation_verified=True`.
- **Hosted Play Console requirements & scope limitations:**
  - `PRIVACY_POLICY_URL` & `ACCOUNT_DELETION_URL` default to `https://api.pillscope.app/*`, which must be live over valid TLS before Play review; [legal.py](backend/app/api/legal.py#L70-L81) documents `DELETE /api/v1/auth/me` via Bearer JWT rather than providing a browser-usable web deletion form/support contact for users who have uninstalled the app.
  - [OfflineAllergyChecker.kt](android/app/src/main/java/com/pillscope/app/data/repository/OfflineAllergyChecker.kt#L12-L50) covers 16 drug classes vs. 25 on [allergy_checker.py](backend/app/services/allergy_checker.py#L12-L390) (missing beta-blockers, corticosteroids, antihistamines, etc. while offline).
  - [ScheduleAndRecallRepository.kt](android/app/src/main/java/com/pillscope/app/data/repository/ScheduleAndRecallRepository.kt#L38-L69) tracks check-offs locally with no `AlarmManager`/`WorkManager` dose notifications, and [recall_watchdog.py](backend/app/services/recall_watchdog.py#L525-L570) matches US FDA recalls by salt without Indian CDSCO coverage.
  - [0001_initial_schema.py](backend/app/db/migrations/versions/0001_initial_schema.py#L22-L24) (also [line 250](backend/app/db/migrations/versions/0001_initial_schema.py#L250)) still runs `CREATE EXTENSION` (requires superuser on managed Postgres) and hardcodes `Vector(768)`, while [android-builder.Dockerfile](infra/docker/android-builder.Dockerfile#L21) pairs `platforms;android-37` with `build-tools;36.0.0`.

---

## 5. Prioritized Remaining Fix Checklist

### Before Closing Beta / Play Console Submission
- [ ] Reset `ProfileRepository._selectedProfileId`, `DoseAdherenceStore`, and Activity-scoped ViewModel state on logout/account switch, clear Room DB when switching users (`user.id` changes), and run `refreshProfiles()` sequentially before `cabinetViewModel.syncCabinet()`.
- [ ] Merge `LOCAL_DEFAULT` allergies with server default profile allergies instead of overwriting, and refuse to upload `PENDING_CREATE` medications for `local-*` profiles onto another profile ID.
- [ ] Persist & return `active_recalls` in `ScanLog`, Alembic migration `0006`, `ScanLogResponse`, and `stream_scan_analysis`.
- [ ] Fix `ProfileSwitcherSheet.kt` to auto-include non-blank `customAllergen` text on `"Save allergies"` and show a confirmation warning on `"Sign Out"` when offline changes are pending sync.
- [ ] Fix Dark Mode contrast (`onPrimary` / `onTertiary` instead of `Color.White` on `ClinicalTeal` / `SafeEmerald`), guard nullable `recallingFirm`, and make release signing fail fast when keystore variables are missing.

### Before Public Production Launch
- [ ] Harden `RateLimitMiddleware._client_key` with trusted-proxy validation + memory eviction + `/drugs/*` & `/cabinet/*` coverage.
- [ ] Train and bundle full `ddi_ensemble.joblib` meeting `--min-macro-f1 0.75`, seed full brand/DDI/openFDA datasets via `bootstrap_db.py`, pre-cache HF models, and add web-accessible deletion handling on `/account-deletion`.
- [ ] Add `dosageSchedule` editing on Cabinet detail sheet, `NavigationBar` `popUpTo`/`saveState`, and optional `WorkManager` / `AlarmManager` dose reminders.

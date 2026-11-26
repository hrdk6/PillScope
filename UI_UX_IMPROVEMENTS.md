# PillScope — UI/UX Engineering Review & Improvement Plan

> **Reviewed revision:** Commit `996ec00` (code identical to `f8e3035` in `android/` and `backend/`)
> **Verification scope:** Built `app-debug.apk` (`./gradlew --no-daemon assembleDebug`) and interactively tested on an Android 36 `medium_phone` emulator (`emulator-5554`) in Light & Dark modes against a local HTTP/SSE fixture stub (covering Onboarding/Consent, Auth, Camera Scan permission/HUD/search, Progressive Safety Analysis, Medication Cabinet List & Network Graph, Daily Schedule, and Profile Switcher), combined with a full source walkthrough of Compose UI, ViewModels, repositories, theme tokens, and web legal pages (`backend/app/api/legal.py`).

---

## Executive Summary

PillScope's Material 3 clinical theme (`#0B5D5B` / `#4FD1C5` teal, slate surfaces, semantic safety cards, and timeline layout) looks cohesive and approachable. However, live end-to-end testing uncovered **several high-impact visual collisions, cross-profile state inconsistencies, and interaction traps** that directly affect medication safety comprehension and day-to-day usability.

| Priority | Theme | Count |
| --- | --- | --- |
| **P0** | Visual collisions, cross-profile state leaks, rotation data loss & misleading graph semantics | 5 |
| **P1** | Layout occlusion, disabled-state styling, camera UX, safety flow ordering & form traps | 9 |
| **P2** | Dark mode WCAG AA contrast, schedule ergonomics, patient-friendly copy & Compose deprecations | 7 |

---

## P0 — Critical Visual Collisions, Clinical Context & State Bugs

### 1. Risk Gauge Headline Collides with Needle & Pivot Dot (`Safety`)
- **Observed (Live Emulator — Light & Dark Mode):** Inside the gauge on `ProgressiveAnalysisScreen`, the centered black/white pivot circle sits directly on top of `"Major risk"` / `"Ready to scan"`, and the needle arm cuts through the text.
- **Root Cause:** [`RiskGaugeHeroCard`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L607-L695) aligns both the `Canvas` (whose semicircle center is at `strokeWidth / 2f + diameter / 2f` near the bottom edge of the `126.dp` canvas) and the `riskHeadline` `Column` to `Alignment.BottomCenter` inside the same `Box`.
- **Recommended Fix:** Place the `riskHeadline` and `"Offline cached report"` `Column` directly **below** the `Canvas` (or increase `Box` height and offset the text cleanly below the pivot/arc baseline) so the needle never intersects typography at any risk level (`0%`–`100%`).

### 2. `"Enable camera"` Button Collides with HUD Status Pill (`Scan` without Camera Permission)
- **Observed (Live Emulator):** When camera permission has not been granted yet, the `"Align medicine label inside brackets"` pill overlaps the bottom half of the `"Enable camera"` CTA button. Meanwhile, the bottom-right circular camera shutter button renders at full solid teal + white border, looking completely enabled even though permission is denied and `canCapture` is `false`.
- **Root Cause:** [`CameraScanScreen`](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L229-L408) renders the bottom HUD `Column` (`QualityHudStatusPill`, `Force scan`, `"Checking against ...'s cabinet"`, and shutter button) unconditionally inside the viewfinder `Box`, even in the `!hasCameraPermission` branch.
- **Recommended Fix:** Gate the bottom HUD overlay (`QualityHudStatusPill`, `Force scan`, cabinet notice, and shutter button) on `if (hasCameraPermission)`, and visually dim/disable the shutter button when `!canCapture`.

### 3. Switching Family Profiles Leaks Prior Safety Report & Fails to Sync Cabinet
- **Observed (Live Emulator):**
  1. Switching from `Self` to `Mom` in `ProfileSwitcherSheet` updated the top-right pill to `Mom (2 allergies)`, but navigating to the **Safety** tab still showed `Self`'s `Ecosprin 75` (`aspirin + warfarin`) locked crimson alert—even though `Mom` does not take Warfarin and has an `NSAIDs` allergy. Tapping **Add to Cabinet** from that stale report would save `Self`'s scanned drug into `Mom`'s cabinet.
  2. Switching to `Mom` automatically fetched and displayed `Mom`'s **Schedule** (`Dolo 650` twice daily), while `Mom`'s **Cabinet** tab showed `"This profile has no saved medications yet"` until manually tapping the top-right Sync icon.
- **Root Cause:**
  - [`AnalysisViewModel`](android/app/src/main/java/com/pillscope/app/ui/analysis/AnalysisViewModel.kt#L31-L66) merges `profileRecalls` for the active `selectedProfileId` into `rawAnalysisState`, but never resets `rawAnalysisState`, `_lastRequest`, or `_savedScanKeys` when `selectedProfileId` changes.
  - [`CabinetViewModel`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L116-L131) clears selection and alerts on `selectedProfileId` changes, but unlike [`ScheduleViewModel`](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleViewModel.kt#L56-L74), it never triggers `syncCabinet()` for the newly selected profile.
- **Recommended Fix:** Observe `profileRepository.selectedProfileId` in `AnalysisViewModel` to clear or scope `rawAnalysisState`/`_savedScanKeys` per profile, explicitly show the target profile name on Safety actions (e.g., `"Add to Mom's Cabinet"`), and automatically sync Cabinet when switching to an unsynced server profile.

### 4. Configuration Changes (Rotation / Dark Mode Switch) Wipe Active Scan & Safety Reports
- **Observed (Live Emulator):** Toggling Dark Mode while viewing an active Safety report recreated `MainActivity` and immediately reset the screen back to `"No active analysis"`.
- **Root Cause:** [`AuthenticatedPillScopeShell`](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L143-L153) runs `LaunchedEffect(user.id)` on every entry into composition, calling `scanViewModel.clearScanState()` and `analysisViewModel.resetForAccountChange()` even when the signed-in `user.id` has not changed.
- **Recommended Fix:** Store the last initialized `user.id` inside a ViewModel or `rememberSaveable` and only reset state when `user.id` actually changes across accounts.

### 5. Network Graph Clips Its Legend Off-Screen & Shows Unanalyzed Pairs as `"Safe"`
- **Observed (Live Emulator):**
  - When an FDA Recall banner and `"Synced"` chip are present on the Cabinet tab, the entire `FlowRow` legend (`Major · Moderate · Minor · Safe · Tap colored curve for details`) and `"View details for ..."` button are pushed off-screen below the bottom navigation bar.
  - Active medications added directly in Cabinet (or synced without local scan history) draw dashed gray `"Safe"` curves between them until a full scan runs, creating a false sense of safety.
  - `android layout` shows zero accessible UI elements inside the graph `Canvas`.
- **Root Cause:**
  - [`CabinetScreen`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L105-L276) places `CabinetNetworkGraph` (`height(380.dp)`) inside a non-scrollable `Column` below fixed alert banners.
  - [`CabinetViewModel.graphState`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L77-L109) populates graph edges solely from `scanRepository.observeCachedEvidenceForProfile`, and [`CabinetGraphState.build`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetGraphState.kt#L184-L252) defaults every unmatched pair to `GraphEdgeSeverity.SAFE` (and silently excludes inactive medications at [`L100`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetGraphState.kt#L100)).
- **Recommended Fix:** Make the Network tab scrollable (or flex-size the canvas with `weight(1f)`), add a distinct `"Not analyzed yet"` edge state with a `"Check cabinet interactions"` CTA, show a `"N paused medications hidden"` badge when inactive drugs exist, support pinch/zoom or layout reset for 6+ drugs, and expose TalkBack semantics for nodes/edges.

---

## P1 — High-Impact Layout, Navigation & Interaction Traps

### 6. Onboarding Primary CTA Hidden Below the Fold & Checkbox Labels Unclickable
- **Observed (Live Emulator):** On initial launch (`1080×2400`), `"Continue to Sign In"` is completely pushed off-screen below `Privacy Policy` / `Data Deletion Info` with no scroll cue. Inspecting `android layout` confirmed only the small `Checkbox` box (`[54,1804][180,1930]`) is clickable; tapping the multi-line consent label text does nothing.
- **Location:** [`OnboardingConsentScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/auth/OnboardingConsentScreen.kt#L123-L185)
- **Fix:** Pin the two consent checkboxes and `"Continue to Sign In"` CTA inside `Scaffold(bottomBar = ...)` (with tighter scannable bullet copy in the cards), and wrap each checkbox `Row` with `Modifier.toggleable(..., role = Role.Checkbox)`. Apply the same row-level `.toggleable` fix to `"Set as default profile"` in [`ProfileSwitcherSheet.kt`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L372-L382).

### 7. Cabinet `+` FAB Overlaps Bottom Cards & Banners Stay Stuck
- **Observed (Live Emulator):** Even with 3 items (`Warf 5`, `Thyronorm 50`, `Shelcal 500`), the `+` `FloatingActionButton` overlaps the bottom-right corner of the last card; with 4+ medications it occludes the `Switch` and `Delete` icon button. Meanwhile, `"Synced (3 medications up to date)"` and `"Safety override triggered"` stay pinned permanently with no dismiss button and do not clear if you delete the conflicting medication.
- **Locations:** [`CabinetScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L94-L103), [`L213-L301`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L213-L301), [`CabinetViewModel.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L375-L391)
- **Fix:** Add `contentPadding = PaddingValues(bottom = 88.dp)` to `LazyColumn`, move status/override banners into `LazyColumn` items (or auto-dismiss transient sync banners), add a close (`×`) button, and clear stale duplicate/allergy override banners on delete or toggle-off.

### 8. Disabled Dialog & Shutter Buttons Look Visually Active
- **Observed (Live Emulator):** In the `"Add to cabinet"` dialog with empty inputs, `"Save"` renders in full-saturation dark teal (`#0B5D5B`), appearing clickable while silently doing nothing when tapped.
- **Locations:**
  - [`CabinetScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L855-L870) (`AddMedicationDialog` `"Save"` / `"Save anyway"`)
  - [`ProgressiveAnalysisScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L373-L391) (`"Save"` / `"Save anyway"`)
  - [`ProfileSwitcherSheet.kt`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L767-L775) (`"Delete forever"` stays bright crimson before `DELETE` is typed)
  - [`CameraScanScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L380-L407) (shutter `Surface` keeps full-opacity color/white border when `enabled = false`)
- **Fix:** Pass `ButtonDefaults.textButtonColors(contentColor = ..., disabledContentColor = ...)` instead of hardcoding `color` on `Text`, and dim container/border/icon alpha when `!canCapture`.

### 9. Camera Viewfinder Framing, Mode Label Confusion & OCR Instability (`Scan`)
- **Observed (Live Emulator):**
  - `PreviewView.ScaleType.FIT_CENTER` leaves wide black pillarboxes on the left and right of the `352.dp` viewfinder, while corner brackets protrude into the black edges and touch `ActiveProfilePill`.
  - `"Checking against Self's cabinet"` renders as bare white text with no dark pill/scrim behind it—illegible over bright surfaces or blister packs.
  - Candidate cards (`Ecosprin 75`) drop dosage strengths (`genericSalts`), while `localSaltChips` show strengths.
- **Code/Flow Issues:**
  - **Mislabeled toggle:** `"Add to Cabinet"` vs `"Check Before Buying"` ([`CameraScanScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L412-L433), [`ScanViewModel.kt`](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt#L27-L30)) only toggles `persist` on scan history—it does **not** add the medication to Cabinet. Rename to `"Save to History"` / `"Quick Check"` or automatically offer/complete cabinet addition.
  - **Shutter ambiguity & viewport squeeze:** The `352.dp` camera preview pushes candidates below the fold with no `"N matches below"` badge, and tapping the shutter blindly submits `resolvedCandidates.firstOrNull()` ([`buildAnalyzeRequestForShutter`](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt#L195-L220)).
  - **`Force scan` flicker:** [`MedicineLabelAnalyzer`](android/app/src/main/java/com/pillscope/app/ml/MedicineLabelAnalyzer.kt#L99) forces `isAcceptable = true` for one frame, then 350ms later the next live frame resets to blurry and clears `ocrTextBlocks` ([`ScanViewModel.kt`](android/app/src/main/java/com/pillscope/app/ui/scan/ScanViewModel.kt#L126)); tapping `Force scan` while paused also unfreezes the camera (`L116`).
  - **Search threshold & camera overwrite:** Queries `< 3` characters (`L80`) show no hint, and clearing manual search lets an unfrozen camera immediately overwrite results.

### 10. Safety Tab Scroll Clipping, Action Ordering & History UX
- **Observed (Live Emulator):**
  - Scrolled cards slice bluntly beneath the pinned crimson banner with no top spacing/divider, and the pinned banner stays non-scrollable even if multiple allergies, duplicates, DDI rules, and recalls stack simultaneously ([`ProgressiveAnalysisScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L131-L138)).
  - `"Add to Cabinet anyway (review alert)"` appears *above* the interaction explanation and clinical summary (`L182-L210`), encouraging action before the user reads the warning.
  - Empty `IDLE` state shows inactive pipeline chips with no `"Scan a medicine"` CTA button.
- **Additional Code Gaps:**
  - If ML alone locks `safetyBannerLocked` without a `deterministicAlerts` entry, `PinnedCriticalSafetyBanner` displays an empty crimson header (`L467-L475`).
  - `!state.persisted` suppresses the streaming `LoadingIndicator` in `RiskGaugeHeroCard` (`L587-L604`).
  - Tapping an entry in `"Recent scans"` (`L316-L322`) loads the report at the top of the screen without scrolling back up, and `cachedHistory.take(10)` conflicts with the header count `"Recent scans (${cachedHistory.size})"`.
  - `savedScanKeys` ([`AnalysisViewModel.kt`](android/app/src/main/java/com/pillscope/app/ui/analysis/AnalysisViewModel.kt#L33)) still displays `"Saved to this profile's medication cabinet"` even after deleting the drug from Cabinet.

### 11. Auth Field Error States & First-Run Offline Lockout
- **Observed (Live Emulator):** Submitting an invalid email shows `"Enter a valid email address."` in the bottom error box without setting `isError = true` on the Email `OutlinedTextField`, leaving the Password field highlighted in active teal ([`AuthScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/auth/AuthScreen.kt#L177-L260)).
- **Offline Pharmacy Trap:** [`AuthRepository.restoreSession`](android/app/src/main/java/com/pillscope/app/data/repository/AuthRepository.kt#L56-L102) only allows offline access for returning users; first-time launches without network cannot reach the on-device scanner or allergy checker. For returning offline users (`offlineResumed = true`), [`PillScopeApp.kt`](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L116) hides offline status inside `ProfileSwitcherSheet` instead of showing a top-level offline chip/banner.

### 12. Profile Switcher Dismissal, Allergen Parsing & Detail Sheet Gaps
- **Observed (Live Emulator):** The default profile subtitle reads `"Self, Penicillin"`, duplicating the `"Self"` heading, and `"Sign Out"` / `"Delete Account"` sit tight against the bottom navigation gesture bar ([`ProfileSwitcherSheet.kt`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L283-L293), [`L644-L678`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L644-L678)).
- **Code Issues:**
  - [`PillScopeApp.kt`](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L287-L290) closes `ProfileSwitcherSheet` immediately on `onSaveAllergies`—hiding any save error—whereas `onCreateProfile`, `onEditProfile`, and `onDeleteProfile` keep it open.
  - Tapping the `+` allergen button ([`L497-L505`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L497-L505)) does not split on commas, while `"Save allergies"` (`L515-L525`) does; unsaved chip edits are silently dropped on dismiss/switch; and tapping `"Edit"` opens the inline form below all profile rows (`L334-L414`), potentially off-screen.
  - [`EdgeInteractionDetailSheet`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetNetworkGraph.kt#L445) lacks `verticalScroll`, clipping long monograph excerpts/links, and [`MedicationDetailBottomSheetContent`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L492-L703) lacks Delete / Active controls (making them unreachable from Network view) and cannot edit generic salts.

### 13. Add/Edit Dosage Input Lacks Quick Presets & Salt Normalization Guard
- Both [`AddMedicationDialog`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L804-L817) and [`ProgressiveAnalysisScreen`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L363-L369) use free-form text for `Dosage schedule` with no quick-pick frequency chips (`Once daily`, `Twice daily`, `Morning`, `Bedtime`, `With food`). Additionally, typing strength into `"Generic salts"` (e.g., `"acetaminophen 650mg"`) stores the strength string inside `canonicalSalt` ([`CabinetViewModel.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetViewModel.kt#L275-L279)).

### 14. Missing `imePadding()` & Web Legal Page Polish
- Scrollable dialogs/sheets (`AuthScreen`, `ProfileSwitcherSheet`, `AddMedicationDialog`, `ProgressiveAnalysisScreen`'s Add dialog) do not apply `imePadding()` / `navigationBarsPadding()` consistently.
- External `uriHandler.openUri(...)` calls silently swallow failures (`runCatching { ... }`) without a fallback toast/snackbar.
- [`backend/app/api/legal.py`](backend/app/api/legal.py#L18-L176) lacks `@media (prefers-color-scheme: dark)` styling and clears the typed email field when `/account-deletion` form submission fails authentication.

---

## P2 — Dark Mode Contrast, Schedule Ergonomics & Polish

### 15. Dark & Light Mode WCAG AA Contrast Defects
- **Observed (Live Emulator — Dark Mode):** `"2 allergies"` in `ActiveProfilePill` renders `#EF4444` text on `#3F151A` (~3.1:1), appearing dim and hard to read; the same low-contrast red-on-maroon pairing occurs on `"FDA Recall"` badges ([`CabinetScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L418)) and `"Major"` pills ([`ProgressiveAnalysisScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L970)). Use `CriticalCrimsonDark` (`#FECACA`) in Dark Mode.
- **Additional Token Audit ([`Theme.kt`](android/app/src/main/java/com/pillscope/app/ui/theme/Theme.kt#L65-L94)):**
  - Hardcoded `Color.White` text/icons on Dark Mode `CriticalCrimson` (`#EF4444`, ~3.76:1) fail WCAG AA for normal body text (`PinnedCriticalSafetyBanner`, Recall Alert banner, `"Delete Account"` button).
  - [`CameraScanScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/scan/CameraScanScreen.kt#L403) hardcodes `tint = Color.White` on the dark-mode `#4FD1C5` shutter button (~1.9:1); use `MaterialTheme.colorScheme.onPrimary`.
  - Light Mode `Moderate` pill uses `#C46A08` over 12% tint (~3.4:1); use `ModerateAmberDark` (`#7C3F00`).

### 16. Schedule Card Spacing, Recall Visibility & Empty-State Guidance
- **Observed (Live Emulator):**
  - On [`ScheduleScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L354-L414), Compose's `48.dp` minimum touch-target padding around the small `"Take"` pill inflates the top `Row` height, leaving an awkward blank vertical gap between `"Thyronorm 50"` and `"levothyroxine"`.
  - `Warf 5` at Bedtime displays **no FDA Recall warning** on the Schedule timeline—users can mark a recalled medication `"Taken"` with zero visual caution.
  - Multi-line slot headers like `"Doses separated automatically for optimal absorption."` wrap awkwardly beside the Refresh button.
- **Code Gaps:**
  - When medications have `dosageSchedule == null` (e.g., saved from Scan without entering a schedule), [`DefaultEmptySlots`](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L57-L75) renders 5 `"No doses scheduled"` rows with no explanation or `"Set dosage in Cabinet"` CTA.
  - Missing daily header with today's date & adherence summary (`"1 of 3 doses taken today"`), [`DoseAdherenceStore`](android/app/src/main/java/com/pillscope/app/data/repository/ScheduleAndRecallRepository.kt#L45-L68) does not refresh across midnight while open, `dose.rationale.firstOrNull()` ([`L476`](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L476)) drops secondary notes, and `String.format("%.1fh", hours)` ([`L451`](android/app/src/main/java/com/pillscope/app/ui/schedule/ScheduleScreen.kt#L451)) omits `Locale.US`.

### 17. Patient-Friendly Copy & Navigation Consistency
- Replace backend/clinical jargon (`"RDKit ML major probability: 94%"`, `"Curated deterministic rule (deterministic_db)"`, raw `LOINC` / `SPL set ID` metadata above excerpts, and raw URLs) in [`ProgressiveAnalysisScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/analysis/ProgressiveAnalysisScreen.kt#L977-L1125) and [`CabinetNetworkGraph.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetNetworkGraph.kt#L489-L570) with plain-language descriptions and friendly links (`"Read on DailyMed"`).
- Visually dim paused cards in [`CabinetMedicationCard`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetScreen.kt#L373-L488) with a `"Paused"` badge so inactive drugs are unmistakable.
- Keep [`ActiveProfilePill`](android/app/src/main/java/com/pillscope/app/ui/PillScopeApp.kt#L174-L198) in a consistent corner across all 4 tabs (`Scan` currently places it top-left inside the preview while Tabs 2–4 put it top-right in the `TopAppBar`), and surface alert/recall badges on the bottom `NavigationBar`.

### 18. Sub-48dp Visual Affordances & Leading-Space Layout Hack
- Ensure minimum `48.dp` visual/touch affordances on `"Force scan"`, lifestyle trigger chips, clickable monograph/recall links, and `"Take"`/`"Taken"` chips.
- Replace `"  View details for ..."` string-space padding in [`CabinetNetworkGraph.kt`](android/app/src/main/java/com/pillscope/app/ui/cabinet/CabinetNetworkGraph.kt#L364) with `Spacer(Modifier.width(6.dp))`.

### 19. Compose Compiler Deprecation Warnings
- `./gradlew assembleDebug` flagged deprecated APIs to modernize:
  - `PasswordVisualTransformation` / `VisualTransformation` in [`AuthScreen.kt`](android/app/src/main/java/com/pillscope/app/ui/auth/AuthScreen.kt#L54-L55) (migrate to `OutlinedSecureTextField` / `BasicSecureTextField`).
  - `Icons.Filled.OpenInNew` & `Icons.Filled.Logout` (migrate to `Icons.AutoMirrored.Filled.*`).
  - `rememberModalBottomSheetState` in [`ProfileSwitcherSheet.kt`](android/app/src/main/java/com/pillscope/app/ui/profiles/ProfileSwitcherSheet.kt#L158).

---

## Prioritized Fix Checklist

- [x] Move `RiskGaugeHeroCard` headline text below the gauge needle/pivot (`ProgressiveAnalysisScreen.kt`).
- [x] Hide camera HUD status pill & shutter controls when `!hasCameraPermission`, and visually dim disabled shutter/dialog buttons (`CameraScanScreen.kt`, `CabinetScreen.kt`, `ProgressiveAnalysisScreen.kt`, `ProfileSwitcherSheet.kt`).
- [x] Reset/scope `AnalysisViewModel` state on profile switch, auto-sync `CabinetViewModel` on profile switch, and prevent `MainActivity` config changes from clearing Scan/Safety state (`PillScopeApp.kt`, `AnalysisViewModel.kt`, `CabinetViewModel.kt`).
- [x] Fix Network Graph viewport clipping (`FlowRow` legend off-screen), add `"Not analyzed yet"` edge semantics, and expose TalkBack accessibility (`CabinetScreen.kt`, `CabinetNetworkGraph.kt`, `CabinetGraphState.kt`).
- [x] Pin Onboarding consent checkboxes & CTA in `Scaffold(bottomBar = ...)`, make checkbox rows `.toggleable`, and add `imePadding()` across forms (`OnboardingConsentScreen.kt`, `AuthScreen.kt`, `ProfileSwitcherSheet.kt`).
- [x] Add `PaddingValues(bottom = 88.dp)` to Cabinet `LazyColumn`, make alert/sync banners dismissible and auto-clearing on delete, and add Delete/Active controls inside the detail sheet (`CabinetScreen.kt`, `CabinetViewModel.kt`).
- [x] Fix `FIT_CENTER` camera pillarboxing, add scrim behind `"Checking against ...'s cabinet"`, clarify `"Save to History"` vs `"Add to Cabinet"`, show strengths on candidate cards, and stabilize `"Force scan"` (`CameraScanScreen.kt`, `ScanViewModel.kt`, `MedicineLabelAnalyzer.kt`).
- [x] Fix Safety screen banner clipping, reorder `"Add to Cabinet anyway"` below clinical details, add `IDLE` `"Scan a medicine"` CTA, fix sandbox spinner visibility, and scroll to top when opening cached history (`ProgressiveAnalysisScreen.kt`).
- [x] Fix Dark Mode contrast on `"N allergies"` / `"FDA Recall"` / `"Major"` badges & dark-mode shutter icon, and update Schedule card spacing, recall warnings, empty-schedule guidance, and dosage presets (`Theme.kt`, `ScheduleScreen.kt`).
- [x] Clean up patient-facing copy (remove `RDKit ML` / `LOINC` / `SPL` jargon), add `verticalScroll` to `EdgeInteractionDetailSheet`, resolve Compose deprecation warnings, and add dark-mode CSS to `legal.py`.

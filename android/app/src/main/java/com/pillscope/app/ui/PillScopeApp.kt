package com.pillscope.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pillscope.app.data.remote.dto.UserResponseDto
import com.pillscope.app.data.repository.AuthRepository
import com.pillscope.app.data.repository.AuthSessionState
import com.pillscope.app.i18n.AppStringsCatalog
import com.pillscope.app.i18n.LanguageViewModel
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.analysis.AnalysisViewModel
import com.pillscope.app.ui.analysis.ProgressiveAnalysisScreen
import com.pillscope.app.ui.assistant.VoiceAssistantScreen
import com.pillscope.app.ui.assistant.VoiceAssistantViewModel
import com.pillscope.app.ui.auth.AuthScreen
import com.pillscope.app.ui.auth.AuthViewModel
import com.pillscope.app.ui.auth.OnboardingConsentScreen
import com.pillscope.app.ui.cabinet.CabinetScreen
import com.pillscope.app.ui.cabinet.CabinetViewModel
import com.pillscope.app.ui.common.LanguageAndVoiceSheet
import com.pillscope.app.ui.common.LanguagePillButton
import com.pillscope.app.ui.profiles.ActiveProfilePill
import com.pillscope.app.ui.profiles.ProfileSwitcherSheet
import com.pillscope.app.ui.profiles.ProfileViewModel
import com.pillscope.app.ui.scan.CameraScanScreen
import com.pillscope.app.ui.scan.ScanViewModel
import com.pillscope.app.ui.schedule.ScheduleScreen
import com.pillscope.app.ui.schedule.ScheduleViewModel
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeEmblem
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import com.pillscope.app.voice.SpeechTone
import com.pillscope.app.voice.VoiceNavTarget

private object Routes {
    const val SCAN = "scan"
    const val ANALYSIS = "analysis"
    const val ASSISTANT = "assistant"
    const val CABINET = "cabinet"
    const val SCHEDULE = "schedule"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PillScopeApp() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val languageViewModel: LanguageViewModel = hiltViewModel()
    val sessionState by authViewModel.sessionState.collectAsStateWithLifecycle()
    val selectedLanguage by languageViewModel.language.collectAsStateWithLifecycle()
    val autoSpeakAnswers by languageViewModel.autoSpeakAnswers.collectAsStateWithLifecycle()
    val speechRate by languageViewModel.speechRate.collectAsStateWithLifecycle()
    val preferCloudVoice by languageViewModel.preferCloudVoice.collectAsStateWithLifecycle()

    var showLanguageSheet by remember { mutableStateOf(false) }
    val appStrings = remember(selectedLanguage) { AppStringsCatalog.forLanguage(selectedLanguage) }
    val reducedMotion = rememberReducedMotionEnabled()

    CompositionLocalProvider(
        LocalAppLanguage provides selectedLanguage,
        LocalAppStrings provides appStrings,
    ) {
        AnimatedContent(
            targetState = sessionState,
            contentKey = { state ->
                when (state) {
                    AuthSessionState.Loading -> "loading"
                    AuthSessionState.NeedsConsent -> "consent"
                    is AuthSessionState.Unauthenticated -> "unauth"
                    is AuthSessionState.Authenticated -> "auth_${state.user.id}"
                }
            },
            transitionSpec = { PillScopeMotion.screenTransition(reducedMotion) },
            label = "sessionTransition",
        ) { session ->
            when (session) {
                AuthSessionState.Loading -> {
                    SplashLoadingSurface(
                        appName = appStrings.appName,
                        loadingText = appStrings.loadingApp,
                        reducedMotion = reducedMotion,
                    )
                }

                AuthSessionState.NeedsConsent -> {
                    OnboardingConsentScreen(
                        onAccept = authViewModel::acceptOnboardingConsent,
                        onOpenLanguagePicker = { showLanguageSheet = true },
                    )
                }

                is AuthSessionState.Unauthenticated -> {
                    AuthScreen(
                        viewModel = authViewModel,
                        sessionNotice = session.notice,
                        onOpenLanguagePicker = { showLanguageSheet = true },
                    )
                }

                is AuthSessionState.Authenticated -> {
                    AuthenticatedPillScopeShell(
                        user = session.user,
                        offlineResumed = session.offlineResumed,
                        authViewModel = authViewModel,
                        languageViewModel = languageViewModel,
                        onOpenLanguagePicker = { showLanguageSheet = true },
                    )
                }
            }
        }

        if (showLanguageSheet) {
            LanguageAndVoiceSheet(
                selectedLanguage = selectedLanguage,
                autoSpeakAnswers = autoSpeakAnswers,
                speechRate = speechRate,
                preferCloudVoice = preferCloudVoice,
                onSelectLanguage = languageViewModel::setLanguage,
                onToggleAutoSpeak = languageViewModel::setAutoSpeakAnswers,
                onChangeSpeechRate = languageViewModel::setSpeechRate,
                onToggleCloudVoice = languageViewModel::setPreferCloudVoice,
                onDismiss = { showLanguageSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SplashLoadingSurface(
    appName: String,
    loadingText: String,
    reducedMotion: Boolean,
) {
    val breatheScale = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "splashEmblemBreathe")
        transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "emblemScale",
        ).value
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = PillScopeColors.SlateSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PillScopeEmblem(
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            scaleX = breatheScale
                            scaleY = breatheScale
                        },
                )
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = PillScopeColors.ClinicalTealDeep,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PillScopeColors.ClinicalTeal,
                    )
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedPillScopeShell(
    user: UserResponseDto,
    offlineResumed: Boolean,
    authViewModel: AuthViewModel,
    languageViewModel: LanguageViewModel,
    onOpenLanguagePicker: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val reducedMotion = rememberReducedMotionEnabled()
    val navController = rememberNavController()
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val scanViewModel: ScanViewModel = hiltViewModel()
    val analysisViewModel: AnalysisViewModel = hiltViewModel()
    val assistantViewModel: VoiceAssistantViewModel = hiltViewModel()
    val cabinetViewModel: CabinetViewModel = hiltViewModel()
    val scheduleViewModel: ScheduleViewModel = hiltViewModel()

    val profiles by profileViewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by profileViewModel.activeProfile.collectAsStateWithLifecycle()
    val profileUiState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val analysisState by analysisViewModel.state.collectAsStateWithLifecycle()
    val cabinetRecalls by cabinetViewModel.activeRecalls.collectAsStateWithLifecycle()
    val scheduleState by scheduleViewModel.schedule.collectAsStateWithLifecycle()
    val speechState by languageViewModel.speechState.collectAsStateWithLifecycle()

    var showProfileSheet by remember { mutableStateOf(false) }
    val isOfflineGuest = user.id == AuthRepository.OFFLINE_GUEST_USER_ID

    LaunchedEffect(user.id) {
        if (!authViewModel.shouldInitializeSessionFor(user.id)) return@LaunchedEffect
        scanViewModel.clearScanState()
        analysisViewModel.resetForAccountChange()
        assistantViewModel.resetForAccountChange()
        cabinetViewModel.resetForAccountChange()
        scheduleViewModel.resetForAccountChange()
        profileViewModel.resetForAccountChange()
        if (!isOfflineGuest) {
            profileViewModel.refreshProfilesAndAwait()
            cabinetViewModel.syncCabinet()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.SCAN
    val openProfileSheet = {
        authViewModel.refreshPendingOfflineStatus()
        showProfileSheet = true
    }
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val readScheduleAloudAction = {
        val currentSchedule = scheduleState
        if (currentSchedule == null || currentSchedule.slots.all { it.medications.isEmpty() }) {
            languageViewModel.speak(strings.noDosesScheduledYet, language, "schedule_tts")
        } else {
            val spoken = buildString {
                append(strings.dailyScheduleTitle).append(". ")
                currentSchedule.slots.filter { it.medications.isNotEmpty() }.forEach { slot ->
                    append(strings.slotLabel(slot.slot)).append(", ")
                    append(slot.recommendedTime).append(": ")
                    append(slot.medications.joinToString(", ") { it.displayName })
                    append(". ")
                }
                if (currentSchedule.unresolvableClashes.isNotEmpty()) {
                    append(strings.conflictsCannotResolve).append(". ")
                }
            }
            val tone = if (currentSchedule.unresolvableClashes.isNotEmpty()) {
                SpeechTone.CAUTION
            } else {
                SpeechTone.REASSURING
            }
            languageViewModel.toggleSpeak(spoken, language, "schedule_tts", tone)
        }
    }

    val handleVoiceNavigate: (VoiceNavTarget) -> Unit = { target ->
        when (target) {
            VoiceNavTarget.SCAN -> navigateToTab(Routes.SCAN)
            VoiceNavTarget.SAFETY -> navigateToTab(Routes.ANALYSIS)
            VoiceNavTarget.ASK_AI -> navigateToTab(Routes.ASSISTANT)
            VoiceNavTarget.CABINET -> navigateToTab(Routes.CABINET)
            VoiceNavTarget.SCHEDULE -> navigateToTab(Routes.SCHEDULE)
        }
    }

    val hasSafetyAlertBadge = analysisState.safetyBannerLocked ||
        analysisState.allergyAlerts.isNotEmpty() ||
        analysisState.duplicateSalts.isNotEmpty() ||
        analysisState.activeRecalls.isNotEmpty()
    val recallBadgeCount = cabinetRecalls.size
    val hasScheduleAlertBadge = scheduleState?.unresolvableClashes?.isNotEmpty() == true ||
        (scheduleState?.slots?.any { slot ->
            slot.medications.any { dose ->
                cabinetRecalls.any { recall ->
                    recall.matchedMedicationId == dose.medicationId ||
                        recall.matchedTerms.any { term ->
                            dose.canonicalSaltKeys.any { it.equals(term, ignoreCase = true) }
                        }
                }
            }
        } == true)

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = PillScopeColors.ClinicalTealDeep,
        selectedTextColor = PillScopeColors.ClinicalTealDeep,
        indicatorColor = PillScopeColors.ClinicalTealContainer,
        unselectedIconColor = PillScopeColors.InkSecondary,
        unselectedTextColor = PillScopeColors.InkSecondary,
    )

    Scaffold(
        containerColor = PillScopeColors.SlateSurface,
        topBar = {
            if (currentRoute != Routes.SCAN) {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = PillScopeColors.SlateSurface,
                        ),
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PillScopeEmblem(modifier = Modifier.size(24.dp))
                                    Text(
                                        text = strings.appName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = PillScopeColors.ClinicalTealDeep,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    LanguagePillButton(onClick = onOpenLanguagePicker)
                                    ActiveProfilePill(
                                        profile = activeProfile,
                                        onClick = openProfileSheet,
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = PillScopeColors.SlateBorder.copy(alpha = 0.65f),
                    )
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = PillScopeColors.SlateBorder.copy(alpha = 0.75f),
                )
                NavigationBar(
                    containerColor = PillScopeColors.CardSurface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.SCAN,
                        onClick = { navigateToTab(Routes.SCAN) },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = strings.navScan) },
                        label = { Text(strings.navScan, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.ANALYSIS,
                        onClick = { navigateToTab(Routes.ANALYSIS) },
                        colors = navItemColors,
                        icon = {
                            BadgedBox(
                                badge = {
                                    NavBadgeReveal(
                                        visible = hasSafetyAlertBadge,
                                        reducedMotion = reducedMotion,
                                    ) {
                                        Badge(containerColor = PillScopeColors.CriticalBannerContainer)
                                    }
                                },
                            ) {
                                Icon(Icons.Default.HealthAndSafety, contentDescription = strings.navSafety)
                            }
                        },
                        label = { Text(strings.navSafety, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.ASSISTANT,
                        onClick = { navigateToTab(Routes.ASSISTANT) },
                        colors = navItemColors,
                        icon = { Icon(Icons.Default.Mic, contentDescription = strings.navAskAi) },
                        label = { Text(strings.navAskAi, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.CABINET,
                        onClick = { navigateToTab(Routes.CABINET) },
                        colors = navItemColors,
                        icon = {
                            BadgedBox(
                                badge = {
                                    NavBadgeReveal(
                                        visible = recallBadgeCount > 0,
                                        reducedMotion = reducedMotion,
                                    ) {
                                        Badge(
                                            containerColor = PillScopeColors.CriticalBannerContainer,
                                            contentColor = PillScopeColors.OnCriticalBanner,
                                        ) {
                                            Text(if (recallBadgeCount > 9) "9+" else recallBadgeCount.toString())
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Medication, contentDescription = strings.navCabinet)
                            }
                        },
                        label = { Text(strings.navCabinet, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SCHEDULE,
                        onClick = { navigateToTab(Routes.SCHEDULE) },
                        colors = navItemColors,
                        icon = {
                            BadgedBox(
                                badge = {
                                    NavBadgeReveal(
                                        visible = hasScheduleAlertBadge,
                                        reducedMotion = reducedMotion,
                                    ) {
                                        Badge(containerColor = PillScopeColors.CriticalBannerContainer)
                                    }
                                },
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = strings.navSchedule)
                            }
                        },
                        label = { Text(strings.navSchedule, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(
                visible = offlineResumed,
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                OfflineSessionStrip(
                    isOfflineGuest = isOfflineGuest,
                    onActionClick = {
                        if (isOfflineGuest) {
                            authViewModel.exitOfflineGuestToSignIn()
                        } else {
                            profileViewModel.refreshProfiles()
                            cabinetViewModel.syncCabinet()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            val enterDur = if (reducedMotion) 0 else 190
            val exitDur = if (reducedMotion) 0 else 130
            NavHost(
                navController = navController,
                startDestination = Routes.SCAN,
                modifier = Modifier.weight(1f),
                enterTransition = { fadeIn(tween(enterDur, easing = FastOutSlowInEasing)) },
                exitTransition = { fadeOut(tween(exitDur, easing = FastOutSlowInEasing)) },
                popEnterTransition = { fadeIn(tween(enterDur, easing = FastOutSlowInEasing)) },
                popExitTransition = { fadeOut(tween(exitDur, easing = FastOutSlowInEasing)) },
            ) {
                composable(Routes.SCAN) {
                    CameraScanScreen(
                        viewModel = scanViewModel,
                        activeProfile = activeProfile,
                        onOpenProfileSwitcher = openProfileSheet,
                        onOpenLanguagePicker = onOpenLanguagePicker,
                        onVoiceNavigate = handleVoiceNavigate,
                        onVoiceAskAi = { question ->
                            navigateToTab(Routes.ASSISTANT)
                            assistantViewModel.askQuestion(question, triggeredByVoice = true)
                        },
                        onAnalyzeRequest = { req ->
                            analysisViewModel.startAnalysis(req)
                            navigateToTab(Routes.ANALYSIS)
                        },
                    )
                }
                composable(Routes.ANALYSIS) {
                    ProgressiveAnalysisScreen(
                        viewModel = analysisViewModel,
                        onNavigateToScan = { navigateToTab(Routes.SCAN) },
                        onAskAiAboutScan = { scanId, medicineName, salts ->
                            assistantViewModel.setMedicineContext(scanId, medicineName, salts)
                            navigateToTab(Routes.ASSISTANT)
                        },
                        onAddToCabinet = { displayName, salts, dosageSchedule ->
                            cabinetViewModel.addMedicationFromScan(
                                displayName = displayName,
                                canonicalSalts = salts,
                                dosageSchedule = dosageSchedule,
                            )
                        },
                    )
                }
                composable(Routes.ASSISTANT) {
                    VoiceAssistantScreen(
                        viewModel = assistantViewModel,
                        onVoiceNavigate = handleVoiceNavigate,
                        onVoiceSwitchLanguage = languageViewModel::setLanguage,
                        onVoiceSearchMedicine = { query ->
                            scanViewModel.onManualQueryChange(query)
                            navigateToTab(Routes.SCAN)
                        },
                        onVoiceReadSafetyReport = {
                            navigateToTab(Routes.ANALYSIS)
                            analysisViewModel.speakCurrentReport()
                        },
                        onVoiceReadSchedule = {
                            navigateToTab(Routes.SCHEDULE)
                            readScheduleAloudAction()
                        },
                    )
                }
                composable(Routes.CABINET) {
                    CabinetScreen(
                        viewModel = cabinetViewModel,
                        onRunCabinetInteractionCheck = { req ->
                            analysisViewModel.startAnalysis(req)
                            navigateToTab(Routes.ANALYSIS)
                        },
                    )
                }
                composable(Routes.SCHEDULE) {
                    ScheduleScreen(
                        viewModel = scheduleViewModel,
                        onOpenCabinet = { navigateToTab(Routes.CABINET) },
                        isSpeakingSchedule = speechState.isSpeaking && speechState.activeUtteranceId == "schedule_tts",
                        onReadScheduleAloud = readScheduleAloudAction,
                    )
                }
            }
        }
    }

    if (showProfileSheet) {
        ProfileSwitcherSheet(
            profiles = profiles,
            activeProfile = activeProfile,
            userEmail = user.email.takeIf { it.isNotBlank() },
            offlineSession = offlineResumed,
            isOfflineGuest = isOfflineGuest,
            hasPendingOfflineChanges = authUiState.hasPendingOfflineChanges,
            statusMessage = profileUiState.statusMessage,
            errorMessage = profileUiState.errorMessage,
            accountActionError = authUiState.accountActionError,
            isDeletingAccount = authUiState.isDeletingAccount,
            onOpenLanguagePicker = {
                showProfileSheet = false
                onOpenLanguagePicker()
            },
            onSelectProfile = { id ->
                profileViewModel.selectProfile(id)
                showProfileSheet = false
            },
            onCreateProfile = { name, relationship, allergies ->
                profileViewModel.createProfile(name, relationship, allergies)
            },
            onEditProfile = { profileId, name, relationship, setAsDefault ->
                profileViewModel.editProfile(profileId, name, relationship, setAsDefault)
            },
            onSaveAllergies = { profileId, allergies ->
                profileViewModel.saveAllergies(profileId, allergies)
            },
            onDeleteProfile = { profileId ->
                profileViewModel.deleteProfile(profileId)
            },
            onSignInFromOfflineGuest = {
                showProfileSheet = false
                authViewModel.exitOfflineGuestToSignIn()
            },
            onSignOut = {
                showProfileSheet = false
                authViewModel.signOut()
            },
            onDeleteAccount = {
                authViewModel.deleteAccount {
                    showProfileSheet = false
                }
            },
            onDismiss = {
                profileViewModel.clearMessages()
                authViewModel.clearError()
                showProfileSheet = false
            },
        )
    }
}

@Composable
private fun NavBadgeReveal(
    visible: Boolean,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = PillScopeMotion.badgeEnter(reducedMotion),
        exit = PillScopeMotion.badgeExit(reducedMotion),
    ) {
        content()
    }
}

@Composable
private fun OfflineSessionStrip(
    isOfflineGuest: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = PillScopeColors.ModerateAmberSurface,
        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(PillScopeColors.ModerateAmber),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = PillScopeColors.ModerateAmberDark,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isOfflineGuest) {
                            strings.offlineGuestStrip
                        } else {
                            strings.offlineCachedStrip
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = PillScopeColors.ModerateAmberDark,
                    )
                }
                TextButton(onClick = onActionClick) {
                    Text(
                        text = if (isOfflineGuest) strings.signInAction else strings.retrySyncAction,
                        color = PillScopeColors.ModerateAmberDark,
                    )
                }
            }
        }
    }
}

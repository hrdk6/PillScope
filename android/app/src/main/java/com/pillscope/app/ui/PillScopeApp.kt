package com.pillscope.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.pillscope.app.ui.analysis.AnalysisViewModel
import com.pillscope.app.ui.analysis.ProgressiveAnalysisScreen
import com.pillscope.app.ui.auth.AuthScreen
import com.pillscope.app.ui.auth.AuthViewModel
import com.pillscope.app.ui.auth.OnboardingConsentScreen
import com.pillscope.app.ui.cabinet.CabinetScreen
import com.pillscope.app.ui.cabinet.CabinetViewModel
import com.pillscope.app.ui.profiles.ActiveProfilePill
import com.pillscope.app.ui.profiles.ProfileSwitcherSheet
import com.pillscope.app.ui.profiles.ProfileViewModel
import com.pillscope.app.ui.scan.CameraScanScreen
import com.pillscope.app.ui.scan.ScanViewModel
import com.pillscope.app.ui.schedule.ScheduleScreen
import com.pillscope.app.ui.schedule.ScheduleViewModel
import com.pillscope.app.ui.theme.PillScopeColors

private object Routes {
    const val SCAN = "scan"
    const val ANALYSIS = "analysis"
    const val CABINET = "cabinet"
    const val SCHEDULE = "schedule"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PillScopeApp() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val sessionState by authViewModel.sessionState.collectAsStateWithLifecycle()

    when (val session = sessionState) {
        AuthSessionState.Loading -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = PillScopeColors.SlateSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(44.dp),
                            color = PillScopeColors.ClinicalTeal,
                        )
                        Text(
                            text = "Loading PillScope…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PillScopeColors.InkSecondary,
                        )
                    }
                }
            }
        }

        AuthSessionState.NeedsConsent -> {
            OnboardingConsentScreen(
                onAccept = authViewModel::acceptOnboardingConsent,
            )
        }

        is AuthSessionState.Unauthenticated -> {
            AuthScreen(
                viewModel = authViewModel,
                sessionNotice = session.notice,
            )
        }

        is AuthSessionState.Authenticated -> {
            AuthenticatedPillScopeShell(
                user = session.user,
                offlineResumed = session.offlineResumed,
                authViewModel = authViewModel,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedPillScopeShell(
    user: UserResponseDto,
    offlineResumed: Boolean,
    authViewModel: AuthViewModel,
) {
    val navController = rememberNavController()
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val scanViewModel: ScanViewModel = hiltViewModel()
    val analysisViewModel: AnalysisViewModel = hiltViewModel()
    val cabinetViewModel: CabinetViewModel = hiltViewModel()
    val scheduleViewModel: ScheduleViewModel = hiltViewModel()

    val profiles by profileViewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by profileViewModel.activeProfile.collectAsStateWithLifecycle()
    val profileUiState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val analysisState by analysisViewModel.state.collectAsStateWithLifecycle()
    val cabinetRecalls by cabinetViewModel.activeRecalls.collectAsStateWithLifecycle()
    val scheduleState by scheduleViewModel.schedule.collectAsStateWithLifecycle()

    var showProfileSheet by remember { mutableStateOf(false) }
    val isOfflineGuest = user.id == AuthRepository.OFFLINE_GUEST_USER_ID

    // Guard session initialization in AuthViewModel so Activity recreations (rotation, Dark Mode)
    // preserve active scan & safety state while actual account changes still cleanly reset.
    LaunchedEffect(user.id) {
        if (!authViewModel.shouldInitializeSessionFor(user.id)) return@LaunchedEffect
        scanViewModel.clearScanState()
        analysisViewModel.resetForAccountChange()
        cabinetViewModel.resetForAccountChange()
        scheduleViewModel.resetForAccountChange()
        profileViewModel.resetForAccountChange()
        if (!isOfflineGuest) {
            // Sync profiles sequentially BEFORE cabinet sync so offline profiles migrate
            // to server UUIDs before pending medications are uploaded.
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

    Scaffold(
        containerColor = PillScopeColors.SlateSurface,
        topBar = {
            if (currentRoute != Routes.SCAN) {
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
                            Text(
                                text = "PillScope",
                                style = MaterialTheme.typography.titleLarge,
                                color = PillScopeColors.ClinicalTealDeep,
                            )
                            ActiveProfilePill(
                                profile = activeProfile,
                                onClick = openProfileSheet,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = PillScopeColors.CardSurface) {
                NavigationBarItem(
                    selected = currentRoute == Routes.SCAN,
                    onClick = { navigateToTab(Routes.SCAN) },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scan") },
                    label = { Text("Scan") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.ANALYSIS,
                    onClick = { navigateToTab(Routes.ANALYSIS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (hasSafetyAlertBadge) {
                                    Badge(containerColor = PillScopeColors.CriticalBannerContainer)
                                }
                            },
                        ) {
                            Icon(Icons.Default.HealthAndSafety, contentDescription = "Safety")
                        }
                    },
                    label = { Text("Safety") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.CABINET,
                    onClick = { navigateToTab(Routes.CABINET) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (recallBadgeCount > 0) {
                                    Badge(
                                        containerColor = PillScopeColors.CriticalBannerContainer,
                                        contentColor = PillScopeColors.OnCriticalBanner,
                                    ) {
                                        Text(if (recallBadgeCount > 9) "9+" else recallBadgeCount.toString())
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Default.Medication, contentDescription = "Cabinet")
                        }
                    },
                    label = { Text("Cabinet") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.SCHEDULE,
                    onClick = { navigateToTab(Routes.SCHEDULE) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (hasScheduleAlertBadge) {
                                    Badge(containerColor = PillScopeColors.CriticalBannerContainer)
                                }
                            },
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Schedule")
                        }
                    },
                    label = { Text("Schedule") },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (offlineResumed) {
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

            NavHost(
                navController = navController,
                startDestination = Routes.SCAN,
                modifier = Modifier.weight(1f),
            ) {
                composable(Routes.SCAN) {
                    CameraScanScreen(
                        viewModel = scanViewModel,
                        activeProfile = activeProfile,
                        onOpenProfileSwitcher = openProfileSheet,
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
                        onAddToCabinet = { displayName, salts, dosageSchedule ->
                            cabinetViewModel.addMedicationFromScan(
                                displayName = displayName,
                                canonicalSalts = salts,
                                dosageSchedule = dosageSchedule,
                            )
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
private fun OfflineSessionStrip(
    isOfflineGuest: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = PillScopeColors.ModerateAmberSurface,
        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
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
                        "Offline mode — on-device scanner & allergy check active"
                    } else {
                        "Offline mode — showing cached cabinet & reports"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = PillScopeColors.ModerateAmberDark,
                )
            }
            TextButton(onClick = onActionClick) {
                Text(
                    text = if (isOfflineGuest) "Sign in" else "Retry sync",
                    color = PillScopeColors.ModerateAmberDark,
                )
            }
        }
    }
}

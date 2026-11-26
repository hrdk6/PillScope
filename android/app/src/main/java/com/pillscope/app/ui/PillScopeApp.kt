package com.pillscope.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Medication
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pillscope.app.data.remote.dto.UserResponseDto
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
    var showProfileSheet by remember { mutableStateOf(false) }

    LaunchedEffect(user.id) {
        profileViewModel.refreshProfiles()
        cabinetViewModel.syncCabinet()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.SCAN

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
                                onClick = { showProfileSheet = true },
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
                    onClick = {
                        navController.navigate(Routes.SCAN) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scan") },
                    label = { Text("Scan") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.ANALYSIS,
                    onClick = {
                        navController.navigate(Routes.ANALYSIS) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = "Safety") },
                    label = { Text("Safety") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.CABINET,
                    onClick = {
                        navController.navigate(Routes.CABINET) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.Medication, contentDescription = "Cabinet") },
                    label = { Text("Cabinet") },
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.SCHEDULE,
                    onClick = {
                        navController.navigate(Routes.SCHEDULE) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Schedule") },
                    label = { Text("Schedule") },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCAN,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SCAN) {
                CameraScanScreen(
                    viewModel = scanViewModel,
                    activeProfile = activeProfile,
                    onOpenProfileSwitcher = { showProfileSheet = true },
                    onAnalyzeRequest = { req ->
                        analysisViewModel.startAnalysis(req)
                        navController.navigate(Routes.ANALYSIS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.ANALYSIS) {
                ProgressiveAnalysisScreen(
                    viewModel = analysisViewModel,
                    onAddToCabinet = { displayName, salts ->
                        cabinetViewModel.addMedicationFromScan(
                            displayName = displayName,
                            canonicalSalts = salts,
                        )
                    },
                )
            }
            composable(Routes.CABINET) {
                CabinetScreen(viewModel = cabinetViewModel)
            }
            composable(Routes.SCHEDULE) {
                ScheduleScreen(viewModel = scheduleViewModel)
            }
        }
    }

    if (showProfileSheet) {
        ProfileSwitcherSheet(
            profiles = profiles,
            activeProfile = activeProfile,
            userEmail = user.email,
            offlineSession = offlineResumed,
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
                showProfileSheet = false
            },
            onDeleteProfile = { profileId ->
                profileViewModel.deleteProfile(profileId)
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

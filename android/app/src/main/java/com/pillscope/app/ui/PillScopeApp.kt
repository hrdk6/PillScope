package com.pillscope.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pillscope.app.ui.analysis.AnalysisViewModel
import com.pillscope.app.ui.analysis.ProgressiveAnalysisScreen
import com.pillscope.app.ui.cabinet.CabinetScreen
import com.pillscope.app.ui.cabinet.CabinetViewModel
import com.pillscope.app.ui.scan.CameraScanScreen
import com.pillscope.app.ui.scan.ScanViewModel

private object Routes {
    const val SCAN = "scan"
    const val ANALYSIS = "analysis"
    const val CABINET = "cabinet"
}

@Composable
fun PillScopeApp() {
    val navController = rememberNavController()
    val scanViewModel: ScanViewModel = hiltViewModel()
    val analysisViewModel: AnalysisViewModel = hiltViewModel()
    val cabinetViewModel: CabinetViewModel = hiltViewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.SCAN

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                    onAnalyzeRequest = { req ->
                        analysisViewModel.startAnalysis(req)
                        navController.navigate(Routes.ANALYSIS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.ANALYSIS) {
                ProgressiveAnalysisScreen(viewModel = analysisViewModel)
            }
            composable(Routes.CABINET) {
                CabinetScreen(viewModel = cabinetViewModel)
            }
        }
    }
}

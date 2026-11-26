package com.pillscope.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pillscope.app.BuildConfig
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.common.LanguagePillButton
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.PillScopeColors

private enum class AuthTab { SIGN_IN, CREATE_ACCOUNT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    sessionNotice: String?,
    onOpenLanguagePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val openUri = rememberSafeUriOpener()

    var selectedTab by rememberSaveable { mutableStateOf(AuthTab.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    val passwordState = rememberTextFieldState()
    var displayName by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val passwordText = passwordState.text.toString()

    LaunchedEffect(passwordText) {
        if (uiState.passwordError != null || uiState.errorMessage != null) {
            viewModel.clearPasswordError()
        }
    }

    val submitAuth = {
        if (!uiState.isSubmitting && email.isNotBlank() && passwordText.isNotBlank()) {
            if (selectedTab == AuthTab.SIGN_IN) {
                viewModel.signIn(email, passwordText)
            } else {
                viewModel.register(email, passwordText, displayName)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = PillScopeColors.SlateSurface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                LanguagePillButton(onClick = onOpenLanguagePicker)
            }

            Surface(
                shape = CircleShape,
                color = PillScopeColors.ClinicalTealContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = PillScopeColors.ClinicalTealDeep,
                    modifier = Modifier.padding(14.dp),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = strings.appName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = PillScopeColors.ClinicalTealDeep,
                )
                Text(
                    text = strings.appTagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkSecondary,
                )
            }

            sessionNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = PillScopeColors.ModerateAmberSurface,
                    border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.ModerateAmberDark,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = selectedTab == AuthTab.SIGN_IN,
                            onClick = {
                                selectedTab = AuthTab.SIGN_IN
                                viewModel.clearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label = { Text(strings.signIn) },
                        )
                        SegmentedButton(
                            selected = selectedTab == AuthTab.CREATE_ACCOUNT,
                            onClick = {
                                selectedTab = AuthTab.CREATE_ACCOUNT
                                viewModel.clearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = { Text(strings.createAccount) },
                        )
                    }

                    if (selectedTab == AuthTab.CREATE_ACCOUNT) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(strings.yourNameOptional) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            viewModel.clearEmailError()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.emailError != null,
                        supportingText = uiState.emailError?.let { err ->
                            { Text(text = err) }
                        },
                        label = { Text(strings.emailAddress) },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    )

                    OutlinedSecureTextField(
                        state = passwordState,
                        modifier = Modifier.fillMaxWidth(),
                        isError = uiState.passwordError != null,
                        supportingText = uiState.passwordError?.let { err ->
                            { Text(text = err) }
                        },
                        label = {
                            Text(
                                if (selectedTab == AuthTab.CREATE_ACCOUNT) {
                                    strings.passwordMinChars
                                } else {
                                    strings.password
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        textObfuscationMode = if (passwordVisible) {
                            TextObfuscationMode.Visible
                        } else {
                            TextObfuscationMode.RevealLastTyped
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        onKeyboardAction = { submitAuth() },
                        shape = RoundedCornerShape(14.dp),
                    )

                    uiState.errorMessage?.let { err ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = PillScopeColors.CriticalCrimsonSurface,
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.CriticalCrimsonDark,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    Button(
                        onClick = submitAuth,
                        enabled = !uiState.isSubmitting && email.isNotBlank() && passwordText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (uiState.isSubmitting) {
                            LoadingIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                if (selectedTab == AuthTab.SIGN_IN) strings.signIn else strings.createAccount,
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = viewModel::continueOfflineGuest,
                enabled = !uiState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(strings.useOfflineScanner)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    onClick = { openUri(BuildConfig.PRIVACY_POLICY_URL) },
                ) {
                    Text(strings.privacyPolicy)
                }
                TextButton(
                    onClick = { openUri(BuildConfig.ACCOUNT_DELETION_URL) },
                ) {
                    Text(strings.accountDeletion)
                }
            }
        }
    }
}

package com.pillscope.app.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
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
import com.pillscope.app.ui.theme.OrchestratedReveal
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeEmblem
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import com.pillscope.app.ui.theme.tactilePressScale

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
    val reducedMotion = rememberReducedMotionEnabled()

    var selectedTab by rememberSaveable { mutableStateOf(AuthTab.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    val passwordState = rememberTextFieldState()
    var displayName by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val passwordText = passwordState.text.toString()
    val submitEnabled = !uiState.isSubmitting && email.isNotBlank() && passwordText.isNotBlank()
    val submitInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(passwordText) {
        if (uiState.passwordError != null || uiState.errorMessage != null) {
            viewModel.clearPasswordError()
        }
    }

    val submitAuth = {
        if (submitEnabled) {
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
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillScopeEmblem(modifier = Modifier.size(48.dp))
                LanguagePillButton(onClick = onOpenLanguagePicker)
            }

            OrchestratedReveal {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.displaySmall,
                        color = PillScopeColors.ClinicalTealDeep,
                    )
                    Text(
                        text = strings.appTagline,
                        style = MaterialTheme.typography.bodyLarge,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            AnimatedVisibility(
                visible = !sessionNotice.isNullOrBlank(),
                enter = PillScopeMotion.panelEnter(reducedMotion),
                exit = PillScopeMotion.panelExit(reducedMotion),
            ) {
                sessionNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.ModerateAmberSurface,
                        border = BorderStroke(1.dp, PillScopeColors.ModerateAmber.copy(alpha = 0.35f)),
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(PillScopeColors.ModerateAmber),
                            )
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PillScopeColors.ModerateAmberDark,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = PillScopeMotion.settleSpring()),
                shape = RoundedCornerShape(18.dp),
                color = PillScopeColors.CardSurface,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val segColors = SegmentedButtonDefaults.colors(
                        activeContainerColor = PillScopeColors.ClinicalTealContainer,
                        activeContentColor = PillScopeColors.ClinicalTealDeep,
                        activeBorderColor = PillScopeColors.ClinicalTeal.copy(alpha = 0.5f),
                        inactiveContainerColor = PillScopeColors.SlateSurface,
                        inactiveContentColor = PillScopeColors.InkSecondary,
                        inactiveBorderColor = PillScopeColors.SlateBorder,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = selectedTab == AuthTab.SIGN_IN,
                            onClick = {
                                selectedTab = AuthTab.SIGN_IN
                                viewModel.clearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            colors = segColors,
                            label = { Text(strings.signIn) },
                        )
                        SegmentedButton(
                            selected = selectedTab == AuthTab.CREATE_ACCOUNT,
                            onClick = {
                                selectedTab = AuthTab.CREATE_ACCOUNT
                                viewModel.clearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            colors = segColors,
                            label = { Text(strings.createAccount) },
                        )
                    }

                    AnimatedVisibility(
                        visible = selectedTab == AuthTab.CREATE_ACCOUNT,
                        enter = PillScopeMotion.panelEnter(reducedMotion),
                        exit = PillScopeMotion.panelExit(reducedMotion),
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(strings.yourNameOptional) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
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
                        shape = RoundedCornerShape(12.dp),
                    )

                    AnimatedVisibility(
                        visible = !uiState.errorMessage.isNullOrBlank(),
                        enter = PillScopeMotion.panelEnter(reducedMotion),
                        exit = PillScopeMotion.panelExit(reducedMotion),
                    ) {
                        uiState.errorMessage?.let { err ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = PillScopeColors.CriticalCrimsonSurface,
                                border = BorderStroke(1.dp, PillScopeColors.CriticalCrimson.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PillScopeColors.CriticalCrimsonDark,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }

                    Button(
                        onClick = submitAuth,
                        enabled = submitEnabled,
                        interactionSource = submitInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .tactilePressScale(submitInteraction, enabled = submitEnabled),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PillScopeColors.ClinicalTeal,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = PillScopeColors.SurfaceTint,
                            disabledContentColor = PillScopeColors.InkSecondary.copy(alpha = 0.55f),
                        ),
                    ) {
                        if (uiState.isSubmitting) {
                            LoadingIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (selectedTab == AuthTab.SIGN_IN) strings.signIn else strings.createAccount,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder.copy(alpha = 0.7f))

            OutlinedButton(
                onClick = viewModel::continueOfflineGuest,
                enabled = !uiState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = PillScopeColors.InkPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = PillScopeColors.InkSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(strings.useOfflineScanner)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { openUri(BuildConfig.PRIVACY_POLICY_URL) }) {
                    Text(strings.privacyPolicy, color = PillScopeColors.ClinicalTeal)
                }
                TextButton(onClick = { openUri(BuildConfig.ACCOUNT_DELETION_URL) }) {
                    Text(strings.accountDeletion, color = PillScopeColors.ClinicalTeal)
                }
            }
        }
    }
}

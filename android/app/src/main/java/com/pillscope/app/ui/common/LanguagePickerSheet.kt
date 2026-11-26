package com.pillscope.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.LocalAppLanguage
import com.pillscope.app.i18n.LocalAppStrings
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled

@Composable
fun LanguagePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false,
) {
    val language = LocalAppLanguage.current
    val strings = LocalAppStrings.current
    val containerColor = if (darkSurface) {
        Color(0xAA101B26)
    } else {
        PillScopeColors.CardSurface
    }
    val contentColor = if (darkSurface) Color(0xFFF0F5FA) else PillScopeColors.InkPrimary
    val borderColor = if (darkSurface) {
        Color.White.copy(alpha = 0.18f)
    } else {
        PillScopeColors.SlateBorder
    }

    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = strings.languageLabel,
                tint = if (darkSurface) PillScopeColors.HudEmeraldLock else PillScopeColors.ClinicalTeal,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LanguageAndVoiceSheet(
    selectedLanguage: AppLanguage,
    autoSpeakAnswers: Boolean,
    speechRate: Float,
    preferCloudVoice: Boolean,
    onSelectLanguage: (AppLanguage) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onChangeSpeechRate: (Float) -> Unit,
    onToggleCloudVoice: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val reducedMotion = rememberReducedMotionEnabled()
    val speedOptions = listOf(
        0.85f to strings.speedSlow,
        0.94f to strings.speedNormal,
        1.12f to strings.speedFast,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PillScopeColors.CardSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = PillScopeColors.ClinicalTealContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = PillScopeColors.ClinicalTealDeep,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.chooseLanguageTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = PillScopeColors.InkPrimary,
                    )
                    Text(
                        text = strings.chooseLanguageSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                AppLanguage.entries.forEach { lang ->
                    val isSelected = lang == selectedLanguage
                    val tileBg by animateColorAsState(
                        targetValue = if (isSelected) {
                            PillScopeColors.ClinicalTealContainer.copy(alpha = 0.72f)
                        } else {
                            PillScopeColors.SlateSurface
                        },
                        animationSpec = tween(PillScopeMotion.FAST_MS),
                        label = "langTileBg",
                    )
                    val tileBorder by animateColorAsState(
                        targetValue = if (isSelected) {
                            PillScopeColors.ClinicalTeal
                        } else {
                            PillScopeColors.SlateBorder
                        },
                        animationSpec = tween(PillScopeMotion.FAST_MS),
                        label = "langTileBorder",
                    )

                    Surface(
                        onClick = { onSelectLanguage(lang) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tileBg,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = tileBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) PillScopeColors.ClinicalTealDeep else PillScopeColors.InkPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = lang.englishName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PillScopeColors.InkSecondary,
                                )
                            }
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = PillScopeMotion.badgeEnter(reducedMotion),
                                exit = PillScopeMotion.badgeExit(reducedMotion),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PillScopeColors.ClinicalTeal,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PillScopeColors.SlateBorder)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = PillScopeColors.ClinicalTeal,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = strings.voiceSettingsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = PillScopeColors.InkPrimary,
                )
            }

            // Unified hairline-divided voice preferences panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = PillScopeColors.SlateSurface,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = strings.autoSpeakLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = PillScopeColors.InkPrimary,
                            )
                            Text(
                                text = strings.autoSpeakSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = PillScopeColors.InkSecondary,
                            )
                        }
                        Switch(
                            checked = autoSpeakAnswers,
                            onCheckedChange = onToggleAutoSpeak,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = PillScopeColors.SlateBorder.copy(alpha = 0.7f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = strings.cloudVoiceLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = PillScopeColors.InkPrimary,
                            )
                            Text(
                                text = strings.cloudVoiceSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = PillScopeColors.InkSecondary,
                            )
                        }
                        Switch(
                            checked = preferCloudVoice,
                            onCheckedChange = onToggleCloudVoice,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.speechSpeedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = PillScopeColors.InkPrimary,
                )
                val segColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PillScopeColors.ClinicalTealContainer,
                    activeContentColor = PillScopeColors.ClinicalTealDeep,
                    activeBorderColor = PillScopeColors.ClinicalTeal.copy(alpha = 0.45f),
                    inactiveContainerColor = PillScopeColors.SlateSurface,
                    inactiveContentColor = PillScopeColors.InkSecondary,
                    inactiveBorderColor = PillScopeColors.SlateBorder,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    speedOptions.forEachIndexed { index, (rate, label) ->
                        val selected = kotlin.math.abs(speechRate - rate) < 0.08f
                        SegmentedButton(
                            selected = selected,
                            onClick = { onChangeSpeechRate(rate) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = speedOptions.size),
                            colors = segColors,
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

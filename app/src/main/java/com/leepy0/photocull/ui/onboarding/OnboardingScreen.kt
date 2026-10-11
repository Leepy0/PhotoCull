package com.leepy0.photocull.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.StatusLine
import com.leepy0.photocull.ui.importer.BadgeTone
import com.leepy0.photocull.ui.importer.ContentMaxWidth
import com.leepy0.photocull.ui.importer.KoreanLineBreaks
import com.leepy0.photocull.ui.importer.IconBadge
import com.leepy0.photocull.ui.importer.notificationsAllowed
import com.leepy0.photocull.ui.importer.openAllFilesAccessSettings
import com.leepy0.photocull.ui.importer.openNotificationSettings
import com.leepy0.photocull.ui.importer.rememberNotificationPermissionRequester
import com.leepy0.photocull.ui.review.AdaptiveActionRow
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.launch

/** 시작 화면 상태(시스템 권한) */
@Immutable
data class OnboardingUiState(
    /** 모든 파일 접근(필수) */
    val filesGranted: Boolean = false,
    /** 알림(권장) */
    val notificationsGranted: Boolean = false,
    /** 알림을 다시 물을 수 없음 → 설정에서 켜야 함 */
    val notificationsBlocked: Boolean = false,
) {
    val canContinue: Boolean get() = filesGranted
}

@Immutable
data class OnboardingActions(
    val onRequestFilesAccess: () -> Unit = {},
    val onRequestNotifications: () -> Unit = {},
    val onContinue: () -> Unit = {},
)

/**
 * 시작하기 전에(흐름 화면). 주요 행동: [계속] — 필수 권한(모든 파일 접근)이 있어야 켜진다.
 * 설정에서 돌아올 때마다 권한을 다시 확인한다. 이미 모두 허용돼 있어도 화면은 그대로 보여준다(설정에서 들어온 경우).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: OnboardingViewModel = viewModel { OnboardingViewModel(container) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var filesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    var notificationsGranted by remember { mutableStateOf(notificationsAllowed(context)) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        filesGranted = hasAllFilesAccess()
        notificationsGranted = notificationsAllowed(context)
    }

    val settingsUnavailable = stringResource(R.string.onboarding_settings_unavailable)
    val openNotificationSettings: () -> Unit = {
        if (!context.openNotificationSettings()) scope.launch { snackbar.showSnackbar(settingsUnavailable) }
    }
    val requester = rememberNotificationPermissionRequester(
        onResult = { granted, blocked ->
            notificationsGranted = granted
            notificationsBlocked = blocked
        },
        // 이미 막혀 있어 창이 뜨지 않음: 누른 것에 반응하도록 바로 알림 설정을 연다
        onBlocked = {
            notificationsBlocked = true
            openNotificationSettings()
        },
    )

    OnboardingContent(
        state = OnboardingUiState(filesGranted, notificationsGranted, notificationsBlocked && !notificationsGranted),
        snackbarHostState = snackbar,
        actions = OnboardingActions(
            onRequestFilesAccess = {
                if (!context.openAllFilesAccessSettings()) scope.launch { snackbar.showSnackbar(settingsUnavailable) }
            },
            onRequestNotifications = {
                if (notificationsBlocked) openNotificationSettings() else requester.request()
            },
            onContinue = { vm.complete(onDone) },
        ),
    )
}

private fun hasAllFilesAccess(): Boolean = runCatching { Permissions.hasAllFilesAccess() }.getOrDefault(false)

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    KoreanLineBreaks {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                BottomActionBar {
                    AdaptiveActionRow(
                        hint = if (state.canContinue) null else stringResource(R.string.onboarding_continue_hint),
                        showHintWhenNarrow = true,
                    ) {
                        PrimaryActionButton(
                            stringResource(R.string.onboarding_continue),
                            onClick = actions.onContinue,
                            enabled = state.canContinue,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.m, vertical = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Column(
                        Modifier.padding(top = Spacing.l, bottom = Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.onboarding_value),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PermissionStep(
                        number = 1,
                        title = stringResource(R.string.onboarding_files_title),
                        required = true,
                        why = stringResource(R.string.onboarding_files_why),
                        how = stringResource(R.string.onboarding_files_how),
                        granted = state.filesGranted,
                    ) {
                        FilledTonalButton(
                            onClick = actions.onRequestFilesAccess,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                        ) { Text(stringResource(R.string.onboarding_files_action)) }
                    }
                    PermissionStep(
                        number = 2,
                        title = stringResource(R.string.onboarding_notif_title),
                        required = false,
                        why = stringResource(R.string.onboarding_notif_why),
                        how = if (state.notificationsBlocked) stringResource(R.string.onboarding_notif_blocked) else null,
                        granted = state.notificationsGranted,
                    ) {
                        OutlinedButton(
                            onClick = actions.onRequestNotifications,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                        ) {
                            Text(
                                stringResource(
                                    if (state.notificationsBlocked) R.string.onboarding_notif_settings else R.string.onboarding_notif_action,
                                ),
                            )
                        }
                    }
                    Row(
                        Modifier.padding(horizontal = Spacing.xxs, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs),
                        )
                        Text(
                            stringResource(R.string.onboarding_privacy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 권한 단계 하나: 번호(허용되면 체크) · 이름 · 필수/권장 · 이유 · [허용] 또는 "허용됨" */
@Composable
private fun PermissionStep(
    number: Int,
    title: String,
    required: Boolean,
    why: String,
    how: String?,
    granted: Boolean,
    action: @Composable () -> Unit,
) {
    val stepDescription = stringResource(R.string.onboarding_step_number, number)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                if (granted) {
                    IconBadge(Icons.Filled.Check, BadgeTone.SUCCESS, Modifier.semantics { contentDescription = stepDescription })
                } else {
                    StepNumber(number, stepDescription)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                RequirementTag(required)
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) {
                StatusLine(StatusKind.SUCCESS, stringResource(R.string.onboarding_granted))
            } else {
                if (how != null) {
                    Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action()
            }
        }
    }
}

@Composable
private fun StepNumber(number: Int, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
        modifier = Modifier.size(Spacing.touchTarget).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RequirementTag(required: Boolean) {
    Surface(
        color = if (required) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (required) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            stringResource(if (required) R.string.onboarding_required else R.string.onboarding_recommended),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
    }
}

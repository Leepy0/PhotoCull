package com.leepy0.photocull.ui.update

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Upgrade
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.statusColor
import com.leepy0.photocull.ui.theme.LocalStatusColors
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.update.UpdateInfo
import com.leepy0.photocull.update.UpdateState
import com.leepy0.photocull.util.Format
import java.time.Instant
import kotlin.math.roundToInt

/** 실패 화면의 주요 행동 */
enum class FailureAction {
    /** 실패한 단계(확인/받기/설치)를 다시 */
    RETRY,
    /** 설치 권한 없음 → '출처를 알 수 없는 앱 설치' 설정 */
    OPEN_INSTALL_SETTINGS,
    /** 서명 불일치 등 앱 안에서 해결할 수 없음 → 릴리스 페이지에서 직접 받기 */
    OPEN_RELEASE_PAGE,
}

/** 업데이트 시트가 보여줄 내용 */
sealed interface UpdateSheetMode {
    /** 사용자가 연 업데이트 화면(확인/받기/설치) */
    data class Update(
        val state: UpdateState,
        val currentVersionName: String,
        /** '출처를 알 수 없는 앱 설치' 허용 여부(화면 복귀 시 다시 확인) */
        val canInstall: Boolean,
        /** BUILD_CHANNEL == "local": 정식 키 서명이 아니라 설치가 실패할 수 있음 */
        val localBuild: Boolean,
        /** 받는 중 취소 지원 여부 */
        val canCancelDownload: Boolean = true,
        /** [UpdateState.Failed]일 때 주요 행동 */
        val failureAction: FailureAction = FailureAction.RETRY,
        /** 사진을 가져오는 중(설치하면 앱이 다시 시작돼 끊긴다): [설치]를 끄고 안내, 끝나면 저절로 켜진다 */
        val importRunning: Boolean = false,
    ) : UpdateSheetMode

    /** 업데이트 직후 첫 실행 */
    data class JustUpdated(val versionName: String, val change: String, val notes: String?) : UpdateSheetMode

    /** 지난 실행이 비정상 종료됨 */
    data class Crash(val crashedAt: Long?, val copied: Boolean) : UpdateSheetMode
}

class UpdateSheetActions(
    val onDownload: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onOpenInstallSettings: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenReleasePage: () -> Unit = {},
    /** 시트를 닫는다(확인/닫기) */
    val onClose: () -> Unit = {},
    val onCopyCrash: () -> Unit = {},
)

/** 변경 내용 상자 최대 높이(넘치면 상자 안에서 스크롤) */
private val NotesMaxHeight = Spacing.xxl * 5

/**
 * 업데이트 시트 본문(stateless). 위: 상태 설명(길면 스크롤), 아래: 주요 행동 1개 + 보조 텍스트 버튼.
 */
@Composable
fun UpdateSheetContent(mode: UpdateSheetMode, actions: UpdateSheetActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            when (mode) {
                is UpdateSheetMode.Update -> UpdateBody(mode)
                is UpdateSheetMode.JustUpdated -> JustUpdatedBody(mode)
                is UpdateSheetMode.Crash -> CrashBody(mode)
            }
        }
        SheetActions(mode, actions)
    }
}

// ---------------- 본문 ----------------

@Composable
private fun UpdateBody(mode: UpdateSheetMode.Update) {
    @Composable
    fun change(info: UpdateInfo) = stringResource(R.string.update_version_change, mode.currentVersionName, info.versionName)
    when (val s = mode.state) {
        UpdateState.Idle, UpdateState.Checking -> SheetHeader(
            leading = { HeaderSpinner() },
            title = stringResource(R.string.update_checking_title),
            subtitle = stringResource(R.string.update_current_version, mode.currentVersionName),
        )
        is UpdateState.UpToDate -> SheetHeader(
            icon = Icons.Outlined.CheckCircle,
            tone = Tone.Success,
            title = stringResource(R.string.update_uptodate_title),
            subtitle = stringResource(R.string.update_uptodate_body, mode.currentVersionName, Format.dateTime(s.checkedAt)),
        )
        is UpdateState.Available -> {
            SheetHeader(
                icon = Icons.Outlined.Upgrade,
                tone = Tone.Accent,
                title = stringResource(R.string.update_available_title),
                subtitle = change(s.info),
            )
            MetaLine(s.info)
            NotesSection(s.info.notes)
            if (mode.localBuild) LocalBuildNote()
        }
        is UpdateState.Downloading -> {
            SheetHeader(
                icon = Icons.Outlined.Download,
                tone = Tone.Accent,
                title = stringResource(R.string.update_downloading_title),
                subtitle = change(s.info),
            )
            DownloadProgress(s.info, s.progress)
        }
        is UpdateState.ReadyToInstall -> {
            SheetHeader(
                icon = Icons.Outlined.InstallMobile,
                tone = Tone.Accent,
                title = stringResource(R.string.update_ready_title),
                subtitle = change(s.info),
            )
            IconLine(Icons.Outlined.CheckCircle, StatusKind.SUCCESS, stringResource(R.string.update_ready_verified))
            when {
                !mode.canInstall -> PermissionCallout()
                mode.importRunning -> IconLine(Icons.Outlined.Info, StatusKind.INFO, stringResource(R.string.update_ready_wait_import))
                else -> BodyText(stringResource(R.string.update_ready_restart_note), muted = true)
            }
            if (mode.localBuild) LocalBuildNote()
        }
        is UpdateState.Installing -> SheetHeader(
            leading = { HeaderSpinner() },
            title = stringResource(R.string.update_installing_title),
            subtitle = stringResource(R.string.update_installing_body),
        )
        is UpdateState.Failed -> {
            val lines = s.message.lines().filter { it.isNotBlank() }
            SheetHeader(
                icon = Icons.Outlined.ErrorOutline,
                tone = Tone.Error,
                title = lines.firstOrNull().orEmpty(),
                subtitle = lines.drop(1).joinToString("\n").ifEmpty { null },
            )
            if (s.fix != null) BodyText(s.fix, large = true)
            if (mode.failureAction == FailureAction.OPEN_INSTALL_SETTINGS && !mode.canInstall) PermissionCallout()
            if (mode.localBuild && s.info != null) LocalBuildNote()
        }
    }
}

@Composable
private fun JustUpdatedBody(mode: UpdateSheetMode.JustUpdated) {
    SheetHeader(
        icon = Icons.Outlined.CheckCircle,
        tone = Tone.Success,
        title = stringResource(justUpdatedTitleRes(mode.versionName), mode.versionName),
        subtitle = mode.change,
    )
    NotesSection(mode.notes)
}

@Composable
private fun CrashBody(mode: UpdateSheetMode.Crash) {
    SheetHeader(
        icon = Icons.Outlined.BugReport,
        tone = Tone.Error,
        title = stringResource(R.string.update_crash_title),
        subtitle = mode.crashedAt?.let { stringResource(R.string.update_crash_time, Format.dateTime(it)) },
    )
    BodyText(stringResource(R.string.update_crash_body), large = true)
    if (mode.copied) IconLine(Icons.Outlined.CheckCircle, StatusKind.SUCCESS, stringResource(R.string.update_crash_copied))
}

// ---------------- 하단 행동 ----------------

@Composable
private fun SheetActions(mode: UpdateSheetMode, actions: UpdateSheetActions) {
    // 설치 중에는 누를 것이 없다.
    if (mode is UpdateSheetMode.Update) {
        val s = mode.state
        if (s is UpdateState.Installing || (s is UpdateState.Downloading && !mode.canCancelDownload)) return
    }
    BottomActionBar(applyNavigationBarInset = false) {
        when (mode) {
            is UpdateSheetMode.Update -> when (mode.state) {
                UpdateState.Idle, UpdateState.Checking -> ReleasePageButton(actions)
                is UpdateState.UpToDate -> {
                    PrimaryActionButton(stringResource(R.string.update_action_ok), actions.onClose)
                    ReleasePageButton(actions)
                }
                is UpdateState.Available -> {
                    PrimaryActionButton(stringResource(R.string.update_action_download), actions.onDownload)
                    ReleasePageButton(actions)
                }
                is UpdateState.Downloading -> OutlinedButton(
                    onClick = actions.onCancelDownload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                ) { Text(stringResource(R.string.update_action_cancel_download)) }
                is UpdateState.ReadyToInstall -> {
                    if (mode.canInstall) {
                        PrimaryActionButton(stringResource(R.string.update_action_install), actions.onInstall, enabled = !mode.importRunning)
                    } else {
                        PrimaryActionButton(stringResource(R.string.update_action_allow_install), actions.onOpenInstallSettings)
                    }
                    ReleasePageButton(actions)
                }
                is UpdateState.Installing -> Unit
                is UpdateState.Failed -> when {
                    mode.failureAction == FailureAction.OPEN_INSTALL_SETTINGS && !mode.canInstall -> {
                        PrimaryActionButton(stringResource(R.string.update_action_allow_install), actions.onOpenInstallSettings)
                        ReleasePageButton(actions)
                    }
                    mode.failureAction == FailureAction.OPEN_RELEASE_PAGE -> {
                        PrimaryActionButton(stringResource(R.string.update_action_release_page), actions.onOpenReleasePage)
                        SecondaryTextButton(stringResource(R.string.action_retry), actions.onRetry)
                    }
                    else -> {
                        PrimaryActionButton(stringResource(R.string.action_retry), actions.onRetry)
                        ReleasePageButton(actions)
                    }
                }
            }
            is UpdateSheetMode.JustUpdated -> PrimaryActionButton(stringResource(R.string.update_action_ok), actions.onClose)
            is UpdateSheetMode.Crash -> {
                PrimaryActionButton(stringResource(R.string.update_action_copy_crash), actions.onCopyCrash)
                SecondaryTextButton(stringResource(R.string.action_close), actions.onClose)
            }
        }
    }
}

@Composable
private fun ReleasePageButton(actions: UpdateSheetActions) {
    TextButton(
        onClick = actions.onOpenReleasePage,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
    ) {
        Text(stringResource(R.string.update_action_release_page))
        Spacer(Modifier.width(Spacing.xs))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
    }
}

@Composable
private fun SecondaryTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text(text) }
}

// ---------------- 조각 ----------------

private enum class Tone { Accent, Success, Error, Warning }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Accent -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    Tone.Success -> LocalStatusColors.current.successContainer to LocalStatusColors.current.onSuccessContainer
    Tone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    Tone.Warning -> LocalStatusColors.current.warningContainer to LocalStatusColors.current.onWarningContainer
}

private val KoreanLocale = LocaleList("ko-KR")

/** 한국어 줄바꿈: 어절(띄어쓰기) 단위로 끊는다("종료되었습니\n다" 방지). API 33 미만은 기본 동작. */
private fun TextStyle.korean(heading: Boolean = false): TextStyle = copy(
    lineBreak = (if (heading) LineBreak.Heading else LineBreak.Paragraph).copy(wordBreak = LineBreak.WordBreak.Phrase),
    localeList = KoreanLocale,
)

@Composable
private fun BodyText(text: String, large: Boolean = false, muted: Boolean = false) {
    Text(
        text,
        style = (if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).korean(),
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/** 아이콘 + 한 줄 상태(색만으로 의미를 전하지 않도록 아이콘 모양과 문구를 함께) */
@Composable
private fun IconLine(icon: ImageVector, kind: StatusKind, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(icon, contentDescription = null, tint = statusColor(kind), modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
        Text(text, style = MaterialTheme.typography.bodyMedium.korean(), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 아이콘(색 원) + 제목 + 보조 설명. 상태는 아이콘 모양과 제목 문구로도 구분된다. */
@Composable
private fun SheetHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector? = null,
    tone: Tone = Tone.Accent,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
        val (container, content) = toneColors(tone)
        Box(
            modifier = Modifier.size(Spacing.iconLarge).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(Spacing.iconMedium))
            }
        }
        Column(
            Modifier.weight(1f).heightIn(min = Spacing.iconLarge),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.korean(heading = true),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium.korean(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeaderSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(Spacing.iconMedium),
        strokeWidth = Spacing.xxs / 2,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun MetaLine(info: UpdateInfo) {
    val parts = buildList {
        info.sizeBytes?.let { add(stringResource(R.string.update_meta_size, Format.bytes(it))) }
        publishedEpoch(info.publishedAt)?.let { add(stringResource(R.string.update_meta_published, Format.monthDay(it))) }
    }
    if (parts.isNotEmpty()) BodyText(parts.joinToString(" · "))
}

@Composable
private fun NotesSection(notes: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.update_notes_title),
            style = MaterialTheme.typography.titleSmall.korean(heading = true),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val lines = notes?.let(::noteLines).orEmpty()
            Column(
                modifier = Modifier
                    .heightIn(max = NotesMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                if (lines.isEmpty()) {
                    BodyText(stringResource(R.string.update_notes_empty), muted = true)
                }
                lines.forEach { line -> NoteLineView(line) }
            }
        }
    }
}

@Composable
private fun NoteLineView(line: NoteLine) {
    val style = MaterialTheme.typography.bodyMedium.korean()
    val color = MaterialTheme.colorScheme.onSurface
    when (line) {
        is NoteLine.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("•", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line.text, style = style, color = color, modifier = Modifier.weight(1f))
        }
        is NoteLine.Heading -> Text(line.text, style = MaterialTheme.typography.titleSmall.korean(heading = true), color = color)
        is NoteLine.Plain -> Text(line.text, style = style, color = color)
        NoteLine.Blank -> Spacer(Modifier.size(Spacing.xxs))
    }
}

@Composable
private fun DownloadProgress(info: UpdateInfo, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (progress >= 0f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val size = info.sizeBytes
            val left = when {
                progress < 0f -> stringResource(R.string.update_downloading_waiting)
                size != null -> stringResource(
                    R.string.update_downloading_progress,
                    Format.bytes((size * progress.toDouble()).toLong()),
                    Format.bytes(size),
                )
                else -> null
            }
            Text(
                left.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (progress >= 0f) {
                Text(
                    stringResource(R.string.update_downloading_percent, (progress * 100).roundToInt().coerceIn(0, 100)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // 앱을 백그라운드로 보내면 시스템이 프로세스를 얼려 받기가 끊길 수 있다(돌아오면 한 번 다시 받는다)
        IconLine(Icons.Outlined.Info, StatusKind.INFO, stringResource(R.string.update_downloading_background))
    }
}

@Composable
private fun PermissionCallout() {
    val (container, content) = toneColors(Tone.Warning)
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(Spacing.iconMedium))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.update_permission_title), style = MaterialTheme.typography.titleSmall.korean(heading = true))
                Text(stringResource(R.string.update_permission_body), style = MaterialTheme.typography.bodyMedium.korean())
            }
        }
    }
}

@Composable
private fun LocalBuildNote() {
    IconLine(Icons.Outlined.Info, StatusKind.INFO, stringResource(R.string.update_local_channel_note))
}

// ---------------- 순수 함수 ----------------

/** "v0.2.0으로" / "v0.2.1로": 끝 숫자 발음(영·삼·육 = 받침 있음, 일·칠·팔 = ㄹ받침)에 맞는 조사 */
@StringRes
internal fun justUpdatedTitleRes(versionName: String): Int = when (versionName.lastOrNull { it.isLetterOrDigit() }) {
    '0', '3', '6' -> R.string.update_just_updated_title_euro
    else -> R.string.update_just_updated_title_ro
}

internal sealed interface NoteLine {
    data class Bullet(val text: String) : NoteLine
    data class Heading(val text: String) : NoteLine
    data class Plain(val text: String) : NoteLine
    data object Blank : NoteLine
}

/** 릴리스 노트(마크다운 일부) → 줄 목록: "- "/"* " = 글머리, "#" = 소제목, 강조 기호 제거, 빈 줄은 하나로 */
internal fun noteLines(raw: String): List<NoteLine> {
    val out = mutableListOf<NoteLine>()
    for (line in raw.replace("\r\n", "\n").lines()) {
        val t = line.trim().replace("**", "").replace("`", "")
        val item = when {
            t.isEmpty() -> NoteLine.Blank
            t.startsWith("- ") || t.startsWith("* ") -> NoteLine.Bullet(t.substring(2).trim())
            t.startsWith("#") -> NoteLine.Heading(t.trimStart('#').trim())
            else -> NoteLine.Plain(t)
        }
        if (item == NoteLine.Blank && (out.isEmpty() || out.last() == NoteLine.Blank)) continue
        out += item
    }
    while (out.lastOrNull() == NoteLine.Blank) out.removeAt(out.lastIndex)
    return out
}

internal fun publishedEpoch(published: String?): Long? =
    published?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

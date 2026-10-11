package com.leepy0.photocull.importer

import com.leepy0.photocull.data.db.ImportStatus
import java.io.File

/**
 * 강제 종료 뒤 정리. 가져오기가 돌고 있지 않을 때만 부른다(앱 시작 시, 가져오기 시작 전).
 * - 저장 폴더의 *.photocull.part: [PartFileRecovery] 규칙(검증된 유일한 복사본이면 되살리고, 아니면 삭제)
 * - PENDING/COPIED로 멈춘 행: 최종 파일이 있고 기록된 해시와 같으면 VERIFIED(이름 변경은 검증 뒤에만 하므로),
 *   아니면 폰에 파일이 없는 실패 행으로 정리. 카드 원본은 지우지 않았으므로 다음 가져오기에서 다시 대상이 된다.
 */
class ImportRecovery(
    private val ledger: ImportLedger,
    private val io: DestinationFiles = DestinationFiles.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [restoredPartFiles]: 이름 변경이 디스크에 남지 못해 .part로 남은 검증된 복사본을 되살린 수 */
    data class Report(val removedPartFiles: Int, val recoveredItems: Int, val abandonedItems: Int, val restoredPartFiles: Int = 0)

    suspend fun run(destRoot: File): Report {
        val parts = PartFileRecovery(ledger, io, clock)
        var removed = 0
        var restored = 0
        fun count(action: PartFileRecovery.Action) {
            when (action) {
                PartFileRecovery.Action.REMOVED -> removed++
                PartFileRecovery.Action.RESTORED -> restored++
                PartFileRecovery.Action.KEPT -> Unit
            }
        }
        for (part in PartFiles.list(destRoot)) count(parts.handle(part))
        // 저장 폴더 설정이 바뀌었어도 멈춘 행의 .part는 같은 규칙으로 처리한다
        for (item in ledger.unfinishedItems()) {
            val dst = item.dstPath?.let(::File) ?: continue
            val part = PartFiles.partOf(dst)
            if (part.exists()) count(parts.handle(part))
        }

        var recovered = 0
        var abandoned = 0
        val buffer = ByteArray(ImportEngine.BUFFER_SIZE)
        // .part 처리로 VERIFIED가 된 행은 빠지도록 다시 읽는다
        for (item in ledger.unfinishedItems()) {
            val dst = item.dstPath?.let(::File)
            val sha = item.sha256
            val intact = item.importStatus == ImportStatus.COPIED && dst != null && sha != null &&
                dst.isFile && dst.length() == item.srcSize && Sha256.ofFile(io, dst, buffer) == sha
            if (intact) {
                if (ledger.setStatusIf(item.id, setOf(ImportStatus.COPIED), ImportStatus.VERIFIED, clock())) recovered++
            } else {
                ledger.updateImportFields(
                    item.copy(
                        importStatus = ImportStatus.FAILED,
                        importError = "INTERRUPTED",
                        dstPath = null,
                        sha256 = null,
                        isPrimary = false,
                        unitKey = UnitKeys.detached(item.sessionId, item.srcRelPath),
                        updatedAt = clock(),
                    ),
                )
                abandoned++
            }
        }
        return Report(removed, recovered, abandoned, restored)
    }
}

/**
 * 남은 *.photocull.part 하나 처리. 엔진 시작과 [ImportRecovery]가 같은 규칙을 쓴다.
 *
 * .part는 유일한 복사본일 수 있다: .part를 fsync하고 이름을 바꾼 직후 카드 원본을 지웠는데 전원이 꺼지면
 * 이름 변경이 디스크에 남지 못해 폰에는 .part만 남는다. 그래서 무조건 지우지 않는다.
 * - 최종 이름의 파일이 이미 있으면 남은 찌꺼기 → 삭제
 * - 최종 경로를 가리키는 기록(상태 무관)의 해시와 크기가 같으면 → 최종 이름으로 되살림(덮어쓰지 않음).
 *   그 행이 PENDING/COPIED면 VERIFIED로(그 밖의 상태는 그대로)
 * - 그 밖에는 검증 전에 멈춘 복사 → 삭제. 카드 원본은 VERIFIED 뒤에만 지우므로 카드에 그대로 있다.
 * - 읽을 수 없거나 되살리지 못하면 지우지 않고 둔다
 */
internal class PartFileRecovery(
    private val ledger: ImportLedger,
    private val io: DestinationFiles,
    private val clock: () -> Long,
) {
    enum class Action { REMOVED, RESTORED, KEPT }

    private val buffer by lazy { ByteArray(ImportEngine.BUFFER_SIZE) }

    suspend fun handle(part: File): Action {
        val target = PartFiles.targetOf(part) ?: return Action.KEPT
        if (target.exists()) return remove(part)
        val size = part.length()
        val candidates = ledger.itemsAt(target.path).filter { it.sha256 != null && it.srcSize == size }
        if (candidates.isNotEmpty()) {
            val hash = Sha256.ofFile(io, part, buffer) ?: return Action.KEPT
            val match = candidates.firstOrNull { it.sha256 == hash }
            if (match != null) {
                if (!io.moveNoReplace(part, target)) return Action.KEPT
                runCatching { io.syncRenamed(target) }
                if (match.srcLastModified > 0) runCatching { target.setLastModified(match.srcLastModified) }
                ledger.setStatusIf(match.id, setOf(ImportStatus.PENDING, ImportStatus.COPIED), ImportStatus.VERIFIED, clock())
                return Action.RESTORED
            }
        }
        return remove(part)
    }

    private fun remove(part: File): Action =
        if (runCatching { part.delete() }.getOrDefault(false)) Action.REMOVED else Action.KEPT
}

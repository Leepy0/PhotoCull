package com.leepy0.photocull.importer

import com.leepy0.photocull.data.db.ImportStatus
import java.io.File

/**
 * 강제 종료 뒤 정리. 가져오기가 돌고 있지 않을 때만 부른다(앱 시작 시, 가져오기 시작 전).
 * - 저장 폴더의 *.photocull.part 삭제(이 접미사만)
 * - PENDING/COPIED로 멈춘 행: 최종 파일이 있고 기록된 해시와 같으면 VERIFIED(이름 변경은 검증 뒤에만 하므로),
 *   아니면 폰에 파일이 없는 실패 행으로 정리. 카드 원본은 지우지 않았으므로 다음 가져오기에서 다시 대상이 된다.
 */
class ImportRecovery(
    private val ledger: ImportLedger,
    private val io: DestinationFiles = DestinationFiles.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Report(val removedPartFiles: Int, val recoveredItems: Int, val abandonedItems: Int)

    suspend fun run(destRoot: File): Report {
        var parts = PartFiles.clean(destRoot)
        var recovered = 0
        var abandoned = 0
        val buffer = ByteArray(ImportEngine.BUFFER_SIZE)
        for (item in ledger.unfinishedItems()) {
            val dst = item.dstPath?.let(::File)
            // 저장 폴더 설정이 바뀌었어도 그 행의 .part는 지운다
            if (dst != null) {
                val part = PartFiles.partOf(dst)
                if (part.exists() && part.delete()) parts++
            }
            val sha = item.sha256
            val intact = item.importStatus == ImportStatus.COPIED && dst != null && sha != null &&
                dst.isFile && dst.length() == item.srcSize && Sha256.ofFile(io, dst, buffer) == sha
            if (intact) {
                ledger.updateItem(item.copy(importStatus = ImportStatus.VERIFIED, updatedAt = clock()))
                recovered++
            } else {
                ledger.updateItem(
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
        return Report(parts, recovered, abandoned)
    }
}

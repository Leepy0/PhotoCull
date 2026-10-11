package com.leepy0.photocull.importer

import com.leepy0.photocull.data.db.ImportedKey
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.storage.CardFile
import com.leepy0.photocull.storage.CardFileKind
import com.leepy0.photocull.storage.CardFileTypes
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.storage.StoragePaths
import java.util.Locale

data class UnitFile(
    val file: CardFile,
    val kind: CardFileKind,
    /** 이전 기록(상대 경로·크기·수정 시각)과 같음 */
    val alreadyImported: Boolean,
)

/** 촬영 단위: 같은 폴더 + 같은 기본 이름(JPG+CR3 등). 영상은 파일마다 단위. */
data class ShotUnit(
    /** 단위 키의 경로 부분. 보통 카드 상대 경로에서 확장자를 뺀 것 */
    val keyPath: String,
    /** 대표 파일(JPG/JPEG > HIF/HEIF > CR3)이 맨 앞 */
    val files: List<UnitFile>,
) {
    val primary: UnitFile get() = files.first()
    val isVideo: Boolean get() = primary.kind.isVideo
}

data class ScanPlan(
    val summary: CardScanSummary,
    val units: List<ShotUnit>,
) {
    val volume: CardVolume get() = summary.volume

    /** 새로 가져올 파일이 있는 사진 단위 수(카드 연결 알림용) */
    val newPhotoUnits: Int get() = units.count { u -> !u.isVideo && u.primary.kind.mediaType == MediaType.PHOTO && u.files.any { !it.alreadyImported } }
    val newVideoUnits: Int get() = units.count { u -> u.isVideo && !u.primary.alreadyImported }
}

/** 카드 파일 목록 → 촬영 단위와 가져오기 전 요약. 순수 함수라 JVM에서 바로 테스트한다. */
object CardScanner {

    fun plan(
        volume: CardVolume,
        files: List<CardFile>,
        importedKeys: Collection<ImportedKey>,
        freeBytes: Long,
        destDisplayPath: String,
    ): ScanPlan {
        val ledger = importedKeys.mapTo(HashSet()) { Triple(it.srcRelPath, it.srcSize, it.srcLastModified) }
        val typed = files.mapNotNull { f ->
            CardFileTypes.kindOf(f.name)?.let { kind -> UnitFile(f, kind, Triple(f.relPath, f.size, f.lastModified) in ledger) }
        }
        val (videos, stills) = typed.partition { it.kind.isVideo }

        val stillGroups = stills.groupBy { groupKey(it.file) }
        val units = ArrayList<ShotUnit>(stillGroups.size + videos.size)
        for (members in stillGroups.values) {
            val sorted = members.sortedWith(compareBy<UnitFile>({ it.kind.priority }, { it.file.name }))
            units += ShotUnit(CardFileTypes.withoutExtension(sorted.first().file.relPath), sorted)
        }
        for (video in videos) {
            // 같은 폴더에 같은 이름 사진이 있으면 단위 키가 겹치지 않게 확장자까지 넣는다
            val keyPath = if (groupKey(video.file) in stillGroups) video.file.relPath else CardFileTypes.withoutExtension(video.file.relPath)
            units += ShotUnit(keyPath, listOf(video))
        }
        units.sortWith(compareBy({ it.primary.file.relPath.lowercase(Locale.ROOT) }, { it.primary.file.relPath }))

        val newFiles = typed.filterNot { it.alreadyImported }
        val newBytes = newFiles.sumOf { it.file.size }
        val rangeSource = newFiles.ifEmpty { typed }.filter { it.file.lastModified > 0 }
        val summary = CardScanSummary(
            volume = volume,
            photoCount = units.count { !it.isVideo && it.primary.kind.mediaType == MediaType.PHOTO },
            rawCount = typed.count { it.kind == CardFileKind.CR3 },
            videoCount = videos.size,
            fileCount = typed.size,
            totalBytes = typed.sumOf { it.file.size },
            alreadyImportedCount = typed.size - newFiles.size,
            newFileCount = newFiles.size,
            newBytes = newBytes,
            freeBytes = freeBytes,
            requiredBytes = newBytes + StoragePaths.SPACE_MARGIN_BYTES,
            destDisplayPath = destDisplayPath,
            oldestCapturedAt = rangeSource.minOfOrNull { it.file.lastModified },
            newestCapturedAt = rangeSource.maxOfOrNull { it.file.lastModified },
        )
        return ScanPlan(summary, units)
    }

    /** 대소문자 무시(FAT) 같은 폴더 + 같은 기본 이름 */
    private fun groupKey(file: CardFile): String =
        CardFileTypes.withoutExtension(file.relPath).lowercase(Locale.ROOT)
}

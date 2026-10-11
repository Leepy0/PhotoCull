package com.leepy0.photocull.importer

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.ImportedKey
import com.leepy0.photocull.storage.CardAccess
import com.leepy0.photocull.storage.CardFile
import com.leepy0.photocull.storage.CardFileKind
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.storage.StoragePaths
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CardScannerTest {
    private val volume = CardVolume("3A1B-27F0", "EOS_DIGITAL", "/storage/3A1B-27F0", null, CardAccess.DIRECT)

    private fun f(rel: String, size: Long = 100, mtime: Long = 1_000) = CardFile(rel, size, mtime, "/storage/3A1B-27F0/$rel")

    @Test fun groupsShotUnitsWithPrimaryPriority() {
        val files = listOf(
            f("DCIM/100CANON/IMG_0001.CR3", 30),
            f("DCIM/100CANON/IMG_0001.JPG", 10),
            f("DCIM/100CANON/IMG_0002.HIF", 20),
            f("DCIM/100CANON/IMG_0002.CR3", 30),
            f("DCIM/100CANON/IMG_0003.CR3", 30),
            f("DCIM/100CANON/img_0004.jpg", 10),
            f("DCIM/100CANON/IMG_0004.CR3", 30), // 대소문자만 다른 짝(FAT)
            f("DCIM/100CANON/MVI_0005.MP4", 500),
            f("DCIM/100CANON/MVI_0006.MOV", 400),
        )
        val plan = CardScanner.plan(volume, files, emptyList(), 0, "DCIM/R10")
        val byKey = plan.units.associateBy { it.keyPath }
        assertThat(byKey.getValue("DCIM/100CANON/IMG_0001").files.map { it.kind }).containsExactly(CardFileKind.JPEG, CardFileKind.CR3).inOrder()
        assertThat(byKey.getValue("DCIM/100CANON/IMG_0002").primary.kind).isEqualTo(CardFileKind.HEIF)
        assertThat(byKey.getValue("DCIM/100CANON/IMG_0003").primary.kind).isEqualTo(CardFileKind.CR3)
        assertThat(byKey.getValue("DCIM/100CANON/img_0004").files).hasSize(2)
        assertThat(byKey.getValue("DCIM/100CANON/MVI_0005.MP4").isVideo).isTrue()
        assertThat(plan.units).hasSize(6)

        val s = plan.summary
        assertThat(s.photoCount).isEqualTo(3) // 0001(JPG), 0002(HIF), 0004(JPG). RAW만인 0003은 사진 수에 없음
        assertThat(s.rawCount).isEqualTo(4)
        assertThat(s.videoCount).isEqualTo(2)
        assertThat(s.fileCount).isEqualTo(9)
        assertThat(s.totalBytes).isEqualTo(30 + 10 + 20 + 30 + 30 + 10 + 30 + 500 + 400L)
    }

    @Test fun videoWithSameBaseAsPhotoGetsDistinctUnitKey() {
        val plan = CardScanner.plan(
            volume,
            listOf(f("DCIM/100CANON/IMG_0001.JPG"), f("DCIM/100CANON/IMG_0001.MP4")),
            emptyList(), 0, "DCIM/R10",
        )
        assertThat(plan.units.map { it.keyPath }).containsExactly("DCIM/100CANON/IMG_0001", "DCIM/100CANON/IMG_0001.MP4")
    }

    @Test fun videosWithSameBaseNameGetDistinctUnitKeys() {
        // A.MP4와 A.MOV가 같은 단위 키면 둘 다 대표로 나오고 남기기/삭제가 둘에 함께 적용된다
        val plan = CardScanner.plan(
            volume,
            listOf(f("DCIM/100CANON/MVI_0001.MP4"), f("DCIM/100CANON/MVI_0001.MOV")),
            emptyList(), 0, "DCIM/R10",
        )
        assertThat(plan.units.map { it.keyPath }).containsExactly("DCIM/100CANON/MVI_0001.MOV", "DCIM/100CANON/MVI_0001.MP4")
        assertThat(plan.units.all { it.isVideo && it.files.size == 1 }).isTrue()
    }

    @Test fun ledgerMatchingNeedsSamePathSizeAndTime() {
        val files = listOf(
            f("DCIM/100CANON/IMG_0001.JPG", 10, 1_000),
            f("DCIM/100CANON/IMG_0002.JPG", 10, 1_000),
            f("DCIM/100CANON/IMG_0003.JPG", 10, 1_000),
        )
        val keys = listOf(
            ImportedKey("DCIM/100CANON/IMG_0001.JPG", 10, 1_000, ImportStatus.SOURCE_DELETE_FAILED, "/x"),
            ImportedKey("DCIM/100CANON/IMG_0002.JPG", 11, 1_000, ImportStatus.VERIFIED, "/y"), // 크기가 다름 → 다른 파일
            ImportedKey("DCIM/100CANON/IMG_0003.JPG", 10, 2_000, ImportStatus.VERIFIED, "/z"), // 시각이 다름
        )
        val plan = CardScanner.plan(volume, files, keys, 0, "DCIM/R10")
        assertThat(plan.summary.alreadyImportedCount).isEqualTo(1)
        assertThat(plan.summary.newFileCount).isEqualTo(2)
        assertThat(plan.summary.newBytes).isEqualTo(20)
        assertThat(plan.newPhotoUnits).isEqualTo(2)
    }

    @Test fun spaceAndRangeUseNewFiles() {
        val files = listOf(
            f("DCIM/100CANON/IMG_0001.JPG", 1_000, 5_000),
            f("DCIM/100CANON/IMG_0002.JPG", 2_000, 9_000),
            f("DCIM/100CANON/IMG_0003.JPG", 4_000, 1_000), // 이미 가져옴
        )
        val keys = listOf(ImportedKey("DCIM/100CANON/IMG_0003.JPG", 4_000, 1_000, ImportStatus.VERIFIED, "/z"))
        val plan = CardScanner.plan(volume, files, keys, freeBytes = StoragePaths.SPACE_MARGIN_BYTES + 2_999, destDisplayPath = "DCIM/R10")
        val s = plan.summary
        assertThat(s.requiredBytes).isEqualTo(3_000 + StoragePaths.SPACE_MARGIN_BYTES)
        assertThat(s.enoughSpace).isFalse()
        assertThat(s.shortageBytes).isEqualTo(1)
        assertThat(s.oldestCapturedAt).isEqualTo(5_000)
        assertThat(s.newestCapturedAt).isEqualTo(9_000)
        assertThat(s.destDisplayPath).isEqualTo("DCIM/R10")
    }

    @Test fun emptyCard() {
        val plan = CardScanner.plan(volume, emptyList(), emptyList(), 10, "DCIM/R10")
        assertThat(plan.units).isEmpty()
        assertThat(plan.summary.fileCount).isEqualTo(0)
        assertThat(plan.summary.oldestCapturedAt).isNull()
    }

    @Test fun exifDateWithoutOffsetIsLocalTime() {
        val seoul = ZoneId.of("Asia/Seoul")
        val parsed = ExifDates.parse("2025:03:31 23:30:00", "25", null, seoul)
        val expected = LocalDateTime.of(2025, 3, 31, 23, 30).atZone(seoul).toInstant().toEpochMilli() + 250
        assertThat(parsed).isEqualTo(expected)
        // 시간대가 있으면 그대로
        val withOffset = ExifDates.parse("2025:03:31 23:30:00", null, "+09:00", ZoneId.of("UTC"))
        assertThat(withOffset).isEqualTo(LocalDateTime.of(2025, 3, 31, 23, 30).toInstant(ZoneOffset.ofHours(9)).toEpochMilli())
        assertThat(ExifDates.parse("0000:00:00 00:00:00", null, null, seoul)).isNull()
        assertThat(ExifDates.parse("garbage", null, null, seoul)).isNull()
    }
}

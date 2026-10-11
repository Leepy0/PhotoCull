package com.leepy0.photocull.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileCardSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(rel: String, size: Int = 10, mtime: Long = 1_700_000_000_000): File =
        File(tmp.root, rel).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(size) { it.toByte() })
            setLastModified(mtime)
        }

    @Test fun listsOnlyVisibleTargetFilesUnderDcim() {
        put("DCIM/100CANON/IMG_0001.JPG", 11)
        put("DCIM/100CANON/IMG_0001.CR3", 22)
        put("DCIM/100CANON/IMG_0002.hif")
        put("DCIM/100CANON/MVI_0003.MP4")
        put("DCIM/101CANON/MVI_0004.mov")
        put("DCIM/100CANON/._IMG_0001.JPG") // macOS 메타 파일
        put("DCIM/.thumbnails/IMG_0001.JPG") // 숨김 폴더
        put("DCIM/CANONMSC/M0100.CTG") // 대상 아님
        put("DCIM/100CANON/IMG_0005.THM")
        put("MISC/IMG_9999.JPG") // DCIM 밖

        val files = FileCardSource(tmp.root).listTargetFiles()
        assertThat(files.map { it.relPath }).containsExactly(
            "DCIM/100CANON/IMG_0001.CR3",
            "DCIM/100CANON/IMG_0001.JPG",
            "DCIM/100CANON/IMG_0002.hif",
            "DCIM/100CANON/MVI_0003.MP4",
            "DCIM/101CANON/MVI_0004.mov",
        ).inOrder()
        val jpg = files.first { it.name == "IMG_0001.JPG" }
        assertThat(jpg.size).isEqualTo(11)
        assertThat(jpg.lastModified).isEqualTo(1_700_000_000_000)
        assertThat(jpg.location).isEqualTo(File(tmp.root, "DCIM/100CANON/IMG_0001.JPG").path)
        assertThat(jpg.dirRelPath).isEqualTo("DCIM/100CANON")
    }

    @Test fun dcimFolderNameIsCaseInsensitiveAndKeptInRelPath() {
        put("dcim/100CANON/IMG_0001.JPG")
        val files = FileCardSource(tmp.root).listTargetFiles()
        assertThat(files.single().relPath).isEqualTo("dcim/100CANON/IMG_0001.JPG")
    }

    @Test fun cameraCardDetection() {
        assertThat(FileCardSource(tmp.root).hasTargetFile()).isFalse() // 빈 카드
        put("DCIM/CANONMSC/M0100.CTG")
        put("Documents/report.pdf")
        assertThat(FileCardSource(tmp.root).hasTargetFile()).isFalse() // DCIM은 있지만 사진 없음
        put("DCIM/100CANON/deep/er/IMG_0001.JPG")
        assertThat(FileCardSource(tmp.root).hasTargetFile()).isTrue()
    }

    @Test fun detectionStopsAtEntryBudget() {
        repeat(50) { put("DCIM/100CANON/NOTE_$it.TXT") }
        put("DCIM/100CANON/zz/IMG_0001.JPG")
        assertThat(FileCardSource(tmp.root).hasTargetFile(maxEntries = 10)).isFalse()
        assertThat(FileCardSource(tmp.root).hasTargetFile()).isTrue()
    }

    @Test fun noDcimMeansEmptyList() {
        put("MISC/IMG_0001.JPG")
        assertThat(FileCardSource(tmp.root).listTargetFiles()).isEmpty()
    }

    @Test(expected = IOException::class)
    fun unreadableRootThrows() {
        FileCardSource(File(tmp.root, "missing")).listTargetFiles()
    }

    @Test fun deleteAndPresence() {
        put("DCIM/100CANON/IMG_0001.JPG")
        var present = true
        val source = FileCardSource(tmp.root) { present }
        val file = source.listTargetFiles().single()
        assertThat(source.isPresent()).isTrue()
        assertThat(source.exists(file)).isTrue()
        assertThat(source.delete(file)).isTrue()
        assertThat(source.exists(file)).isFalse()
        present = false
        assertThat(source.isPresent()).isFalse()
    }

    @Test fun fileTypes() {
        assertThat(CardFileTypes.kindOf("IMG_0001.JPEG")).isEqualTo(CardFileKind.JPEG)
        assertThat(CardFileTypes.kindOf("IMG_0001.HEIF")).isEqualTo(CardFileKind.HEIF)
        assertThat(CardFileTypes.kindOf("IMG_0001.Cr3")).isEqualTo(CardFileKind.CR3)
        assertThat(CardFileTypes.kindOf("IMG_0001.CR2")).isNull()
        assertThat(CardFileTypes.kindOf(".IMG_0001.JPG")).isNull()
        assertThat(CardFileTypes.kindOf("JPG")).isNull()
        assertThat(CardFileTypes.withoutExtension("DCIM/100CANON/IMG_0001.JPG")).isEqualTo("DCIM/100CANON/IMG_0001")
        assertThat(CardFileTypes.withoutExtension("DCIM/1.00/IMG")).isEqualTo("DCIM/1.00/IMG")
    }
}

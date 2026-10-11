package com.leepy0.photocull.storage

import android.os.Environment
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 폰 쪽 저장 위치. 모두 기본 저장소(/storage/emulated/0) 안이라 휴지통 이동은 이름 변경으로 즉시 끝난다. */
object StoragePaths {
    /** 복사 중 임시 파일 접미사. 이 접미사가 붙은 파일만 정리 대상으로 삼는다. */
    const val PART_SUFFIX = ".photocull.part"

    /** 가져오기 시 여유 공간 여유분 */
    const val SPACE_MARGIN_BYTES: Long = 2L * 1024 * 1024 * 1024

    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    fun dcimDir(root: File = primaryRoot): File = File(root, Environment.DIRECTORY_DCIM)

    /** 사용자에게 보여줄 상대 경로. 예: "DCIM/R10" */
    fun destDisplayPath(folderName: String): String = "${Environment.DIRECTORY_DCIM}/${sanitizeFolderName(folderName)}"

    fun destRoot(folderName: String, root: File = primaryRoot): File = File(dcimDir(root), sanitizeFolderName(folderName))

    /** 실제 저장 폴더. 월별 하위 폴더 설정이면 촬영 월(yyyy-MM) 폴더 */
    fun destDir(folderName: String, monthly: Boolean, capturedAt: Long, root: File = primaryRoot): File {
        val base = destRoot(folderName, root)
        if (!monthly) return base
        val month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(capturedAt))
        return File(base, month)
    }

    /** 휴지통. .nomedia로 갤러리에서 숨긴다. */
    fun trashDir(root: File = primaryRoot): File = File(dcimDir(root), ".PhotoCullTrash")

    fun ensureTrashDir(root: File = primaryRoot): File {
        val dir = trashDir(root)
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    /** 폴더 이름에 경로 구분자·숨김 접두사가 들어가지 않게 정리 */
    fun sanitizeFolderName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trimStart('.')
        return cleaned.ifBlank { "R10" }
    }
}

package com.leepy0.photocull.update

import android.content.Context
import java.io.File

/** 업데이트 관련 파일 위치와 변경 내용 캐시 */
object UpdateFiles {
    /** 받은 APK: cacheDir/update/ */
    fun downloadDir(context: Context): File = File(context.cacheDir, "update")

    /** 받을 때 저장해 두는 변경 내용(설치 후 첫 실행에서 보여줌): filesDir/update/last_notes.txt */
    fun notesFile(context: Context): File = File(File(context.filesDir, "update"), "last_notes.txt")

    fun writeNotes(context: Context, info: UpdateInfo) = writeNotes(notesFile(context), info.versionCode, info.notes)

    /** [versionCode]용으로 저장된 변경 내용. 다른 버전 것이거나 없으면 null */
    fun readNotes(context: Context, versionCode: Int): String? = readNotes(notesFile(context), versionCode)

    internal fun writeNotes(file: File, versionCode: Int, notes: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText("$HEADER$versionCode\n$notes")
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    internal fun readNotes(file: File, versionCode: Int): String? = runCatching {
        if (!file.isFile) return null
        val text = file.readText()
        val header = text.substringBefore('\n')
        if (header.removePrefix(HEADER).toIntOrNull() != versionCode) return null
        text.substringAfter('\n', "").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private const val HEADER = "versionCode="
}

package com.leepy0.photocull.trash

import com.leepy0.photocull.storage.StoragePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * 휴지통 파일 작업. 어떤 경우에도 이미 있는 파일을 덮어쓰지 않고, 원본은 옮긴 곳에 온전한 사본이 생긴 뒤에만 사라진다.
 */
internal object TrashFiles {
    private const val COPY_BUFFER = 1 shl 20
    private const val MAX_RESTORE_SUFFIX = 999

    /**
     * [src]를 [dst]로 옮긴다. 같은 저장소라 보통 이름 변경으로 즉시 끝난다.
     * 이름 변경이 안 되면 `.photocull.part`로 복사 → 크기 확인 → 최종 이름 → 원본 삭제 순으로 처리하고,
     * 원본을 지우지 못하면 사본을 지워 원래 상태로 돌린다.
     * @return 옮겼으면 true. false면 [src]는 그대로 있고 [dst]는 만들어지지 않았다.
     */
    fun move(src: File, dst: File): Boolean {
        if (!src.isFile || dst.exists()) return false
        dst.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        try {
            // 옵션 없는 Files.move: 대상이 있으면 실패(덮어쓰지 않음)
            Files.move(src.toPath(), dst.toPath())
            return true
        } catch (e: FileAlreadyExistsException) {
            return false
        } catch (e: Exception) {
            // 이름 변경 실패(드문 FUSE 동작 등) → 복사로 대체
        }
        return copyThenDelete(src, dst)
    }

    private fun copyThenDelete(src: File, dst: File): Boolean {
        val part = File(dst.parentFile, dst.name + StoragePaths.PART_SUFFIX)
        try {
            if (part.exists() && !part.delete()) return false
            val expected = src.length()
            src.inputStream().use { input ->
                FileOutputStream(part).use { out ->
                    input.copyTo(out, COPY_BUFFER)
                    out.fd.sync()
                }
            }
            if (part.length() != expected) {
                part.delete()
                return false
            }
            part.setLastModified(src.lastModified())
            try {
                Files.move(part.toPath(), dst.toPath())
            } catch (e: Exception) {
                part.delete()
                return false
            }
            if (!src.delete() && src.exists()) {
                // 원본을 지우지 못했으면 사본을 지워 같은 사진이 두 곳에 남지 않게 한다
                dst.delete()
                return false
            }
            return true
        } catch (e: IOException) {
            part.delete()
            return false
        } catch (e: SecurityException) {
            part.delete()
            return false
        }
    }

    /** 휴지통 안 이름: "<itemId>_<파일명>". 남은 파일과 겹치면 "<itemId>_<n>_<파일명>" */
    fun uniqueTrashFile(dir: File, itemId: Long, fileName: String): File {
        val first = File(dir, "${itemId}_$fileName")
        if (!first.exists()) return first
        var n = 1
        while (true) {
            val candidate = File(dir, "${itemId}_${n}_$fileName")
            if (!candidate.exists() && !File(candidate.path + StoragePaths.PART_SUFFIX).exists()) return candidate
            n++
        }
    }

    /**
     * 복원 위치. 원래 자리가 비어 있으면 그대로, 하나라도 차 있으면 촬영 단위 전체에 같은 접미사
     * "<이름>_restored<n>.<확장자>"를 붙여 JPG와 CR3 짝이 깨지지 않게 한다.
     * @return 항목 id → 복원할 파일. 빈 자리를 찾지 못하면 null
     */
    fun restoreTargets(originals: Map<Long, File>): Map<Long, File>? {
        if (originals.isEmpty()) return emptyMap()
        for (n in 0..MAX_RESTORE_SUFFIX) {
            val candidates = originals.mapValues { (_, f) -> if (n == 0) f else withSuffix(f, "_restored$n") }
            val distinct = candidates.values.map { it.path.lowercase() }.toSet().size == candidates.size
            if (distinct && candidates.values.none { it.exists() || File(it.path + StoragePaths.PART_SUFFIX).exists() }) {
                return candidates
            }
        }
        return null
    }

    fun withSuffix(file: File, suffix: String): File {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val renamed = if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
        return File(file.parentFile, renamed)
    }

    /** [file]이 휴지통 폴더 바로 아래 파일인지(휴지통 밖 파일은 절대 지우지 않는다) */
    fun isInside(dir: File, file: File): Boolean =
        file.absoluteFile.normalize().parentFile == dir.absoluteFile.normalize()

    /** 중단된 복사가 남긴 `.photocull.part`만 지운다 */
    fun cleanPartFiles(dir: File): Int {
        val leftovers = dir.listFiles { f -> f.isFile && f.name.endsWith(StoragePaths.PART_SUFFIX) } ?: return 0
        return leftovers.count { it.delete() }
    }
}

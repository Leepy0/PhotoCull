package com.leepy0.photocull.trash

import com.leepy0.photocull.storage.StoragePaths
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 휴지통 파일 작업. 어떤 경우에도 이미 있는 파일을 덮어쓰지 않고, 원본은 옮긴 곳에 온전한 사본이 생긴 뒤에만 사라진다.
 */
internal object TrashFiles {
    /**
     * 휴지통 복사 중 임시 파일 접미사. 가져오기의 [StoragePaths.PART_SUFFIX]와 달라서 서로의 임시 파일을
     * 덮거나 정리하지 않는다. 휴지통이 정리하는 임시 파일은 이 접미사뿐이다.
     */
    const val TRASH_PART_SUFFIX = ".photocull-trash.part"

    private const val COPY_BUFFER = 1 shl 20
    private const val MAX_RESTORE_SUFFIX = 999
    private val TRASH_NAME = Regex("^(\\d+)_(?:(\\d+)_)?(.+)$")

    /**
     * [src]를 [dst]로 옮긴다. 같은 저장소라 보통 이름 변경으로 즉시 끝난다.
     * 이름 변경이 안 되면 [copyThenDelete]로 처리한다.
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

    /**
     * 복사로 옮기기: `<dst>.photocull-trash.part`를 새로 만들어(이미 있으면 포기) 복사하며 원본 SHA-256 계산 →
     * fsync → 사본을 다시 읽어 SHA-256 비교 → 최종 이름 → 원본 삭제. 원본을 지우지 못하면 사본을 지워 원래 상태로 돌린다.
     * [hashOf]는 사본 해시 계산(테스트에서 바꿔 끼운다).
     */
    fun copyThenDelete(src: File, dst: File, hashOf: (File) -> String = ::sha256Of): Boolean {
        if (!src.isFile || dst.exists()) return false
        val part = File(dst.parentFile, dst.name + TRASH_PART_SUFFIX)
        var created = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            // CREATE_NEW: 같은 이름의 임시 파일이 있으면 남의 것일 수 있으니 지우지 않고 포기한다
            FileChannel.open(part.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                created = true
                src.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        val chunk = ByteBuffer.wrap(buffer, 0, n)
                        while (chunk.hasRemaining()) out.write(chunk)
                    }
                }
                out.force(true)
            }
            val sourceHash = hex(digest.digest())
            if (part.length() != src.length() || hashOf(part) != sourceHash) {
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
        } catch (e: FileAlreadyExistsException) {
            return false
        } catch (e: IOException) {
            if (created) part.delete()
            return false
        } catch (e: SecurityException) {
            if (created) part.delete()
            return false
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** 휴지통 안 이름: "<itemId>_<파일명>". 남은 파일과 겹치면 "<itemId>_<n>_<파일명>" */
    fun uniqueTrashFile(dir: File, itemId: Long, fileName: String): File {
        val first = File(dir, "${itemId}_$fileName")
        if (isFree(first)) return first
        var n = 1
        while (true) {
            val candidate = File(dir, "${itemId}_${n}_$fileName")
            if (isFree(candidate)) return candidate
            n++
        }
    }

    /**
     * 휴지통 파일 이름에서 항목 id와 원래 파일명을 읽는다("<id>_<파일명>" / "<id>_<n>_<파일명>").
     * 형식이 아니면 null. "<id>_<n>_" 형태는 원래 이름이 숫자로 시작하는 경우와 겹칠 수 있어 두 후보를 모두 돌려준다.
     */
    fun parseTrashName(name: String): Pair<Long, Set<String>>? {
        if (name.endsWith(TRASH_PART_SUFFIX) || name.startsWith(".")) return null
        val m = TRASH_NAME.matchEntire(name) ?: return null
        val id = m.groupValues[1].toLongOrNull() ?: return null
        val rest = name.substring(m.groupValues[1].length + 1)
        val names = buildSet {
            add(rest)
            if (m.groupValues[2].isNotEmpty()) add(m.groupValues[3])
        }
        return id to names
    }

    /**
     * 복원 위치. 원래 자리가 비어 있으면 그대로, 하나라도 차 있으면 촬영 단위 전체에 같은 접미사
     * "<이름>_restored<n>.<확장자>"를 붙여 JPG와 CR3 짝이 깨지지 않게 한다.
     * @return 항목 id → 복원할 파일. 빈 자리를 찾지 못하면 null
     */
    fun restoreTargets(originals: Map<Long, File>): Map<Long, File>? {
        if (originals.isEmpty()) return emptyMap()
        for (n in 0..MAX_RESTORE_SUFFIX) {
            val candidates = originals.mapValues { (_, f) -> restoreCandidate(f, n) }
            val distinct = candidates.values.map { it.path.lowercase() }.toSet().size == candidates.size
            if (distinct && candidates.values.all { isFree(it) }) return candidates
        }
        return null
    }

    /** n = 0이면 원래 자리, 아니면 "<이름>_restored<n>.<확장자>" */
    fun restoreCandidate(original: File, n: Int): File = if (n == 0) original else withSuffix(original, "_restored$n")

    val restoreSuffixRange: IntRange get() = 0..MAX_RESTORE_SUFFIX

    /** 파일도, 가져오기·휴지통의 임시 파일도 없는 자리 */
    private fun isFree(file: File): Boolean =
        !file.exists() &&
            !File(file.path + StoragePaths.PART_SUFFIX).exists() &&
            !File(file.path + TRASH_PART_SUFFIX).exists()

    fun withSuffix(file: File, suffix: String): File {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val renamed = if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
        return File(file.parentFile, renamed)
    }

    /** [file]이 휴지통 폴더 바로 아래 파일인지(휴지통 밖 파일은 절대 지우지 않는다) */
    fun isInside(dir: File, file: File): Boolean =
        file.absoluteFile.normalize().parentFile == dir.absoluteFile.normalize()

    /** 중단된 휴지통 복사가 남긴 `.photocull-trash.part`만 지운다(가져오기의 `.photocull.part`는 건드리지 않는다) */
    fun cleanPartFiles(dir: File): Int {
        val leftovers = dir.listFiles { f -> f.isFile && f.name.endsWith(TRASH_PART_SUFFIX) } ?: return 0
        return leftovers.count { it.delete() }
    }
}

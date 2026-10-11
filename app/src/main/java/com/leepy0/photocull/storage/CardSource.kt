package com.leepy0.photocull.storage

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** 카드 위의 대상 파일 1개 */
data class CardFile(
    /** 카드 루트 기준 상대 경로. 예: "DCIM/100CANON/IMG_0001.JPG". 접근 방식(경로/SAF)과 무관하게 같다. */
    val relPath: String,
    val size: Long,
    val lastModified: Long,
    /** 실제 접근 위치: 파일 경로 또는 SAF 문서 URI */
    val location: String,
) {
    val name: String get() = relPath.substringAfterLast('/')

    /** "DCIM/100CANON" */
    val dirRelPath: String get() = relPath.substringBeforeLast('/', "")
}

/**
 * 카드 파일 접근. 스캐너와 가져오기 엔진은 이 인터페이스만 써서 JVM 테스트가 가능하다.
 * 구현: [FileCardSource](파일 경로), [SafCardSource](폴더 승인 트리).
 * 모든 함수는 디스크/IPC 작업이라 메인 스레드에서 부르지 않는다.
 */
interface CardSource {
    /** DCIM 아래(재귀) 대상 확장자 파일. 숨김 파일·폴더는 건너뛴다. relPath 순 정렬. DCIM이 없으면 빈 목록. */
    @Throws(IOException::class)
    fun listTargetFiles(): List<CardFile>

    /**
     * 카메라 카드 판정: DCIM 아래에 대상 파일이 하나라도 있는지. 첫 파일을 찾으면 바로 멈추고,
     * 최대 [maxEntries]개 항목까지만 본다(일반 USB 메모리에서 오래 걸리지 않게).
     */
    fun hasTargetFile(maxEntries: Int = DEFAULT_PROBE_ENTRIES): Boolean

    @Throws(IOException::class)
    fun openInput(file: CardFile): InputStream

    /** 원본 삭제. 삭제했으면 true. 실패 이유(분리/쓰기 금지)는 호출 측이 [isPresent], [exists]로 판단한다. */
    fun delete(file: CardFile): Boolean

    fun exists(file: CardFile): Boolean

    /** 카드가 아직 연결(마운트)돼 있는지. 읽기 오류가 카드 분리 때문인지 판단할 때 쓴다. */
    fun isPresent(): Boolean

    companion object {
        const val DEFAULT_PROBE_ENTRIES = 4_000

        /** 폴더 깊이 제한(비정상 파일 시스템에서 끝없이 내려가지 않게) */
        const val MAX_DEPTH = 12
    }
}

/** java.io.File로 카드에 접근(모든 파일 접근 권한 + /storage/XXXX-XXXX 경로) */
class FileCardSource(
    private val root: File,
    private val presence: () -> Boolean = { root.isDirectory },
) : CardSource {

    private fun dcimDir(): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(CardFileTypes.DCIM, ignoreCase = true) }

    override fun listTargetFiles(): List<CardFile> {
        if (root.listFiles() == null) throw IOException("card root not readable")
        val dcim = dcimDir() ?: return emptyList()
        val out = ArrayList<CardFile>()
        walk(dcim, dcim.name, 0, out)
        out.sortBy { it.relPath }
        return out
    }

    private fun walk(dir: File, rel: String, depth: Int, out: MutableList<CardFile>) {
        // 하위 폴더를 못 읽으면(손상 등) 그 폴더만 건너뛴다. 건너뛴 파일은 카드에 그대로 남는다.
        val children = dir.listFiles() ?: if (depth == 0) throw IOException("DCIM not readable") else return
        for (child in children) {
            val name = child.name
            if (CardFileTypes.isHidden(name)) continue
            if (child.isDirectory) {
                if (depth < CardSource.MAX_DEPTH) walk(child, "$rel/$name", depth + 1, out)
            } else if (CardFileTypes.isTarget(name)) {
                out += CardFile("$rel/$name", child.length(), child.lastModified(), child.path)
            }
        }
    }

    override fun hasTargetFile(maxEntries: Int): Boolean {
        val dcim = dcimDir() ?: return false
        var budget = maxEntries
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(dcim to 0)
        while (queue.isNotEmpty() && budget > 0) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (--budget <= 0) return false
                val name = child.name
                if (CardFileTypes.isHidden(name)) continue
                if (child.isDirectory) {
                    if (depth < CardSource.MAX_DEPTH) queue.add(child to depth + 1)
                } else if (CardFileTypes.isTarget(name)) {
                    return true
                }
            }
        }
        return false
    }

    override fun openInput(file: CardFile): InputStream = FileInputStream(File(file.location))

    override fun delete(file: CardFile): Boolean = File(file.location).delete()

    override fun exists(file: CardFile): Boolean = File(file.location).exists()

    override fun isPresent(): Boolean = runCatching(presence).getOrDefault(false)
}

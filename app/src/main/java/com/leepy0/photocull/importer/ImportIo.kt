package com.leepy0.photocull.importer

import android.content.Context
import android.media.MediaScannerConnection
import android.system.Os
import android.system.OsConstants
import com.leepy0.photocull.storage.StoragePaths
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/** 폰 쪽 파일 쓰기/읽기. 테스트에서 일부러 깨진 쓰기를 넣어 검증 실패 경로를 확인한다. */
interface DestinationFiles {
    @Throws(IOException::class)
    fun openForWrite(file: File): DestinationOutput

    @Throws(IOException::class)
    fun openForRead(file: File): InputStream

    /** 기존 파일을 절대 덮어쓰지 않는 이름 변경. 대상이 있으면 false */
    fun moveNoReplace(from: File, to: File): Boolean

    /**
     * 이름 변경(.part → 최종 이름)을 디스크까지 기록한다. 카드 원본을 지우기 전에 부른다.
     * 그러지 않으면 전원이 꺼진 뒤 폰에는 .part만 남고 카드 원본은 이미 지워진 상태가 될 수 있다.
     * 파일 fsync에 실패하면 IOException(폴더 fsync는 최선만 다한다).
     */
    @Throws(IOException::class)
    fun syncRenamed(file: File)

    companion object {
        val Default: DestinationFiles = FileDestinationFiles
    }
}

interface DestinationOutput : Closeable {
    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** 디스크까지 기록(fsync) */
    @Throws(IOException::class)
    fun sync()
}

object FileDestinationFiles : DestinationFiles {
    override fun openForWrite(file: File): DestinationOutput {
        val out = FileOutputStream(file)
        return object : DestinationOutput {
            override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
            override fun sync() {
                out.flush()
                out.fd.sync()
            }
            override fun close() = out.close()
        }
    }

    override fun openForRead(file: File): InputStream = FileInputStream(file)

    override fun moveNoReplace(from: File, to: File): Boolean =
        try {
            // 옵션 없는 Files.move는 대상이 있으면 FileAlreadyExistsException(덮어쓰지 않음)
            Files.move(from.toPath(), to.toPath())
            true
        } catch (e: Exception) {
            false
        }

    override fun syncRenamed(file: File) {
        // RandomAccessFile("rw")는 없는 파일을 만들므로 먼저 확인
        if (!file.isFile) throw FileNotFoundException(file.path)
        // 주 수단: 이름이 바뀐 파일을 fsync하면 ext4·f2fs 저널이 이름 변경까지 함께 커밋한다
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        // 보조: 폴더 fsync. /storage/emulated(FUSE)나 JVM 테스트에서는 실패할 수 있어 무시한다
        syncDirectory(file.parentFile)
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            // ErrnoException 등: 무시
        } catch (e: LinkageError) {
            // JVM(Robolectric)에 네이티브 구현이 없을 때
        }
    }
}

/** 갤러리 반영 요청(묶어서 보냄) */
fun interface MediaScanSink {
    fun scan(paths: List<String>, mimeTypes: List<String>)

    companion object {
        val None = MediaScanSink { _, _ -> }
    }
}

class AndroidMediaScanSink(context: Context) : MediaScanSink {
    private val app = context.applicationContext
    override fun scan(paths: List<String>, mimeTypes: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(app, paths.toTypedArray(), mimeTypes.toTypedArray(), null)
    }
}

/** 사용자 취소 신호. 엔진은 버퍼(1MB)마다 확인한다. */
class CancelSignal {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }
}

internal object Sha256 {
    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    /** 스트림 전체의 해시. [onRead]로 읽은 바이트 수를 알린다. */
    @Throws(IOException::class)
    fun of(input: InputStream, buffer: ByteArray, onRead: (Int) -> Unit = {}): String {
        val digest = newDigest()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            digest.update(buffer, 0, n)
            onRead(n)
        }
        return hex(digest)
    }

    /** 폰 파일 해시. 읽지 못하면 null */
    fun ofFile(io: DestinationFiles, file: File, buffer: ByteArray): String? =
        try {
            io.openForRead(file).use { of(it, buffer) }
        } catch (e: IOException) {
            null
        }
}

/**
 * 복사 중 파일(*.photocull.part). 이 접미사 파일만 다룬다.
 * 남은 .part를 무조건 지우면 안 된다(유일한 복사본일 수 있다): 처리는 [PartFileRecovery]가 한다.
 */
internal object PartFiles {
    private const val MAX_DEPTH = 8

    fun partOf(target: File): File = File(target.path + StoragePaths.PART_SUFFIX)

    /** .part의 최종 이름. 접미사가 없거나 이름이 접미사뿐이면 null */
    fun targetOf(part: File): File? {
        val name = part.name
        if (!name.endsWith(StoragePaths.PART_SUFFIX) || name.length == StoragePaths.PART_SUFFIX.length) return null
        return File(part.parentFile, name.removeSuffix(StoragePaths.PART_SUFFIX))
    }

    /** [root] 아래(깊이 제한)의 .part 파일 */
    fun list(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) walk(child, depth + 1)
                } else if (child.name.endsWith(StoragePaths.PART_SUFFIX)) {
                    out += child
                }
            }
        }
        walk(root, 0)
        return out
    }
}

/**
 * 촬영 단위 키. 일반 행은 "<sessionId>:<카드 상대 경로에서 확장자 뺀 것>".
 * 폰에 파일이 없는 행(실패·취소)과, 이미 있던 같은 내용 파일을 가리키는 중복 행은 따로 떼어(detached)
 * 검토·휴지통의 단위 묶음과 단위 용량 계산에 끼지 않게 한다.
 */
internal object UnitKeys {
    fun unit(sessionId: Long, keyPath: String): String = "$sessionId:$keyPath"
    fun duplicate(sessionId: Long, relPath: String): String = "$sessionId:dup:$relPath"
    fun detached(sessionId: Long, relPath: String): String = "$sessionId:failed:$relPath"

    fun isDetached(unitKey: String): Boolean {
        val rest = unitKey.substringAfter(':', "")
        return rest.startsWith("dup:") || rest.startsWith("failed:")
    }
}

package com.leepy0.photocull.importer

import android.content.Context
import android.media.MediaScannerConnection
import com.leepy0.photocull.storage.StoragePaths
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

/** 남은 복사 중 파일(*.photocull.part) 정리. 이 접미사 파일만 지운다. */
internal object PartFiles {
    private const val MAX_DEPTH = 8

    fun partOf(target: File): File = File(target.path + StoragePaths.PART_SUFFIX)

    fun clean(root: File): Int {
        if (!root.isDirectory) return 0
        var count = 0
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) walk(child, depth + 1)
                } else if (child.name.endsWith(StoragePaths.PART_SUFFIX) && child.delete()) {
                    count++
                }
            }
        }
        walk(root, 0)
        return count
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

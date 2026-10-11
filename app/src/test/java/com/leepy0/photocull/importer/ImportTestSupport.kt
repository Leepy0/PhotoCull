package com.leepy0.photocull.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.storage.CardAccess
import com.leepy0.photocull.storage.CardFile
import com.leepy0.photocull.storage.CardFileTypes
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.storage.FileCardSource
import com.leepy0.photocull.storage.StoragePaths
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/** 2026-10-12 12:00:00 UTC(달 중간이라 시간대와 무관하게 같은 달) */
const val DEFAULT_MTIME = 1_791_806_400_000L

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String = sha256(file.readBytes())

fun randomBytes(seed: Int, size: Int): ByteArray = Random(seed).nextBytes(size)

/**
 * 테스트용 카드: 임시 폴더 위의 [FileCardSource]에 고장 주입을 더했다.
 * - [present]=false → 분리된 카드(읽기·삭제 실패, isPresent=false)
 * - [removeWhileReading] 파일을 [removeAfterBytes]만큼 읽으면 분리
 * - [failDeletes] 이름의 파일은 삭제 실패(쓰기 금지)
 * - [onRead] 읽기 진행 훅(취소 테스트), [beforeDelete] 원본 삭제 직전 훅
 * - [deleteCalls] 삭제 시도 수
 */
class TestCard(val root: File) : CardSource {
    @Volatile var present = true
    var chunkSize = 64 * 1024
    var removeWhileReading: String? = null
    var removeAfterBytes = 0L
    val failDeletes = HashSet<String>()
    var onRead: ((CardFile, Long) -> Unit)? = null
    var beforeDelete: ((CardFile) -> Unit)? = null
    @Volatile var deleteCalls = 0

    /** 넣은 대상 파일의 원래 해시(relPath → sha256). 안전 불변식 확인용 */
    val originals = LinkedHashMap<String, String>()

    private val fs = FileCardSource(root) { present }

    fun put(relPath: String, bytes: ByteArray, mtime: Long = DEFAULT_MTIME): File {
        val f = File(root, relPath)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(mtime)
        if (relPath.startsWith("DCIM/") && CardFileTypes.isTarget(f.name)) originals[relPath] = sha256(bytes)
        return f
    }

    fun file(relPath: String) = File(root, relPath)

    override fun listTargetFiles(): List<CardFile> {
        if (!present) throw IOException("card gone")
        return fs.listTargetFiles()
    }

    override fun hasTargetFile(maxEntries: Int): Boolean = present && fs.hasTargetFile(maxEntries)

    override fun openInput(file: CardFile): InputStream {
        if (!present) throw IOException("card gone")
        val raw = fs.openInput(file)
        return object : FilterInputStream(raw) {
            var count = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!present) throw IOException("EIO: card gone")
                if (removeWhileReading == file.name && count >= removeAfterBytes) {
                    present = false
                    throw IOException("EIO: card removed")
                }
                val n = super.read(b, off, minOf(len, chunkSize))
                if (n > 0) {
                    count += n
                    onRead?.invoke(file, count)
                }
                return n
            }
        }
    }

    override fun delete(file: CardFile): Boolean {
        deleteCalls++
        beforeDelete?.invoke(file)
        if (!present || file.name in failDeletes) return false
        return fs.delete(file)
    }

    override fun exists(file: CardFile): Boolean = present && fs.exists(file)

    override fun isPresent(): Boolean = present
}

/** 지정한 파일의 쓰기를 깨뜨리는 폰 저장소(검증 실패 경로). [corrupt](파일명, 시도 번호) */
class CorruptingFiles(private val corrupt: (String, Int) -> Boolean) : DestinationFiles {
    val attempts = HashMap<String, Int>()

    override fun openForWrite(file: File): DestinationOutput {
        val target = file.name.removeSuffix(StoragePaths.PART_SUFFIX)
        val attempt = attempts.merge(target, 1, Int::plus)!!
        val real = DestinationFiles.Default.openForWrite(file)
        if (!corrupt(target, attempt)) return real
        return object : DestinationOutput {
            var first = true
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (first && length > 0) {
                    first = false
                    val copy = buffer.copyOfRange(offset, offset + length)
                    copy[0] = (copy[0] + 1).toByte()
                    real.write(copy, 0, length)
                } else {
                    real.write(buffer, offset, length)
                }
            }
            override fun sync() = real.sync()
            override fun close() = real.close()
        }
    }

    override fun openForRead(file: File): InputStream = DestinationFiles.Default.openForRead(file)
    override fun moveNoReplace(from: File, to: File): Boolean = DestinationFiles.Default.moveNoReplace(from, to)
    override fun syncRenamed(file: File) = DestinationFiles.Default.syncRenamed(file)
}

/** 실제 파일 위에서 이름 변경·fsync 호출을 기록하는 폰 저장소. [failSync] 이름은 fsync가 실패한다. */
open class RecordingFiles : DestinationFiles {
    /** 호출 순서: "move:<이름>", "sync:<이름>" */
    val events = ArrayList<String>()
    val failSync = HashSet<String>()
    var onSync: ((File) -> Unit)? = null

    override fun openForWrite(file: File): DestinationOutput = DestinationFiles.Default.openForWrite(file)
    override fun openForRead(file: File): InputStream = DestinationFiles.Default.openForRead(file)
    override fun moveNoReplace(from: File, to: File): Boolean =
        DestinationFiles.Default.moveNoReplace(from, to).also { if (it) events += "move:${to.name}" }

    override fun syncRenamed(file: File) {
        events += "sync:${file.name}"
        onSync?.invoke(file)
        if (file.name in failSync) throw IOException("fsync failed")
        DestinationFiles.Default.syncRenamed(file)
    }
}

/** 엔진 테스트 공통: 카드·폰 임시 폴더, 인메모리 Room, 기록용 미디어 스캔/분석 신호 */
class EngineHarness(tmp: File) {
    val db: AppDatabase = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    val ledger = RoomImportLedger(db)
    val card = TestCard(File(tmp, "card").apply { mkdirs() })
    val phone = File(tmp, "phone").apply { mkdirs() }
    val dest: File get() = StoragePaths.destRoot("R10", phone)
    val scanned = ArrayList<String>()
    var kicks = 0
    val volume = CardVolume("3A1B-27F0", "EOS_DIGITAL", card.root.path, null, CardAccess.DIRECT)

    fun engine(
        io: DestinationFiles = DestinationFiles.Default,
        ledger: ImportLedger = this.ledger,
        removalGraceMs: Long = 0,
    ) = ImportEngine(
        ledger = ledger,
        io = io,
        mediaScanner = MediaScanSink { paths, _ -> scanned += paths },
        onMediaVerified = { kicks++ },
        freeSpace = { Long.MAX_VALUE },
        removalGraceMs = removalGraceMs,
    )

    fun plan(): ScanPlan = runBlocking {
        CardScanner.plan(volume, card.listTargetFiles(), ledger.importedKeys(volume.id), Long.MAX_VALUE, "DCIM/R10")
    }

    fun run(
        move: Boolean,
        monthly: Boolean = false,
        cancel: CancelSignal = CancelSignal(),
        io: DestinationFiles = DestinationFiles.Default,
        progress: MutableList<ImportProgress>? = null,
        ledger: ImportLedger = this.ledger,
        removalGraceMs: Long = 0,
    ): EngineOutcome = runBlocking {
        val request = ImportRequest(plan(), move, "R10", monthly, phone)
        engine(io, ledger, removalGraceMs).run(request, card, cancel) { progress?.add(it) }
    }

    fun items(): List<MediaItemEntity> = runBlocking {
        db.query("SELECT id FROM media_item ORDER BY id", emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.let { ids -> db.mediaDao().getAll(ids).sortedBy { it.id } }
    }

    fun item(relPath: String): MediaItemEntity = items().last { it.srcRelPath == relPath }

    /** 폰 저장 폴더의 파일(저장 폴더 기준 상대 경로 → 파일) */
    fun phoneFiles(): Map<String, File> =
        dest.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(dest).path }

    fun partFiles(): List<File> = phone.walkTopDown().filter { it.isFile && it.name.endsWith(StoragePaths.PART_SUFFIX) }.toList()

    /**
     * 안전 불변식: 카드에서 지워진 원본마다 폰에 같은 SHA-256의 파일이 있다.
     * 그리고 남은 .part가 없다.
     */
    fun assertSafetyInvariant() {
        val phoneHashes = phone.walkTopDown().filter { it.isFile }.map { sha256(it) }.toSet()
        for ((rel, hash) in card.originals) {
            if (!card.file(rel).exists()) {
                assertWithMessage("deleted source $rel must have a verified copy on the phone").that(phoneHashes).contains(hash)
            }
        }
        assertWithMessage("no .part files may remain").that(partFiles()).isEmpty()
    }

    fun close() = db.close()
}

/** EXIF DateTimeOriginal만 들어 있는 최소 JPEG(헤더 + EOI + 임의 바이트) */
fun jpegWithExif(dateTimeOriginal: String, payload: ByteArray): ByteArray {
    val tiff = ByteArrayOutputStream()
    fun le16(v: Int) { tiff.write(v and 0xff); tiff.write((v shr 8) and 0xff) }
    fun le32(v: Int) { le16(v and 0xffff); le16((v shr 16) and 0xffff) }
    // TIFF 헤더(리틀 엔디언), IFD0은 8에서 시작
    tiff.write('I'.code); tiff.write('I'.code); le16(0x2A); le32(8)
    // IFD0: ExifIFDPointer 1개 → Exif IFD는 8 + 2 + 12 + 4 = 26
    le16(1); le16(0x8769); le16(4); le32(1); le32(26); le32(0)
    // Exif IFD: DateTimeOriginal(ASCII 20) → 값은 26 + 2 + 12 + 4 = 44
    le16(1); le16(0x9003); le16(2); le32(20); le32(44); le32(0)
    val date = dateTimeOriginal.toByteArray(Charsets.US_ASCII)
    require(date.size == 19)
    tiff.write(date); tiff.write(0)
    val exif = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff.toByteArray()
    val len = exif.size + 2
    val out = ByteArrayOutputStream()
    out.write(0xFF); out.write(0xD8)
    out.write(0xFF); out.write(0xE1); out.write((len shr 8) and 0xff); out.write(len and 0xff)
    out.write(exif)
    out.write(0xFF); out.write(0xD9)
    out.write(payload)
    return out.toByteArray()
}

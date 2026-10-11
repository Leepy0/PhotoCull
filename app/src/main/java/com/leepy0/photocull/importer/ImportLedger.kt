package com.leepy0.photocull.importer

import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.ImportedKey
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 이전에 폰에 안전하게 저장된 파일 기록(같은 볼륨) */
data class StoredRef(
    val id: Long,
    val srcRelPath: String,
    val srcSize: Long,
    val srcLastModified: Long,
    val unitKey: String,
    val dstPath: String?,
    val sha256: String?,
    val type: MediaType,
    val isPrimary: Boolean,
    val importStatus: ImportStatus,
    val capturedAt: Long,
)

/** 가져오기 기록 저장소. 엔진은 이 인터페이스만 쓴다(테스트는 Room 인메모리 DB). */
interface ImportLedger {
    suspend fun importedKeys(volumeId: String): List<ImportedKey>
    suspend fun storedRecords(volumeId: String): List<StoredRef>
    suspend fun insertSession(session: ImportSessionEntity): Long
    suspend fun updateSession(session: ImportSessionEntity)
    suspend fun insertItem(item: MediaItemEntity): Long
    suspend fun updateItem(item: MediaItemEntity)
    suspend fun getItem(id: Long): MediaItemEntity?

    /** 강제 종료로 PENDING/COPIED에 멈춘 행 */
    suspend fun unfinishedItems(): List<MediaItemEntity>
}

class RoomImportLedger(private val db: AppDatabase) : ImportLedger {
    private val mediaDao get() = db.mediaDao()
    private val sessionDao get() = db.sessionDao()

    override suspend fun importedKeys(volumeId: String): List<ImportedKey> = mediaDao.importedKeys(volumeId)

    override suspend fun storedRecords(volumeId: String): List<StoredRef> = withContext(Dispatchers.IO) {
        db.query(
            """SELECT id, srcRelPath, srcSize, srcLastModified, unitKey, dstPath, sha256, type, isPrimary, importStatus, capturedAt
               FROM media_item WHERE volumeId = ? AND importStatus IN ($STORED_STATUSES) ORDER BY id DESC""",
            arrayOf(volumeId),
        ).use { c ->
            val out = ArrayList<StoredRef>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val type = runCatching { MediaType.valueOf(c.getString(7)) }.getOrNull() ?: continue
                val status = runCatching { ImportStatus.valueOf(c.getString(9)) }.getOrNull() ?: continue
                out += StoredRef(
                    id = c.getLong(0),
                    srcRelPath = c.getString(1),
                    srcSize = c.getLong(2),
                    srcLastModified = c.getLong(3),
                    unitKey = c.getString(4),
                    dstPath = if (c.isNull(5)) null else c.getString(5),
                    sha256 = if (c.isNull(6)) null else c.getString(6),
                    type = type,
                    isPrimary = c.getInt(8) != 0,
                    importStatus = status,
                    capturedAt = c.getLong(10),
                )
            }
            out
        }
    }

    override suspend fun insertSession(session: ImportSessionEntity): Long = sessionDao.insert(session)
    override suspend fun updateSession(session: ImportSessionEntity) = sessionDao.update(session)
    override suspend fun insertItem(item: MediaItemEntity): Long = mediaDao.insert(item)
    override suspend fun updateItem(item: MediaItemEntity) = mediaDao.update(item)
    override suspend fun getItem(id: Long): MediaItemEntity? = mediaDao.get(id)

    override suspend fun unfinishedItems(): List<MediaItemEntity> {
        val ids = withContext(Dispatchers.IO) {
            db.query("SELECT id FROM media_item WHERE importStatus IN ('PENDING','COPIED')", emptyArray()).use { c ->
                val out = ArrayList<Long>()
                while (c.moveToNext()) out += c.getLong(0)
                out
            }
        }
        return ids.chunked(500).flatMap { mediaDao.getAll(it) }
    }

    private companion object {
        /** Daos.kt의 STORED와 같은 집합(폰에 안전하게 저장된 상태) */
        const val STORED_STATUSES = "'VERIFIED','SOURCE_DELETED','DUPLICATE','SOURCE_DELETE_FAILED'"
    }
}

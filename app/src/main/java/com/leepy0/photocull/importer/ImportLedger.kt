package com.leepy0.photocull.importer

import android.database.Cursor
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.ImportedKey
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

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
    val reviewState: ReviewState,
) {
    /** 검토에 나오는(휴지통·영구 삭제가 아닌) 행 */
    val isLive: Boolean get() = reviewState == ReviewState.PENDING || reviewState == ReviewState.KEPT
}

/**
 * 가져오기 기록 저장소. 엔진은 이 인터페이스만 쓴다(테스트는 Room 인메모리 DB).
 *
 * 같은 media_item 행을 분석(선명도·dHash·그룹)과 검토(남기기·휴지통)가 동시에 쓰므로,
 * 가져오기 쪽은 행 전체를 덮어쓰지 않고 가져오기 열만 바꾼다([updateImportFields], [setStatusIf]).
 */
interface ImportLedger {
    suspend fun importedKeys(volumeId: String): List<ImportedKey>
    suspend fun storedRecords(volumeId: String): List<StoredRef>
    suspend fun insertSession(session: ImportSessionEntity): Long
    suspend fun updateSession(session: ImportSessionEntity)
    suspend fun insertItem(item: MediaItemEntity): Long

    /**
     * [item]의 가져오기 열만 기록: importStatus, importError, sha256, dstPath, fileName, unitKey, isPrimary, updatedAt.
     * 검토·휴지통·분석 열은 건드리지 않는다.
     */
    suspend fun updateImportFields(item: MediaItemEntity)

    /** 상태가 [from] 중 하나일 때만 [to]로 바꾼다(읽고 다시 쓰지 않는 한 문장). 바뀌었으면 true */
    suspend fun setStatusIf(id: Long, from: Set<ImportStatus>, to: ImportStatus, now: Long): Boolean

    /** 이 폰 경로를 가리키는 모든 행(상태 무관) */
    suspend fun itemsAt(dstPath: String): List<MediaItemEntity>

    /** 이 폰 파일을 검토에 내놓는 주인 행: 저장된 상태 + 검토 대기/남김 + 단위에서 떼어낸 행이 아님 */
    suspend fun liveOwner(dstPath: String): StoredRef?

    /** 단위에 검토에 나오는 대표 파일이 있는지 */
    suspend fun unitHasLivePrimary(unitKey: String): Boolean

    /** 강제 종료로 PENDING/COPIED에 멈춘 행 */
    suspend fun unfinishedItems(): List<MediaItemEntity>
}

class RoomImportLedger(private val db: AppDatabase) : ImportLedger {
    private val mediaDao get() = db.mediaDao()
    private val sessionDao get() = db.sessionDao()

    override suspend fun importedKeys(volumeId: String): List<ImportedKey> = mediaDao.importedKeys(volumeId)

    override suspend fun storedRecords(volumeId: String): List<StoredRef> = withContext(Dispatchers.IO) {
        db.query(
            "SELECT $REF_COLUMNS FROM media_item WHERE volumeId = ? AND importStatus IN ($STORED_STATUSES) ORDER BY id DESC",
            arrayOf(volumeId),
        ).use(::readRefs)
    }

    override suspend fun insertSession(session: ImportSessionEntity): Long = sessionDao.insert(session)
    override suspend fun updateSession(session: ImportSessionEntity) = sessionDao.update(session)
    override suspend fun insertItem(item: MediaItemEntity): Long = mediaDao.insert(item)

    override suspend fun updateImportFields(item: MediaItemEntity) {
        execUpdate(
            """UPDATE media_item SET importStatus = ?, importError = ?, sha256 = ?, dstPath = ?, fileName = ?,
               unitKey = ?, isPrimary = ?, updatedAt = ? WHERE id = ?""",
            arrayOf(
                item.importStatus.name, item.importError, item.sha256, item.dstPath, item.fileName,
                item.unitKey, if (item.isPrimary) 1L else 0L, item.updatedAt, item.id,
            ),
        )
    }

    override suspend fun setStatusIf(id: Long, from: Set<ImportStatus>, to: ImportStatus, now: Long): Boolean {
        if (from.isEmpty()) return false
        val marks = from.joinToString(",") { "?" }
        val args: Array<Any?> = (listOf<Any?>(to.name, now, id) + from.map { it.name }).toTypedArray()
        return execUpdate("UPDATE media_item SET importStatus = ?, updatedAt = ? WHERE id = ? AND importStatus IN ($marks)", args) > 0
    }

    override suspend fun itemsAt(dstPath: String): List<MediaItemEntity> {
        val ids = withContext(Dispatchers.IO) {
            db.query("SELECT id FROM media_item WHERE dstPath = ? ORDER BY id DESC", arrayOf(dstPath)).use { c ->
                val out = ArrayList<Long>()
                while (c.moveToNext()) out += c.getLong(0)
                out
            }
        }
        if (ids.isEmpty()) return emptyList()
        val order = ids.withIndex().associate { (i, id) -> id to i }
        return ids.chunked(500).flatMap { mediaDao.getAll(it) }.sortedBy { order[it.id] }
    }

    override suspend fun liveOwner(dstPath: String): StoredRef? = withContext(Dispatchers.IO) {
        db.query(
            """SELECT $REF_COLUMNS FROM media_item WHERE dstPath = ? AND importStatus IN ($STORED_STATUSES)
               AND reviewState IN ($LIVE_STATES) ORDER BY id DESC""",
            arrayOf(dstPath),
        ).use(::readRefs)
    }.firstOrNull { !UnitKeys.isDetached(it.unitKey) }

    override suspend fun unitHasLivePrimary(unitKey: String): Boolean = withContext(Dispatchers.IO) {
        db.query(
            """SELECT COUNT(*) FROM media_item WHERE unitKey = ? AND isPrimary = 1
               AND importStatus IN ($STORED_STATUSES) AND reviewState IN ($LIVE_STATES)""",
            arrayOf(unitKey),
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

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

    private fun readRefs(c: Cursor): List<StoredRef> {
        val out = ArrayList<StoredRef>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val type = runCatching { MediaType.valueOf(c.getString(7)) }.getOrNull() ?: continue
            val status = runCatching { ImportStatus.valueOf(c.getString(9)) }.getOrNull() ?: continue
            val review = runCatching { ReviewState.valueOf(c.getString(11)) }.getOrNull() ?: continue
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
                reviewState = review,
            )
        }
        return out
    }

    /** 쓰기 한 문장. Room 트랜잭션 안에서 실행해 화면의 Flow(무효화 추적)도 갱신되게 한다. 바뀐 행 수 */
    private suspend fun execUpdate(sql: String, args: Array<Any?>): Int = withContext(Dispatchers.IO) {
        db.runInTransaction(
            Callable {
                db.compileStatement(sql).use { st ->
                    args.forEachIndexed { i, v ->
                        val index = i + 1
                        when (v) {
                            null -> st.bindNull(index)
                            is String -> st.bindString(index, v)
                            is Long -> st.bindLong(index, v)
                            is Int -> st.bindLong(index, v.toLong())
                            else -> st.bindString(index, v.toString())
                        }
                    }
                    st.executeUpdateDelete()
                }
            },
        )
    }

    private companion object {
        const val REF_COLUMNS =
            "id, srcRelPath, srcSize, srcLastModified, unitKey, dstPath, sha256, type, isPrimary, importStatus, capturedAt, reviewState"

        /** Daos.kt의 STORED와 같은 집합(폰에 안전하게 저장된 상태) */
        const val STORED_STATUSES = "'VERIFIED','SOURCE_DELETED','DUPLICATE','SOURCE_DELETE_FAILED'"

        /** 검토에 나오는 상태 */
        const val LIVE_STATES = "'PENDING','KEPT'"
    }
}

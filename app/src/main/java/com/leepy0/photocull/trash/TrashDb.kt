package com.leepy0.photocull.trash

import androidx.room.withTransaction
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.ReviewState

/** 휴지통이 행에 남기는 변경. 휴지통 관련 열만 바꾼다. */
internal sealed interface TrashChange {
    val id: Long

    /** 휴지통 상태. [trashPath] = null이면 자리 표시(옮길 파일이 이미 없던 짝) */
    data class Trashed(override val id: Long, val trashPath: String?, val trashedAt: Long) : TrashChange

    /** 영구 삭제됨(또는 파일이 이미 없어 목록에서 뺌) */
    data class Deleted(override val id: Long) : TrashChange

    /** 검토 대상으로 돌아옴. [dstPath]가 null이면 저장 위치는 그대로 */
    data class Restored(
        override val id: Long,
        val state: ReviewState,
        val dstPath: String? = null,
        val fileName: String? = null,
    ) : TrashChange
}

/**
 * 휴지통의 DB 쓰기. 한 번의 호출(= 촬영 단위 하나)을 한 트랜잭션으로 묶고, 행 전체가 아니라
 * reviewState · trashPath · trashedAt · dstPath · fileName · updatedAt만 바꾼다.
 * 그래서 그사이 분석이 쓴 결과나 가져오기가 쓴 importStatus를 오래된 값으로 덮지 않는다.
 */
internal class TrashDb(private val db: AppDatabase) {

    suspend fun apply(changes: List<TrashChange>, now: Long, beforeWrite: () -> Unit = {}) {
        if (changes.isEmpty()) return
        db.withTransaction {
            beforeWrite()
            val sql = db.openHelper.writableDatabase
            for (c in changes) {
                when (c) {
                    is TrashChange.Trashed -> sql.execSQL(
                        "UPDATE media_item SET reviewState = ?, trashPath = ?, trashedAt = ?, updatedAt = ? WHERE id = ?",
                        arrayOf<Any?>(ReviewState.TRASHED.name, c.trashPath, c.trashedAt, now, c.id),
                    )
                    is TrashChange.Deleted -> sql.execSQL(
                        "UPDATE media_item SET reviewState = ?, trashPath = NULL, updatedAt = ? WHERE id = ?",
                        arrayOf<Any?>(ReviewState.DELETED.name, now, c.id),
                    )
                    is TrashChange.Restored -> if (c.dstPath != null) {
                        sql.execSQL(
                            """UPDATE media_item SET reviewState = ?, dstPath = ?, fileName = ?, trashPath = NULL,
                               trashedAt = NULL, updatedAt = ? WHERE id = ?""",
                            arrayOf<Any?>(c.state.name, c.dstPath, c.fileName ?: c.dstPath.substringAfterLast('/'), now, c.id),
                        )
                    } else {
                        sql.execSQL(
                            "UPDATE media_item SET reviewState = ?, trashPath = NULL, trashedAt = NULL, updatedAt = ? WHERE id = ?",
                            arrayOf<Any?>(c.state.name, now, c.id),
                        )
                    }
                }
            }
        }
    }

    /** [path]를 저장 위치로 가진 다른 행이 있는지(영구 삭제된 행과, 남의 파일을 가리키는 중복 행은 빼고) */
    fun otherOwnerOf(path: String, exceptId: Long): Boolean =
        db.query(
            """SELECT 1 FROM media_item WHERE dstPath = ? AND id != ? AND reviewState != 'DELETED'
               AND importStatus != 'DUPLICATE' LIMIT 1""",
            arrayOf<Any?>(path, exceptId),
        ).use { it.moveToFirst() }
}

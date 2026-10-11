package com.leepy0.photocull.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "import_session")
data class ImportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val volumeLabel: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** true = 검증 후 카드 원본 삭제(이동), false = 복사만 */
    val moveMode: Boolean,
    val state: SessionState = SessionState.RUNNING,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val successCount: Int = 0,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
    val sourceDeleteFailedCount: Int = 0,
)

/**
 * 카드에서 가져온 파일 1개 = 1행. JPG+CR3처럼 같은 이름의 짝은 [unitKey]가 같고,
 * 그중 대표 파일(JPG > HIF > CR3, 영상은 자신)이 [isPrimary]다. 검토 화면은 대표 파일만 보여주고,
 * 삭제·복원은 같은 unitKey의 파일을 함께 처리한다.
 */
@Entity(
    tableName = "media_item",
    indices = [
        Index("sessionId"),
        Index("unitKey"),
        Index("groupId"),
        Index("reviewState"),
        Index(value = ["volumeId", "srcRelPath"]),
        Index("dstPath"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val volumeId: String,
    /** 카드 루트 기준 상대 경로. 예: DCIM/100CANON/IMG_0001.JPG */
    val srcRelPath: String,
    /** 카드 원본 접근 경로(파일 경로 또는 SAF 문서 URI) */
    val srcLocation: String,
    val srcSize: Long,
    val srcLastModified: Long,
    /** 같은 촬영 단위 키: "<sessionId>:<카드 상대 경로에서 확장자 뺀 것>" */
    val unitKey: String,
    val isPrimary: Boolean,
    val type: MediaType,
    val mimeType: String,
    /** 폰에 저장된 절대 경로. 휴지통에 있는 동안에도 원래 위치를 기억한다. */
    val dstPath: String? = null,
    val fileName: String,
    val sha256: String? = null,
    val importStatus: ImportStatus = ImportStatus.PENDING,
    val importError: String? = null,
    /** 촬영 시각(ms). EXIF DateTimeOriginal+SubSec, 없으면 파일 수정 시각 */
    val capturedAt: Long,
    val width: Int? = null,
    val height: Int? = null,
    // EXIF 요약(상세 화면 표시용)
    val iso: Int? = null,
    val exposure: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    // 분석 결과. sharpness == null && !analysisFailed 이면 아직 분석 전
    val sharpness: Double? = null,
    /** 가장 선명한 타일의 중심(0..1, 이미지 기준). 100% 확대 시 이 위치로 이동 */
    val focusX: Float? = null,
    val focusY: Float? = null,
    val dhash: Long? = null,
    val analysisFailed: Boolean = false,
    val durationMs: Long? = null,
    val groupId: Long? = null,
    val reviewState: ReviewState = ReviewState.PENDING,
    val trashedAt: Long? = null,
    /** 휴지통 안의 실제 경로 */
    val trashPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "media_group", indices = [Index("bestItemId")])
data class MediaGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: GroupKind,
    val bestItemId: Long,
    /** 사용자가 베스트를 직접 바꿨으면 true. 다시 묶을 때도 유지한다. */
    val bestPinned: Boolean = false,
    val count: Int,
    val startAt: Long,
    val endAt: Long,
)

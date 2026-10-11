package com.leepy0.photocull.data.db

/** PHOTO = JPG/JPEG/HIF(분석 대상), RAW = CR3(JPG와 짝으로만 다룸), VIDEO = MP4/MOV */
enum class MediaType { PHOTO, RAW, VIDEO }

/**
 * 파일 단위 가져오기 상태. 순서대로 진행되며 어느 단계에서 끊겨도 카드 원본은 남아 있다.
 * - COPIED: 폰에 .part로 복사 완료(검증 전)
 * - VERIFIED: 해시 검증 끝, 원래 이름으로 저장됨
 * - SOURCE_DELETED: 카드 원본까지 삭제(이동 완료)
 * - DUPLICATE: 같은 내용이 이미 폰에 있어 복사하지 않음(이동 모드면 카드 원본은 삭제)
 * - SOURCE_DELETE_FAILED: 폰에는 안전하게 저장됐지만 카드에서 지우지 못함(쓰기 금지 등)
 * - FAILED: 복사·검증 실패. 카드 원본은 그대로 둔다.
 */
enum class ImportStatus { PENDING, COPIED, VERIFIED, SOURCE_DELETED, DUPLICATE, SOURCE_DELETE_FAILED, FAILED }

/** PENDING = 아직 판단 전, KEPT = 사용자가 남기기로 함, TRASHED = 휴지통, DELETED = 영구 삭제됨 */
enum class ReviewState { PENDING, KEPT, TRASHED, DELETED }

/** BURST = 1초 이내 연속 촬영, SIMILAR = 10초 이내 + 비슷한 구도(dHash) */
enum class GroupKind { BURST, SIMILAR }

enum class SessionState { RUNNING, COMPLETED, CANCELLED, INTERRUPTED }

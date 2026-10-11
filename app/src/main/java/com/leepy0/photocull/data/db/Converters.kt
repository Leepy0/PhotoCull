package com.leepy0.photocull.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun mediaTypeToString(v: MediaType): String = v.name
    @TypeConverter fun stringToMediaType(v: String): MediaType = MediaType.valueOf(v)
    @TypeConverter fun importStatusToString(v: ImportStatus): String = v.name
    @TypeConverter fun stringToImportStatus(v: String): ImportStatus = ImportStatus.valueOf(v)
    @TypeConverter fun reviewStateToString(v: ReviewState): String = v.name
    @TypeConverter fun stringToReviewState(v: String): ReviewState = ReviewState.valueOf(v)
    @TypeConverter fun groupKindToString(v: GroupKind): String = v.name
    @TypeConverter fun stringToGroupKind(v: String): GroupKind = GroupKind.valueOf(v)
    @TypeConverter fun sessionStateToString(v: SessionState): String = v.name
    @TypeConverter fun stringToSessionState(v: String): SessionState = SessionState.valueOf(v)
}

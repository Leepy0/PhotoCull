package com.leepy0.photocull.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ImportSessionEntity::class, MediaItemEntity::class, MediaGroupEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun mediaDao(): MediaDao
    abstract fun groupDao(): GroupDao
    abstract fun groupingDao(): GroupingDao

    /**
     * 카드 원본 삭제는 "검증됨" 기록이 커밋된 뒤에 일어난다. 정전에도 그 커밋이 남도록 WAL에서도 매 커밋을 디스크에 동기화한다.
     * (기본 NORMAL은 정전 시 마지막 커밋들이 되돌아갈 수 있다. 쓰기량이 적은 앱이라 비용은 무시할 만하다.)
     */
    private object DurableCommits : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA synchronous = FULL").close()
        }
    }

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "photocull.db")
                .addCallback(DurableCommits)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}

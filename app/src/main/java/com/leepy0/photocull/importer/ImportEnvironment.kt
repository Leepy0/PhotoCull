package com.leepy0.photocull.importer

import android.content.Context
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.leepy0.photocull.storage.CardInspector
import com.leepy0.photocull.storage.CardMonitor
import com.leepy0.photocull.storage.CardMonitorImpl
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.storage.CardVolume
import com.leepy0.photocull.storage.StoragePaths
import java.io.File

/** 가져오기 컨트롤러가 쓰는 플랫폼 의존 부분. 테스트에서는 임시 폴더·가짜 카드로 바꿔 끼운다. */
interface ImportEnvironment {
    /** 기본 저장소 루트(/storage/emulated/0) */
    val storageRoot: File
    val destinationFiles: DestinationFiles
    val mediaScanner: MediaScanSink

    /** 읽기 오류 뒤 카드 분리 여부를 기다리는 시간 */
    val removalGraceMs: Long get() = 2_000

    /** 폰 여유 공간(StatFs.availableBytes) */
    fun freeBytes(): Long

    /** 지금 카드 상태를 다시 판단해 돌려준다 */
    suspend fun currentCard(): CardState

    fun openSource(volume: CardVolume): CardSource

    /** 포그라운드 서비스 시작(화면에서 [가져오기 시작]) */
    fun startService(moveMode: Boolean)
}

class AndroidImportEnvironment(
    context: Context,
    private val cardMonitor: CardMonitor,
) : ImportEnvironment {
    private val app = context.applicationContext

    override val storageRoot: File get() = StoragePaths.primaryRoot
    override val destinationFiles: DestinationFiles = DestinationFiles.Default
    override val mediaScanner: MediaScanSink = AndroidMediaScanSink(app)

    override fun freeBytes(): Long = runCatching { StatFs(storageRoot.path).availableBytes }.getOrDefault(0L)

    override suspend fun currentCard(): CardState =
        (cardMonitor as? CardMonitorImpl)?.evaluateNow() ?: cardMonitor.state.value

    override fun openSource(volume: CardVolume): CardSource = CardInspector.openSource(app, volume)

    override fun startService(moveMode: Boolean) {
        ContextCompat.startForegroundService(app, ImportService.startIntent(app, moveMode))
    }
}

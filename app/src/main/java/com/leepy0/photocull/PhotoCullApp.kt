package com.leepy0.photocull

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.leepy0.photocull.diagnostics.CrashReporter
import com.leepy0.photocull.notify.NotificationChannels
import kotlinx.coroutines.launch

class PhotoCullApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = DefaultAppContainer(this)
        NotificationChannels.createAll(this)
        container.appScope.launch {
            // 강제 종료로 RUNNING에 남은 세션 정리, 보관 기간 지난 휴지통 비우기, 남은 분석 이어서
            runCatching { container.db.sessionDao().markRunningInterrupted(System.currentTimeMillis()) }
            // 처음 만들 때 지난 실행에서 남은 *.photocull.part와 멈춘 기록을 정리한다
            container.importController
            runCatching { container.trash.purgeExpired() }
            container.analysis.kick()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .crossfade(true)
            .build()

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as PhotoCullApp).container
    }
}

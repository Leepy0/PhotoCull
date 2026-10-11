package com.leepy0.photocull.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 뷰어가 열기 전에 파일을 확인: 없는 파일·손상된 JPEG는 계속 돌지 않고 바로 안내 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoProbeTest {
    private val dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "probe").apply { mkdirs() }

    @Test fun missingFile() {
        assertThat(probePhotoFile(File(dir, "nope.jpg").path, checkHeader = true)).isEqualTo(PhotoLoadFailure.MISSING)
    }

    @Test fun corruptJpegIsUnreadable() {
        val file = File(dir, "broken.jpg").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        assertThat(probePhotoFile(file.path, checkHeader = true)).isEqualTo(PhotoLoadFailure.UNREADABLE)
        // 머리를 확인하지 않는 형식(코일이 여는 HEIF 등)은 존재만 본다
        assertThat(probePhotoFile(file.path, checkHeader = false)).isNull()
    }

    @Test fun validJpegIsFine() {
        val file = File(dir, "ok.jpg")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertThat(probePhotoFile(file.path, checkHeader = true)).isNull()
    }
}

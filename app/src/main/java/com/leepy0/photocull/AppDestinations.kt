package com.leepy0.photocull

import android.content.Context
import android.content.Intent

/**
 * 알림 등 앱 밖에서 특정 화면을 열 때 쓰는 인텐트. MainActivity가 EXTRA_DESTINATION을 읽어 이동한다.
 */
object AppDestinations {
    const val EXTRA_DESTINATION = "com.leepy0.photocull.DESTINATION"

    const val IMPORT = "import"
    const val REVIEW = "review"
    const val UPDATE = "update"
    const val HOME = "home"

    fun intent(context: Context, destination: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

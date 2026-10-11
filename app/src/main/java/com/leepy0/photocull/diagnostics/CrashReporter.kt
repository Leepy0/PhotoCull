package com.leepy0.photocull.diagnostics

import android.content.Context

// TODO(update): 비정상 종료 기록·표시 실제 구현으로 교체
object CrashReporter {
    fun install(context: Context) {}
    fun lastCrash(context: Context): String? = null
    fun clear(context: Context) {}
}

package com.example.appfreezer

import android.content.Context

/** إعدادات التطبيق البسيطة. الافتراضي disable-user لأنه الأكثر ثباتاً على الأجهزة المعدّلة. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: FreezeMode
        get() = runCatching { FreezeMode.valueOf(p.getString("mode", null) ?: "") }
            .getOrDefault(FreezeMode.DISABLE)
        set(v) { p.edit().putString("mode", v.name).apply() }
}

package com.andyl.ignite.data

import android.provider.Settings
import com.andyl.ignite.data.db.AndroidContextHolder

actual fun isReduceMotionEnabled(): Boolean = runCatching {
    val resolver = AndroidContextHolder.context.contentResolver
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
}.getOrDefault(false)

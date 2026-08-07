package io.legado.app.fanqie

import android.content.Context
import androidx.core.content.edit
import splitties.init.appCtx

object FanqieConfig {

    private const val PREFS_NAME = "FanqieConfig"
    private val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var groupId: Long
        get() = sp.getLong("groupId", 0L)
        set(value) = sp.edit { putLong("groupId", value) }

    var autoSyncProgress: Boolean
        get() = sp.getBoolean("autoSyncProgress", true)
        set(value) = sp.edit { putBoolean("autoSyncProgress", value) }

    var lastSyncTime: Long
        get() = sp.getLong("lastSyncTime", 0L)
        set(value) = sp.edit { putLong("lastSyncTime", value) }
}

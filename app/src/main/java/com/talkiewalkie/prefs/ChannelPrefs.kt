package com.talkiewalkie.prefs

import android.content.Context
import com.talkiewalkie.model.Role

object ChannelPrefs {
    private const val PREFS_NAME = "channel_prefs"
    private const val KEY_NAME   = "last_channel_name"
    private const val KEY_ROLE   = "last_channel_role"

    fun save(context: Context, name: String, role: Role) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_ROLE, role.name)
            .apply()
    }

    fun load(context: Context): Pair<String, Role>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name  = prefs.getString(KEY_NAME, null) ?: return null
        val role  = runCatching { Role.valueOf(prefs.getString(KEY_ROLE, null) ?: "") }.getOrNull()
            ?: return null
        if (role == Role.NONE) return null
        return name to role
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

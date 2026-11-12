package com.jonisuominen.readout.data

import android.content.SharedPreferences
import com.jonisuominen.readout.core.KeyValueStore

/**
 * The cache the app, widgets, and background work read. They share one
 * process, so one SharedPreferences file is always consistent between them.
 */
class PrefsKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

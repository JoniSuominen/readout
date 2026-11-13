package com.jonisuominen.readout.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.jonisuominen.readout.core.KeyValueStore

/**
 * The cache the app, widgets, and background work read. They share one
 * process, so one SharedPreferences file is always consistent between them.
 */
class PrefsKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        preferences.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }
}

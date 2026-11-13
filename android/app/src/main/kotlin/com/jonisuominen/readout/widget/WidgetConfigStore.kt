package com.jonisuominen.readout.widget

import android.content.Context
import androidx.core.content.edit
import com.jonisuominen.readout.core.WidgetInstanceConfiguration
import com.jonisuominen.readout.core.WidgetPrivacyMode

/**
 * Each placed widget's own choices: its layout and whether private values
 * show. One widget cannot change another's.
 */
class WidgetConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("readout.widgets", Context.MODE_PRIVATE)

    fun configuration(appWidgetId: Int): WidgetInstanceConfiguration = WidgetInstanceConfiguration(
        designID = preferences.getString("design.$appWidgetId", null),
        privacyMode = WidgetPrivacyMode.fromKey(preferences.getString("privacy.$appWidgetId", null))
            ?: WidgetInstanceConfiguration.homeDefault.privacyMode,
    )

    fun save(appWidgetId: Int, configuration: WidgetInstanceConfiguration) {
        preferences.edit {
            putString("design.$appWidgetId", configuration.designID)
            putString("privacy.$appWidgetId", configuration.privacyMode.key)
        }
    }

    fun delete(appWidgetIds: IntArray) {
        preferences.edit {
            for (id in appWidgetIds) remove("design.$id").remove("privacy.$id")
        }
    }
}

package com.jonisuominen.readout.widget

import android.content.Context
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
        preferences.edit()
            .putString("design.$appWidgetId", configuration.designID)
            .putString("privacy.$appWidgetId", configuration.privacyMode.key)
            .apply()
    }

    fun delete(appWidgetIds: IntArray) {
        val editor = preferences.edit()
        for (id in appWidgetIds) editor.remove("design.$id").remove("privacy.$id")
        editor.apply()
    }
}

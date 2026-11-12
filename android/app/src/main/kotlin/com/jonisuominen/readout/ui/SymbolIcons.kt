package com.jonisuominen.readout.ui

import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.Symbols

/** The drawable for each icon key a metric can carry. */
object SymbolIcons {
    fun resource(symbol: String): Int = when (symbol) {
        Symbols.SUN -> R.drawable.ic_sun
        Symbols.MOON -> R.drawable.ic_moon
        Symbols.CLOUD_SUN -> R.drawable.ic_cloud_sun
        Symbols.CLOUD_MOON -> R.drawable.ic_cloud_moon
        Symbols.CLOUD -> R.drawable.ic_cloud
        Symbols.FOG -> R.drawable.ic_fog
        Symbols.DRIZZLE -> R.drawable.ic_drizzle
        Symbols.SLEET -> R.drawable.ic_sleet
        Symbols.RAIN -> R.drawable.ic_rain
        Symbols.SNOW -> R.drawable.ic_snow
        Symbols.HEAVY_RAIN -> R.drawable.ic_heavy_rain
        Symbols.THUNDER -> R.drawable.ic_thunder
        Symbols.THERMOMETER -> R.drawable.ic_thermometer
        Symbols.DROP -> R.drawable.ic_drop
        Symbols.CALENDAR -> R.drawable.ic_calendar
        Symbols.WIND -> R.drawable.ic_wind
        Symbols.HUMIDITY -> R.drawable.ic_humidity
        Symbols.SUNRISE -> R.drawable.ic_sunrise
        Symbols.SUNSET -> R.drawable.ic_sunset
        Symbols.AIR -> R.drawable.ic_air
        Symbols.SPARKLES -> R.drawable.ic_sparkles
        Symbols.SLEEP -> R.drawable.ic_sleep
        Symbols.WALK -> R.drawable.ic_walk
        Symbols.HEART -> R.drawable.ic_heart
        Symbols.FLAME -> R.drawable.ic_flame
        Symbols.RUN -> R.drawable.ic_run
        Symbols.DISTANCE -> R.drawable.ic_distance
        Symbols.DUMBBELL -> R.drawable.ic_dumbbell
        Symbols.LIST -> R.drawable.ic_list
        Symbols.LOCK -> R.drawable.ic_lock
        Symbols.REFRESH -> R.drawable.ic_refresh
        Symbols.REFRESH_ERROR -> R.drawable.ic_refresh_error
        Symbols.LINK -> R.drawable.ic_link
        Symbols.RING -> R.drawable.ic_ring
        else -> R.drawable.ic_cloud
    }
}

package com.ttmrase.crownfall.ui.map

import android.content.Context

/** マップの表示設定（昼夜の色味）。端末内の設定で、セーブには入れない。 */
internal object MapPrefs {
    private const val PREFS = "crownfall_ui_prefs"
    private const val KEY_DAY_NIGHT = "map_daynight"

    fun dayNight(context: Context): Int =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).getInt(KEY_DAY_NIGHT, DayNight.AUTO.id)

    fun setDayNight(
        context: Context,
        id: Int,
    ) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DAY_NIGHT, id).apply()
    }
}

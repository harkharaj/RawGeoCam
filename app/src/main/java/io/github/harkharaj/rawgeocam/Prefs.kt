package io.github.harkharaj.rawgeocam

import android.content.Context

/**
 * Settings that survive closing the app. Manual values are stored per lens (key = camera id, or
 * "logicalId/physicalId" for a hidden physical lens such as the Pixel ultrawide), because each lens has
 * its own exposure, ISO and focus ranges. The focus value is stored exactly (diopters), not as a slider
 * position, so the lens goes back to the same commanded focus distance after a restart.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("rawgeocam", Context.MODE_PRIVATE)

    var manualMode: Boolean
        get() = sp.getBoolean("manual_mode", false)
        set(v) = sp.edit().putBoolean("manual_mode", v).apply()

    /** Auto mode only: focus locked at infinity (true) or continuous AF (false). */
    var focusAtInfinity: Boolean
        get() = sp.getBoolean("focus_at_infinity", true)
        set(v) = sp.edit().putBoolean("focus_at_infinity", v).apply()

    var lensKey: String?
        get() = sp.getString("lens_key", null)
        set(v) = sp.edit().putString("lens_key", v).apply()

    fun exposureNs(lens: String): Long? = sp.getLong("$lens/exposure_ns", -1L).takeIf { it > 0L }

    fun iso(lens: String): Int? = sp.getInt("$lens/iso", -1).takeIf { it > 0 }

    fun focusDiopters(lens: String): Float? = sp.getFloat("$lens/focus_diopters", -1f).takeIf { it >= 0f }

    fun saveManual(lens: String, exposureNs: Long, iso: Int, focusDiopters: Float) {
        sp.edit()
            .putLong("$lens/exposure_ns", exposureNs)
            .putInt("$lens/iso", iso)
            .putFloat("$lens/focus_diopters", focusDiopters)
            .apply()
    }
}

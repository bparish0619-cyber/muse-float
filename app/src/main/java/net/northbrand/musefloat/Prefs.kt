package net.northbrand.musefloat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * SharedPreferences access for Muse Float, in one place so the activity, the
 * service, and the boot receiver all agree on keys and defaults.
 *
 * Stored values:
 *  - target:       what a bubble tap opens (the Muse app, or muse.ai web)
 *  - bubble_x/y:   last resting position of the bubble, in pixels
 *  - running:      whether the floating service believes it is alive, used by
 *                  the main screen to show an honest status line
 *  - enabled:      whether the user wants the bubble on at all; this (not
 *                  "running") is what the boot receiver checks, so a snooze
 *                  or a system kill does not switch the bubble off for good
 *  - boot:         whether the bubble may restart itself after a reboot
 *  - size_dp:      bubble diameter chosen on the main screen
 *  - opacity_pct:  idle-fade opacity chosen on the main screen (percent)
 *  - snooze_until: epoch millis until which the bubble stays hidden
 */
object Prefs {

    // CHANGE-ME: where a tap takes you when the "web" target is chosen.
    const val WEB_URL = "https://muse.ai"

    const val TARGET_APP = "app"
    const val TARGET_WEB = "web"

    // CHANGE-ME: slider ranges and defaults for the main-screen controls.
    // The service needs no matching change: it sizes everything (drag
    // clamp, edge snap, dismiss target) from the saved value.
    // Never take the opacity floor to 0: at full transparency the bubble
    // would be impossible to find again.
    const val SIZE_MIN_DP = 44
    const val SIZE_MAX_DP = 120
    const val SIZE_DEFAULT_DP = 56
    const val OPACITY_MIN_PCT = 10
    const val OPACITY_MAX_PCT = 90
    const val OPACITY_DEFAULT_PCT = 55

    private const val FILE = "muse_float_prefs"
    private const val KEY_TARGET = "target"
    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"
    private const val KEY_RUNNING = "running"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BOOT = "boot"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_OPACITY = "opacity_pct"
    private const val KEY_SNOOZE = "snooze_until"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- tap target ----------------------------------------------------

    /** The saved tap target, or "" when the user has never chosen one. */
    fun target(context: Context): String =
        prefs(context).getString(KEY_TARGET, "") ?: ""

    fun setTarget(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TARGET, value).apply()
    }

    // ---- bubble position -------------------------------------------------

    /** Saved bubble position, or null the very first time. */
    fun bubbleX(context: Context): Int? =
        if (prefs(context).contains(KEY_X)) prefs(context).getInt(KEY_X, 0) else null

    fun bubbleY(context: Context): Int? =
        if (prefs(context).contains(KEY_Y)) prefs(context).getInt(KEY_Y, 0) else null

    fun setBubblePos(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    // ---- service state ---------------------------------------------------

    fun running(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    fun setRunning(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, value).apply()
    }

    /** True after the user starts the bubble, false after an explicit stop. */
    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Boot restart toggle from the main screen. Default: on. */
    fun bootEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT, true)

    fun setBootEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT, value).apply()
    }

    // ---- appearance --------------------------------------------------------

    fun bubbleSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_SIZE, SIZE_DEFAULT_DP)
            .coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)

    fun setBubbleSizeDp(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SIZE, value).apply()
    }

    /** Idle-fade opacity as a 0.0 to 1.0 alpha for the bubble view. */
    fun idleAlpha(context: Context): Float =
        prefs(context).getInt(KEY_OPACITY, OPACITY_DEFAULT_PCT)
            .coerceIn(OPACITY_MIN_PCT, OPACITY_MAX_PCT) / 100f

    fun idleOpacityPct(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY, OPACITY_DEFAULT_PCT)
            .coerceIn(OPACITY_MIN_PCT, OPACITY_MAX_PCT)

    fun setIdleOpacityPct(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_OPACITY, value).apply()
    }

    // ---- snooze ------------------------------------------------------------

    /** Epoch millis the bubble stays hidden until; 0 means not snoozed. */
    fun snoozeUntil(context: Context): Long =
        prefs(context).getLong(KEY_SNOOZE, 0L)

    fun setSnoozeUntil(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_SNOOZE, value).apply()
    }

    // ---- Muse app lookup ---------------------------------------------------

    /**
     * Finds an installed launcher app whose label contains "Muse" and
     * returns its package name, or null. The manifest's <queries> block is
     * what makes launcher apps visible to this scan on Android 11+.
     */
    fun findMusePackage(context: Context): String? {
        val pm: PackageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, 0)
        for (info in matches) {
            val label = info.loadLabel(pm)?.toString() ?: continue
            if (label.contains("Muse", ignoreCase = true)) {
                return info.activityInfo.packageName
            }
        }
        return null
    }

    /** The effective target: the saved choice, else app if found, else web. */
    fun effectiveTarget(context: Context): String {
        val saved = target(context)
        if (saved == TARGET_APP || saved == TARGET_WEB) return saved
        return if (findMusePackage(context) != null) TARGET_APP else TARGET_WEB
    }
}

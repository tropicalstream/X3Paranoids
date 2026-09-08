package com.x3paranoids

import android.content.Context
import android.os.Build

/** Persistent settings + records. RayNeo detection follows guide gotcha #24 (never Build.MODEL). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3paranoids", Context.MODE_PRIVATE)

    private val deviceText = listOf(Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT)
        .joinToString(" ").lowercase()
    val isRayNeoX3 = "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText
    val sbs get() = isRayNeoX3

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (v > highScore) p.edit().putInt("hi", v).apply() }
    var bestWave: Int
        get() = p.getInt("bestWave", 0)
        set(v) { if (v > bestWave) p.edit().putInt("bestWave", v).apply() }
    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }

    var music: Boolean
        get() = p.getBoolean("music", true)
        set(v) = p.edit().putBoolean("music", v).apply()
    /** 0..10 */
    var volume: Int
        get() = p.getInt("volume", 7)
        set(v) = p.edit().putInt("volume", v.coerceIn(0, 10)).apply()
    var voice: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()
    var headLook: Boolean
        get() = p.getBoolean("head", true)
        set(v) = p.edit().putBoolean("head", v).apply()
    /** The nav plate in the sight's right gutter. On by default — the maze is unreadable without it. */
    var minimap: Boolean
        get() = p.getBoolean("minimap", true)
        set(v) = p.edit().putBoolean("minimap", v).apply()
    /** The pad's raw dx sign vs. the physical gesture is a matter of taste; this flips which way a swipe turns. */
    var turnReversed: Boolean
        get() = p.getBoolean("turnRev", false)
        set(v) = p.edit().putBoolean("turnRev", v).apply()
    /** 0 = normal, 1 = hard (Recognizers take two hits from wave 1, faster patrols). */
    var difficulty: Int
        get() = p.getInt("difficulty", 0)
        set(v) = p.edit().putInt("difficulty", v.coerceIn(0, 1)).apply()

    fun resetSettings() {
        p.edit().remove("music").remove("volume").remove("voice").remove("head").remove("minimap").remove("turnRev").remove("difficulty").apply()
    }
}

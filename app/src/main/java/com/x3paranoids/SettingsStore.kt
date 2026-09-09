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
    /** The highest wave ever CLEARED on this device — written in Game.waveCleared, so 1 means wave one fell. */
    var bestWave: Int
        get() = p.getInt("bestWave", 0)
        set(v) { if (v > bestWave) p.edit().putInt("bestWave", v).apply() }
    /**
     * The highest wave ever REACHED — written on wave entry. It exists because [bestWave] alone is
     * ambiguous in exactly the way that matters: "best wave 1" can mean "wave one was cleared and
     * wave two was entered" or "wave one was never beaten", and the two are opposite readings of
     * the same machine. Both numbers are kept so the record says which.
     */
    var bestWaveReached: Int
        get() = p.getInt("bestWaveReached", 0)
        set(v) { if (v > bestWaveReached) p.edit().putInt("bestWaveReached", v).apply() }
    /**
     * Games actually PLAYED — a debug launch (Game.debugStart) no longer counts, because a counter
     * that includes `am start --ei wave 6` verification runs is not evidence about players.
     */
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
    /**
     * 0 = EASY, 1 = NORMAL, 2 = HARD. The row used to run NORMAL / HARD only — the one dial a
     * player could reach went upward from a setting nobody survived. EASY exists so that the
     * machine can keep its teeth without the first ninety seconds being an execution.
     *
     * THE DEFAULT IS EARNED, NOT ASSUMED. A device that has never cleared wave two has no evidence
     * that anybody here can play this yet, so it opens on EASY; once wave two has fallen the
     * default is NORMAL. Either way it is one swipe from the other, and the choice persists the
     * moment it is touched. The KEY IS NEW ("diff3") because the old one stored a two-value scale,
     * and reading an old `1` as the new HARD would silently promote anybody who ever tried hard.
     */
    var difficulty: Int
        get() = p.getInt("diff3", if (bestWave >= 2) 1 else 0)
        set(v) = p.edit().putInt("diff3", v.coerceIn(0, 2)).apply()

    fun resetSettings() {
        p.edit().remove("music").remove("volume").remove("voice").remove("head").remove("minimap").remove("turnRev").remove("diff3").apply()
    }
}

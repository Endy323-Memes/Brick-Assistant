package com.brick.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.brick.assistant.voice.RobotFx
import org.json.JSONObject

data class AppSettings(
    // --- Main voice ---
    /** "classic" = natural male assistant voice (Google speech, calm and even), "david" = Microsoft David. */
    val mainVoice: String = "classic",
    /** Google voice for the classic male voice. Blank = automatic male voice. */
    val classicVoice: String = "",
    val classicRate: Float = 1.05f,
    /** Intonation kept, 0..1 (lower = flatter, more like classic assistants). */
    val classicExpression: Float = 0.5f,
    val classicPitch: Float = 0.94f,

    // --- Unit voice style ---
    /** "natural" = natural female voice (Google speech, the Google Maps voice family), "robot" = robot unit. */
    val unitStyle: String = "natural",
    /** Google voice for the natural unit voice. Blank = automatic (Google Maps-style US female). */
    val naturalVoice: String = "",
    val naturalRate: Float = 1.0f,

    // --- David ---
    /** Address of the David voice server running on a Windows PC, e.g. http://192.168.1.20:5150 */
    val davidServerUrl: String = "",
    val davidToken: String = "",
    /** SAPI speaking rate, -10..10 */
    val davidRate: Int = 0,
    /** Speed of David's custom (stitched) responses; 1 = as recorded. */
    val davidStitchSpeed: Float = 1.2f,
    /** Android voice used when the David server can't be reached. Blank = system default. */
    val davidFallbackVoice: String = "",
    val davidFallbackPitch: Float = 0.8f,
    val davidFallbackRate: Float = 0.95f,

    // --- Robot (tutorial-bot voice, tuned from reference measurements) ---
    val robotFxEnabled: Boolean = true,
    /** If true the robot is built from David's voice, otherwise from an Android voice. */
    val robotUseDavidBase: Boolean = false,
    val robotVoice: String = "",
    /** Median pitch of the robot unit, an original female voice (215 Hz). */
    val robotTargetF0: Float = RobotFx.UNIT_F0,
    /** How strongly the reference tone curve is applied, 0..1. */
    val robotEqStrength: Float = 0.85f,
    /** Intonation scaling around the median pitch (1 = the base voice's own intonation). */
    val robotExpressiveness: Float = 1.4f,
    val robotRate: Float = 1.0f,
    // Optional extras, off by default (the reference voice uses none of them).
    val ringFreq: Float = 55f,
    val ringMix: Float = 0f,
    val combDelayMs: Float = 7f,
    val combFeedback: Float = 0f,
    val bitDepth: Int = 16,
    val speakerFilter: Boolean = false,

    // --- Assistant ---
    /** Assist gesture shows a floating card over the current app instead of opening Brick. */
    val useOverlay: Boolean = true,
    /** Listen for "Hey Brick" in the background (offline). */
    val wakeWordEnabled: Boolean = false,
    /** Listen through connected Bluetooth headphones' microphone (Hey Brick and requests). */
    val headsetMic: Boolean = true,
    /** Pause "Hey Brick" while the phone is in a pocket (light and proximity sensors). */
    val pocketPause: Boolean = true,
    val useFahrenheit: Boolean = true,
    val homeCity: String = "",
    /** Optional AccuWeather API key (developer.accuweather.com). Blank = Open-Meteo. */
    val accuWeatherKey: String = "",
    val musicPackage: String = "",
    val autoSendTexts: Boolean = false,
    /** Plays the bottom animation in Spotify, YouTube or Google Maps colors when Brick uses those apps. */
    val brandGlow: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("mainVoice", mainVoice)
        put("classicVoice", classicVoice)
        put("classicRate", classicRate.toDouble())
        put("classicExpression", classicExpression.toDouble())
        put("classicPitch", classicPitch.toDouble())
        put("unitStyle", unitStyle)
        put("naturalVoice", naturalVoice)
        put("naturalRate", naturalRate.toDouble())
        put("voicePresetVersion", VOICE_PRESET_VERSION)
        put("davidServerUrl", davidServerUrl)
        put("davidToken", davidToken)
        put("davidRate", davidRate)
        put("davidStitchSpeed", davidStitchSpeed.toDouble())
        put("davidFallbackVoice", davidFallbackVoice)
        put("davidFallbackPitch", davidFallbackPitch.toDouble())
        put("davidFallbackRate", davidFallbackRate.toDouble())
        put("robotFxEnabled", robotFxEnabled)
        put("robotUseDavidBase", robotUseDavidBase)
        put("robotVoice", robotVoice)
        put("robotPresetVersion", ROBOT_PRESET_VERSION)
        put("robotTargetF0", robotTargetF0.toDouble())
        put("robotEqStrength", robotEqStrength.toDouble())
        put("robotExpressiveness", robotExpressiveness.toDouble())
        put("robotRate", robotRate.toDouble())
        put("ringFreq", ringFreq.toDouble())
        put("ringMix", ringMix.toDouble())
        put("combDelayMs", combDelayMs.toDouble())
        put("combFeedback", combFeedback.toDouble())
        put("bitDepth", bitDepth)
        put("speakerFilter", speakerFilter)
        put("useOverlay", useOverlay)
        put("wakeWordEnabled", wakeWordEnabled)
        put("headsetMic", headsetMic)
        put("pocketPause", pocketPause)
        put("useFahrenheit", useFahrenheit)
        put("homeCity", homeCity)
        put("accuWeatherKey", accuWeatherKey)
        put("musicPackage", musicPackage)
        put("autoSendTexts", autoSendTexts)
        put("brandGlow", brandGlow)
    }.toString()

    /** Copy with only the robot tuning reset to defaults. */
    fun withDefaultRobot(): AppSettings {
        val d = AppSettings()
        return copy(
            robotFxEnabled = d.robotFxEnabled, robotTargetF0 = d.robotTargetF0,
            robotEqStrength = d.robotEqStrength, robotExpressiveness = d.robotExpressiveness, robotRate = d.robotRate,
            ringFreq = d.ringFreq, ringMix = d.ringMix, combDelayMs = d.combDelayMs,
            combFeedback = d.combFeedback, bitDepth = d.bitDepth, speakerFilter = d.speakerFilter,
        )
    }

    companion object {
        /** Bump when robot defaults change so old tunings are replaced. */
        const val ROBOT_PRESET_VERSION = 3
        /** Bump to move saved settings onto new default voices. */
        const val VOICE_PRESET_VERSION = 1

        fun fromJson(raw: String?): AppSettings {
            if (raw == null) return AppSettings()
            return try {
                val o = JSONObject(raw)
                val d = AppSettings()
                // Settings saved before the natural voices existed switch to them once.
                val newVoices = o.optInt("voicePresetVersion", 0) < VOICE_PRESET_VERSION
                AppSettings(
                    mainVoice = if (newVoices) d.mainVoice else o.optString("mainVoice", d.mainVoice),
                    classicVoice = o.optString("classicVoice", d.classicVoice),
                    classicRate = o.optDouble("classicRate", d.classicRate.toDouble()).toFloat(),
                    classicExpression = o.optDouble("classicExpression", d.classicExpression.toDouble()).toFloat(),
                    classicPitch = o.optDouble("classicPitch", d.classicPitch.toDouble()).toFloat(),
                    unitStyle = if (newVoices) d.unitStyle else o.optString("unitStyle", d.unitStyle),
                    naturalVoice = o.optString("naturalVoice", d.naturalVoice),
                    naturalRate = o.optDouble("naturalRate", d.naturalRate.toDouble()).toFloat(),
                    davidServerUrl = o.optString("davidServerUrl", d.davidServerUrl),
                    davidToken = o.optString("davidToken", d.davidToken),
                    davidRate = o.optInt("davidRate", d.davidRate),
                    davidStitchSpeed = o.optDouble("davidStitchSpeed", d.davidStitchSpeed.toDouble()).toFloat(),
                    davidFallbackVoice = o.optString("davidFallbackVoice", d.davidFallbackVoice),
                    davidFallbackPitch = o.optDouble("davidFallbackPitch", d.davidFallbackPitch.toDouble()).toFloat(),
                    davidFallbackRate = o.optDouble("davidFallbackRate", d.davidFallbackRate.toDouble()).toFloat(),
                    robotFxEnabled = o.optBoolean("robotFxEnabled", d.robotFxEnabled),
                    robotUseDavidBase = o.optBoolean("robotUseDavidBase", d.robotUseDavidBase),
                    robotVoice = o.optString("robotVoice", d.robotVoice),
                    robotTargetF0 = o.optDouble("robotTargetF0", d.robotTargetF0.toDouble()).toFloat(),
                    robotEqStrength = o.optDouble("robotEqStrength", d.robotEqStrength.toDouble()).toFloat(),
                    robotExpressiveness = o.optDouble("robotExpressiveness", d.robotExpressiveness.toDouble()).toFloat(),
                    robotRate = o.optDouble("robotRate", d.robotRate.toDouble()).toFloat(),
                    ringFreq = o.optDouble("ringFreq", d.ringFreq.toDouble()).toFloat(),
                    ringMix = o.optDouble("ringMix", d.ringMix.toDouble()).toFloat(),
                    combDelayMs = o.optDouble("combDelayMs", d.combDelayMs.toDouble()).toFloat(),
                    combFeedback = o.optDouble("combFeedback", d.combFeedback.toDouble()).toFloat(),
                    bitDepth = o.optInt("bitDepth", d.bitDepth),
                    speakerFilter = o.optBoolean("speakerFilter", d.speakerFilter),
                    useOverlay = o.optBoolean("useOverlay", d.useOverlay),
                    wakeWordEnabled = o.optBoolean("wakeWordEnabled", d.wakeWordEnabled),
                    headsetMic = o.optBoolean("headsetMic", d.headsetMic),
                    pocketPause = o.optBoolean("pocketPause", d.pocketPause),
                    useFahrenheit = o.optBoolean("useFahrenheit", d.useFahrenheit),
                    homeCity = o.optString("homeCity", d.homeCity),
                    accuWeatherKey = o.optString("accuWeatherKey", d.accuWeatherKey),
                    musicPackage = o.optString("musicPackage", d.musicPackage),
                    autoSendTexts = o.optBoolean("autoSendTexts", d.autoSendTexts),
                    brandGlow = o.optBoolean("brandGlow", d.brandGlow),
                ).let { if (o.optInt("robotPresetVersion", 1) < ROBOT_PRESET_VERSION) it.withDefaultRobot() else it }
            } catch (e: Exception) {
                AppSettings()
            }
        }
    }
}

class SettingsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppSettings.fromJson(prefs.getString("json", null)))
    val state: StateFlow<AppSettings> = _state
    val current: AppSettings get() = _state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val prev = _state.value
        val next = transform(prev)
        // Slider drags call this many times with the same value: skip the JSON rewrite when nothing changed.
        if (next == prev) return
        _state.value = next
        prefs.edit().putString("json", next.toJson()).apply()
        ControlProvider.notifyChanged(app, prev.wakeWordEnabled != next.wakeWordEnabled)
    }
}

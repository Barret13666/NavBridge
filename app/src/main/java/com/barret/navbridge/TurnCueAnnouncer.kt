package com.barret.navbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Plays the turn cues the dashboard raises: a spoken instruction, a beep, or
 * a vibration pattern, according to what is set in Settings.
 *
 * WHY THE BOARD DECIDES AND THIS ONLY PLAYS
 *
 * The phone has the route (it computed it) and the position (it is the
 * source), so it could work out the cues itself and need no firmware support
 * at all. It deliberately does not. The board is the one holding the
 * thresholds, the off-route state and the nearest-point anchor that decide
 * what is actually drawn on the screen; re-deriving all of that here would be
 * a second implementation free to drift from the first, and the failure mode
 * of that drift is a voice saying "turn left now" while the arrow in front of
 * you says something else. So the board sends TCU1 (see gps_nav.cpp's cue
 * block) and this class does as it is told.
 *
 * AUDIO ROUTING
 *
 * Everything here is tagged USAGE_ASSISTANCE_NAVIGATION_GUIDANCE. That is not
 * cosmetic: it is what makes a Bluetooth helmet headset or a car stereo treat
 * the cue as navigation rather than media, route it correctly, and duck the
 * music instead of stopping it. Focus is requested as TRANSIENT_MAY_DUCK for
 * the same reason -- a turn instruction that pauses your music for four
 * seconds and then resumes it is far more disruptive than one spoken over it.
 */
class TurnCueAnnouncer(private val context: Context) {

    companion object {
        private const val TAG = "TurnCueAnnouncer"

        const val PREFS = "nmea_bridge"
        const val KEY_CUE_MODE = "cue_mode"        // 0 off, 1 beep, 2 speech
        const val KEY_VIBRATION = "cue_vibration"  // boolean

        const val MODE_OFF = 0
        const val MODE_BEEP = 1
        const val MODE_SPEECH = 2

        /**
         * Vibration patterns, chosen so the three you most need are
         * distinguishable through a jacket without looking: one long pulse for
         * left, two short for right, a triple for arrival. Timings are
         * off/on pairs in milliseconds.
         */
        private val PATTERN_LEFT = longArrayOf(0, 400)
        private val PATTERN_RIGHT = longArrayOf(0, 150, 120, 150)
        private val PATTERN_GENERIC = longArrayOf(0, 250)
        private val PATTERN_ARRIVE = longArrayOf(0, 120, 100, 120, 100, 320)
        private val PATTERN_REROUTE = longArrayOf(0, 90, 90, 90, 90, 90)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val navAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null

    // Duplicate suppression. The board sends every cue TWICE on purpose (plain
    // UDP, no resend path for something this time-critical), so the second
    // copy arriving is the normal case, not an error -- it is dropped here by
    // sequence number rather than at the socket, because that is where the
    // sequence number means something.
    private var lastSeq = -1L

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun start() {
        if (tts != null) return
        // TextToSpeech construction must not happen on an arbitrary thread --
        // the callback comes back on the main looper and the engine binds a
        // service. This is called from the service's onCreate, which is
        // already the main thread; posting anyway keeps it true if that ever
        // changes.
        main.post {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    applyTtsLanguage()
                    tts?.setAudioAttributes(navAttributes)
                    ttsReady = true
                } else {
                    Log.w(TAG, "TTS init failed ($status) -- cues fall back to a beep")
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = abandonFocus()
                @Deprecated("required override")
                override fun onError(utteranceId: String?) = abandonFocus()
            })
        }
    }

    fun stop() {
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        lastSeq = -1L
    }

    /**
     * Re-points the engine at whatever language Settings now says. Called on
     * start and whenever the language changes; if the engine has no voice for
     * it, the cue is downgraded to a beep rather than being spoken in the
     * wrong language, which would be worse than silence.
     */
    fun applyTtsLanguage() {
        val engine = tts ?: return
        val locale = LocaleHelper.locale(context)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "no TTS voice for $locale -- spoken cues will beep instead")
            ttsReady = false
        } else {
            ttsReady = true
        }
    }

    /**
     * Handles one TCU1 line from the board:
     *   TCU1|<seq>|<event>|<code>|<exit>|<distM>
     * Returns false if the line was not a cue this class understands, so the
     * caller can log it as unrecognised traffic.
     */
    fun handleCuePacket(line: String): Boolean {
        val parts = line.trim().split("|")
        if (parts.size < 6 || parts[0] != "TCU1") return false

        val seq = parts[1].toLongOrNull() ?: return false
        val event = parts[2]
        val code = parts[3]
        val exit = parts[4].toIntOrNull() ?: 0
        val dist = parts[5].toIntOrNull() ?: 0

        if (seq == lastSeq) return true // the deliberate duplicate -- see lastSeq
        lastSeq = seq

        announce(event, code, exit, dist)
        return true
    }

    /** Exposed so the Settings screen's Test button plays a real cue. */
    fun announce(event: String, code: String, exit: Int, dist: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_CUE_MODE, MODE_SPEECH)
        val vibrate = prefs.getBoolean(KEY_VIBRATION, true)

        if (vibrate) vibrateFor(event, code)
        if (mode == MODE_OFF) return

        if (mode == MODE_SPEECH && ttsReady) {
            speak(phraseFor(event, code, exit, dist))
        } else {
            beep(event)
        }
    }

    /**
     * Builds the sentence. Note that the manoeuvre phrase is a resource, not a
     * word assembled here: Russian and English put the distance and the verb
     * in different places, and the only way that comes out right in both is to
     * let each locale own the whole sentence template.
     */
    private fun phraseFor(event: String, code: String, exit: Int, dist: Int): String {
        val manoeuvre = when (code) {
            "left" -> LocaleHelper.string(context, R.string.cue_turn_left)
            "right" -> LocaleHelper.string(context, R.string.cue_turn_right)
            "sll" -> LocaleHelper.string(context, R.string.cue_slight_left)
            "slr" -> LocaleHelper.string(context, R.string.cue_slight_right)
            "shl" -> LocaleHelper.string(context, R.string.cue_sharp_left)
            "shr" -> LocaleHelper.string(context, R.string.cue_sharp_right)
            "uturn" -> LocaleHelper.string(context, R.string.cue_uturn)
            "keepl" -> LocaleHelper.string(context, R.string.cue_keep_left)
            "keepr" -> LocaleHelper.string(context, R.string.cue_keep_right)
            "rndb" ->
                // The exit number has always been in BRouter's hints and was
                // being thrown away by both halves of this project until now.
                // "Take the second exit" is the difference between a usable
                // roundabout instruction and a useless one.
                if (exit > 0) LocaleHelper.string(context, R.string.cue_roundabout_exit, exit)
                else LocaleHelper.string(context, R.string.cue_roundabout)
            "dest" -> LocaleHelper.string(context, R.string.cue_destination)
            else -> LocaleHelper.string(context, R.string.cue_straight)
        }

        return when (event) {
            "ARRIVE" -> LocaleHelper.string(context, R.string.cue_arrive)
            "REROUTE" -> LocaleHelper.string(context, R.string.cue_reroute)
            "PREP" -> LocaleHelper.string(context, R.string.cue_prepare, manoeuvre, roundDistance(dist))
            else -> LocaleHelper.string(context, R.string.cue_now, manoeuvre)
        }
    }

    /**
     * Spoken distances are rounded to something a person would actually say.
     * "In two hundred and eighty-seven meters" is precise and useless; the
     * board's own display rounds to 10m for the same reason.
     */
    private fun roundDistance(dist: Int): Int = when {
        dist >= 500 -> (dist / 100) * 100
        dist >= 100 -> (dist / 50) * 50
        else -> (dist / 10) * 10
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        requestFocus()
        // QUEUE_FLUSH, not QUEUE_ADD: if a cue is still being spoken when the
        // next one fires, the new one is the one that matters. Queueing would
        // mean hearing "in three hundred meters, turn left" while already IN
        // the turn.
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navbridge-cue")
    }

    private fun beep(event: String) {
        requestFocus()
        Thread {
            var tone: ToneGenerator? = null
            try {
                // STREAM_MUSIC rather than STREAM_NOTIFICATION so the beep
                // follows the same route as the spoken cue over Bluetooth, and
                // is not silenced by a notification-volume setting the rider
                // has nothing to do with.
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                when (event) {
                    "ARRIVE" -> {
                        tone.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                        Thread.sleep(350)
                    }
                    "REROUTE" -> {
                        tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                        Thread.sleep(350)
                    }
                    "NOW" -> {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                        Thread.sleep(300)
                    }
                    else -> {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        Thread.sleep(200)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "beep failed: ${e.message}")
            } finally {
                tone?.release()
                abandonFocus()
            }
        }.start()
    }

    private fun vibrateFor(event: String, code: String) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        val pattern = when {
            event == "ARRIVE" -> PATTERN_ARRIVE
            event == "REROUTE" -> PATTERN_REROUTE
            code == "left" || code == "sll" || code == "shl" || code == "keepl" -> PATTERN_LEFT
            code == "right" || code == "slr" || code == "shr" || code == "keepr" -> PATTERN_RIGHT
            else -> PATTERN_GENERIC
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // -1 = play once, do not repeat. The navigation audio
                // attributes matter here too: without them a cue can be
                // suppressed by Do Not Disturb, which is exactly when you are
                // most likely to be riding.
                v.vibrate(VibrationEffect.createWaveform(pattern, -1), navAttributes)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed: ${e.message}")
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navAttributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}

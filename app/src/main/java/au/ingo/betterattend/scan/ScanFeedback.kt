package au.ingo.betterattend.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Plays a distinct sound and vibration per scan outcome. Sounds are synthesised into WAV files in
 * the cache dir on first use (off the main thread) and preloaded into a [SoundPool] for low latency.
 * Every failure is swallowed: feedback must never break scanning.
 */
class ScanFeedback(context: Context) {
    private val appContext = context.applicationContext
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundIds = ConcurrentHashMap<FeedbackKind, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val io = Executors.newSingleThreadExecutor()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        io.execute {
            runCatching {
                val dir = File(appContext.cacheDir, "scan-tones-v1").apply { mkdirs() }
                FeedbackKind.entries.forEach { kind ->
                    val file = File(dir, "${kind.name.lowercase()}.wav")
                    if (!file.exists() || file.length() == 0L) file.writeBytes(ToneSynth.wavFor(kind))
                    soundIds[kind] = pool.load(file.absolutePath, 1)
                }
            }
        }
    }

    fun play(kind: FeedbackKind, sound: Boolean, haptic: Boolean) {
        if (sound) runCatching {
            soundIds[kind]?.takeIf { it in loaded }?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
        }
        if (haptic) runCatching { vibrator?.vibrate(effectFor(kind)) }
    }

    private fun effectFor(kind: FeedbackKind): VibrationEffect = when (kind) {
        FeedbackKind.Success -> VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
        FeedbackKind.Info -> VibrationEffect.createOneShot(25, 110)
        FeedbackKind.Warning -> VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), intArrayOf(0, 200, 0, 200), -1)
        FeedbackKind.Reject -> VibrationEffect.createWaveform(longArrayOf(0, 70, 60, 70, 60, 120), intArrayOf(0, 255, 0, 255, 0, 255), -1)
    }

    fun release() {
        runCatching { pool.release() }
        io.shutdown()
    }
}

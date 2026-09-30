package au.ingo.betterattend.scan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The four kinds of scan feedback; each has its own sound and vibration. */
enum class FeedbackKind { Success, Warning, Info, Reject }

/**
 * Synthesises the scanner's short feedback cues at runtime (no bundled audio assets): soft sine
 * notes with a little harmonic body and click-free attack/release envelopes, as 16-bit mono WAV.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44_100

    /** One note: [freq] Hz for [ms] milliseconds, then [gapMs] of silence. */
    data class Note(val freq: Double, val ms: Int, val gapMs: Int = 0, val volume: Double = 0.8, val harmonic: Double = 0.18)

    fun notesFor(kind: FeedbackKind): List<Note> = when (kind) {
        // Bright rising major third: "yes!"
        FeedbackKind.Success -> listOf(Note(1046.5, 90, 10), Note(1318.5, 170))
        // Neutral repeated note: "seen this before"
        FeedbackKind.Warning -> listOf(Note(880.0, 80, 60), Note(880.0, 80))
        // Gentle single low-mid blip, quieter: "saved, nothing to worry about"
        FeedbackKind.Info -> listOf(Note(659.3, 70, 20, volume = 0.55), Note(784.0, 110, volume = 0.55))
        // Low descending buzz with more harmonics: "no"
        FeedbackKind.Reject -> listOf(Note(311.1, 120, 20, harmonic = 0.45), Note(233.1, 170, harmonic = 0.45))
    }

    /** Renders [notes] to signed 16-bit PCM samples. */
    fun render(notes: List<Note>, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = notes.sumOf { (it.ms + it.gapMs) * sampleRate / 1000 }
        val out = ShortArray(total)
        var offset = 0
        for (n in notes) {
            val len = n.ms * sampleRate / 1000
            val attack = min(len / 4, sampleRate * 6 / 1000).coerceAtLeast(1)
            val release = min(len / 2, sampleRate * 45 / 1000).coerceAtLeast(1)
            for (i in 0 until len) {
                val t = i.toDouble() / sampleRate
                val env = when {
                    i < attack -> i.toDouble() / attack
                    i > len - release -> (len - i).toDouble() / release
                    else -> 1.0
                }
                val s = sin(2 * PI * n.freq * t) + n.harmonic * sin(4 * PI * n.freq * t)
                val v = s / (1 + n.harmonic) * env * n.volume
                out[offset + i] = (v * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            offset += len + n.gapMs * sampleRate / 1000
        }
        return out
    }

    /** Wraps PCM samples in a minimal RIFF/WAVE container. */
    fun wav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { body.putShort(it) }
        return ByteArrayOutputStream(44 + dataBytes).apply { write(header.array()); write(body.array()) }.toByteArray()
    }

    fun wavFor(kind: FeedbackKind): ByteArray = wav(render(notesFor(kind)))
}

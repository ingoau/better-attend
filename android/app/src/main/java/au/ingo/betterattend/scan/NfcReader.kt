package au.ingo.betterattend.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

enum class NfcStatus { Unavailable, Disabled, Ready }

object NfcSupport {
    fun status(context: Context): NfcStatus {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() ?: return NfcStatus.Unavailable
        return if (adapter.isEnabled) NfcStatus.Ready else NfcStatus.Disabled
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Reads the NDEF message from a tag (on the NFC binder thread) and parses it. */
    fun read(tag: Tag): NfcParseResult {
        val ndef = Ndef.get(tag) ?: return NfcParseResult.Unrecognised(NdefParser.NO_DATA)
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
            NdefParser.parse(message?.records.orEmpty().map(RawNdefRecord::from))
        } catch (_: Exception) {
            // Cached message is still available when the tag moved away mid-read.
            ndef.cachedNdefMessage?.let { NdefParser.parse(it.records.map(RawNdefRecord::from)) }
                ?: NfcParseResult.Unrecognised("Couldn't read that tag. Hold it still against the back of the phone.")
        } finally {
            runCatching { ndef.close() }
        }
    }
}

/**
 * Listens for NFC tags with reader mode while the calling screen is resumed and [enabled].
 * Results are delivered on the main thread. Returns the adapter's current status so the UI can show
 * an "NFC ready" chip or a hint to turn NFC on (re-checked every time the screen resumes).
 */
@Composable
fun rememberNfcReader(enabled: Boolean, onResult: (NfcParseResult) -> Unit): NfcStatus {
    val context = LocalContext.current
    val activity: Activity? = LocalActivity.current
    val callback by rememberUpdatedState(onResult)
    var status by remember { mutableStateOf(NfcSupport.status(context)) }
    val main = remember { Handler(Looper.getMainLooper()) }

    LifecycleResumeEffect(enabled, activity) {
        status = NfcSupport.status(context)
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        val active = enabled && activity != null && adapter != null && status == NfcStatus.Ready
        if (active) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            runCatching {
                adapter!!.enableReaderMode(activity, { tag -> val r = NfcSupport.read(tag); main.post { callback(r) } }, flags, extras)
            }
        }
        onPauseOrDispose {
            if (active) runCatching { adapter!!.disableReaderMode(activity) }
        }
    }
    return status
}

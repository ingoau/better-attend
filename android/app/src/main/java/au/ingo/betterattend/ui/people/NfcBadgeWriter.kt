package au.ingo.betterattend.ui.people

import android.app.Activity
import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import java.io.IOException

/**
 * The Hack Club badge format, kept compatible with badges written by the old iOS app:
 *  1. (optional) URI record `https://badge.hackclub.com/t/<slack_user_id>` so any phone opens the badge page;
 *  2. External record `hackclub.com:attend` whose payload is the ASCII badge token Attend scans.
 *
 * Deliberately self-contained (the scanner has its own reader) so the two features don't collide.
 */
object BadgeNdef {
    const val DOMAIN = "hackclub.com"
    const val TYPE = "attend"
    const val EXTERNAL_TYPE = "$DOMAIN:$TYPE"

    fun badgeUrl(slackUserId: String) = "https://badge.hackclub.com/t/$slackUserId"

    fun buildMessage(token: String, slackUserId: String?): NdefMessage {
        require(token.isNotBlank()) { "Badge token is blank" }
        val records = buildList {
            if (!slackUserId.isNullOrBlank()) add(NdefRecord.createUri(badgeUrl(slackUserId.trim())))
            add(NdefRecord.createExternal(DOMAIN, TYPE, token.trim().toByteArray(Charsets.US_ASCII)))
        }
        return NdefMessage(records.toTypedArray())
    }

    /** The Attend token on a tag, or null if there isn't one. */
    fun readToken(message: NdefMessage?): String? = message?.records?.firstOrNull { r ->
        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(r.type, Charsets.US_ASCII).equals(EXTERNAL_TYPE, ignoreCase = true)
    }?.payload?.toString(Charsets.US_ASCII)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Why a write didn't work, phrased for staff at a busy desk. */
class BadgeWriteException(message: String, val retryable: Boolean = true) : Exception(message)

enum class NfcAvailability { Unsupported, Disabled, Ready }

object NfcBadgeWriter {

    fun availability(context: Context): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.Unsupported
        return if (adapter.isEnabled) NfcAvailability.Ready else NfcAvailability.Disabled
    }

    /**
     * Listens for any tag while [activity] is resumed. Reader mode (rather than foreground dispatch)
     * stops Android from also opening the tag in another app. Call [stop] when done (and on pause).
     *
     * Must be called while [activity] is resumed: `enableReaderMode` throws IllegalStateException otherwise.
     * Any such failure is swallowed and reported as `false` rather than crashing the screen.
     */
    fun start(activity: Activity, onTag: (Tag) -> Unit): Boolean = runCatching {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(activity, { tag -> onTag(tag) }, flags, Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        })
        true
    }.getOrDefault(false)

    fun stop(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    /**
     * Writes [message] and reads it back, returning the token found on the tag. Blocking: call off
     * the main thread. Throws [BadgeWriteException] with a friendly message on failure.
     */
    fun writeAndVerify(tag: Tag, message: NdefMessage, expectedToken: String): String {
        try {
            val ndef = Ndef.get(tag)
            val readBack: NdefMessage? = if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) throw BadgeWriteException("This badge is locked (read-only). Use a blank badge.", retryable = false)
                    if (it.maxSize < message.byteArrayLength) {
                        throw BadgeWriteException("This tag is too small for an Attend badge (${it.maxSize} bytes).", retryable = false)
                    }
                    it.writeNdefMessage(message)
                    it.ndefMessage
                }
            } else {
                val formatable = NdefFormatable.get(tag)
                    ?: throw BadgeWriteException("This tag can't store badge data. Try a different badge.", retryable = false)
                formatable.use { it.connect(); it.format(message) }
                Ndef.get(tag)?.use { it.connect(); it.ndefMessage }
            }
            val found = BadgeNdef.readToken(readBack)
                ?: throw BadgeWriteException("Couldn't verify the badge. Hold it still and try again.")
            if (!found.equals(expectedToken.trim(), ignoreCase = true)) {
                throw BadgeWriteException("The badge didn't save correctly. Hold it still and try again.")
            }
            return found
        } catch (e: BadgeWriteException) {
            throw e
        } catch (_: TagLostException) {
            throw BadgeWriteException("Lost contact with the badge. Hold it flat against the phone and try again.")
        } catch (_: FormatException) {
            throw BadgeWriteException("The badge rejected the data. Try again, or use a different badge.")
        } catch (_: IOException) {
            throw BadgeWriteException("Lost contact with the badge. Hold it flat against the phone and try again.")
        } catch (e: SecurityException) {
            throw BadgeWriteException("The badge moved away too soon. Try again.")
        }
    }
}

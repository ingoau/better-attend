package au.ingo.betterattend.scan

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanRepository

/**
 * The Hack Club badge NDEF format, kept compatible with badges written by the old app:
 *  1. (optional) URI record `https://badge.hackclub.com/t/<slack user id>` so any phone tapping the
 *     badge opens the person's Hack Club badge page;
 *  2. External record (TNF 0x04) of type `hackclub.com:attend` whose payload is the ASCII badge token.
 */
object NfcBadgeFormat {
    const val EXTERNAL_DOMAIN = "hackclub.com"
    const val EXTERNAL_TYPE_NAME = "attend"
    /** Full external type as stored in the record's type field. */
    const val EXTERNAL_TYPE = "$EXTERNAL_DOMAIN:$EXTERNAL_TYPE_NAME"
    const val BADGE_URL_PREFIX = "https://badge.hackclub.com/t/"

    /** Builds the two-record badge message to write to a blank tag. */
    fun buildBadgeMessage(slackUserId: String?, token: String): NdefMessage {
        val records = buildList {
            if (!slackUserId.isNullOrBlank()) add(NdefRecord.createUri(BADGE_URL_PREFIX + slackUserId.trim()))
            add(NdefRecord.createExternal(EXTERNAL_DOMAIN, EXTERNAL_TYPE_NAME, token.trim().toByteArray(Charsets.US_ASCII)))
        }
        return NdefMessage(records.toTypedArray())
    }
}

/** A platform-free copy of an NDEF record so parsing can be unit tested on the JVM. */
class RawNdefRecord(val tnf: Short, val type: ByteArray, val payload: ByteArray) {
    companion object {
        fun from(record: NdefRecord) = RawNdefRecord(record.tnf, record.type ?: ByteArray(0), record.payload ?: ByteArray(0))
    }
}

sealed interface NfcParseResult {
    data class Input(val input: ScanInput) : NfcParseResult
    /** The tag was read but holds nothing we can scan. [message] is shown on the result card. */
    data class Unrecognised(val message: String) : NfcParseResult
}

object NdefParser {
    // NFC Forum constants, duplicated here so this object has no Android dependency.
    private const val TNF_WELL_KNOWN: Short = 0x01
    private const val TNF_ABSOLUTE_URI: Short = 0x03
    private const val TNF_EXTERNAL_TYPE: Short = 0x04
    private val RTD_URI = byteArrayOf('U'.code.toByte())
    private val RTD_TEXT = byteArrayOf('T'.code.toByte())

    const val NO_DATA = "This tag doesn't have any Attend data on it."
    const val UNLINKED_BADGE = "This badge isn't linked to Attend yet. Find the person manually, then rewrite their badge."
    const val UNRECOGNISED = "This tag isn't an Attend badge or ticket."

    /** NFC Forum URI Record Type Definition abbreviation table (index = prefix byte). */
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
        "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /**
     * Turns the records on a tag into something to scan:
     *  1. an external `hackclub.com:attend` record → badge token;
     *  2. else URI / Text records → [ScanRepository.parseCode] (e.g. `attend://checkin/<id>`);
     *  3. else the first record's payload as text → [ScanRepository.parseCode].
     */
    fun parse(records: List<RawNdefRecord>): NfcParseResult {
        if (records.isEmpty()) return NfcParseResult.Unrecognised(NO_DATA)

        records.firstOrNull { it.tnf == TNF_EXTERNAL_TYPE && String(it.type, Charsets.US_ASCII).equals(NfcBadgeFormat.EXTERNAL_TYPE, ignoreCase = true) }
            ?.let { rec ->
                val token = String(rec.payload, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (token.isNotEmpty()) return NfcParseResult.Input(ScanInput(badgeToken = token, source = "nfc"))
            }

        var sawBadgeUrl = false
        for (rec in records) {
            val text = textOf(rec) ?: continue
            ScanRepository.parseCode(text, "nfc")?.let { return NfcParseResult.Input(it) }
            if (text.startsWith(NfcBadgeFormat.BADGE_URL_PREFIX, ignoreCase = true)) sawBadgeUrl = true
        }
        if (sawBadgeUrl) return NfcParseResult.Unrecognised(UNLINKED_BADGE)

        val fallback = records.first().payload.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }
        fallback?.let { ScanRepository.parseCode(it, "nfc") }?.let { return NfcParseResult.Input(it) }
        return NfcParseResult.Unrecognised(if (fallback.isNullOrBlank()) NO_DATA else UNRECOGNISED)
    }

    /** Decodes a URI or Text record to a string, or null for any other record type. */
    fun textOf(rec: RawNdefRecord): String? = when {
        rec.tnf == TNF_WELL_KNOWN && rec.type.contentEquals(RTD_URI) -> decodeUri(rec.payload)
        rec.tnf == TNF_WELL_KNOWN && rec.type.contentEquals(RTD_TEXT) -> decodeText(rec.payload)
        rec.tnf == TNF_ABSOLUTE_URI -> String(rec.type, Charsets.UTF_8)
        else -> null
    }

    fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(code) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLength = status and 0x3F
        val start = 1 + langLength
        if (start > payload.size) return null
        return String(payload, start, payload.size - start, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }
}

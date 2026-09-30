package au.ingo.betterattend.scan

import au.ingo.betterattend.data.repo.ScanInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefParserTest {
    private val uuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val token = "e4b1f0c2-1111-4a7b-8c9d-0e1f2a3b4c5d"

    private fun external(type: String, payload: String) = RawNdefRecord(0x04, type.toByteArray(), payload.toByteArray(Charsets.US_ASCII))
    private fun uri(prefix: Int, rest: String) = RawNdefRecord(0x01, byteArrayOf('U'.code.toByte()), byteArrayOf(prefix.toByte()) + rest.toByteArray())
    private fun text(value: String, lang: String = "en", utf16: Boolean = false): RawNdefRecord {
        val langBytes = lang.toByteArray(Charsets.US_ASCII)
        val status = (if (utf16) 0x80 else 0) or langBytes.size
        val body = value.toByteArray(if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        return RawNdefRecord(0x01, byteArrayOf('T'.code.toByte()), byteArrayOf(status.toByte()) + langBytes + body)
    }

    private fun input(r: NfcParseResult): ScanInput = (r as NfcParseResult.Input).input

    @Test fun externalRecordGivesBadgeToken() {
        val r = NdefParser.parse(listOf(external("hackclub.com:attend", token)))
        assertEquals(ScanInput(badgeToken = token, source = "nfc"), input(r))
    }

    @Test fun externalRecordWinsOverUriRecordEvenWhenSecond() {
        val r = NdefParser.parse(listOf(uri(0x04, "badge.hackclub.com/t/U01ABC"), external("hackclub.com:attend", token)))
        assertEquals(token, input(r).badgeToken)
    }

    @Test fun externalTypeIsCaseInsensitiveAndPayloadTrimmed() {
        val r = NdefParser.parse(listOf(external("HackClub.com:Attend", "  $token\u0000")))
        assertEquals(token, input(r).badgeToken)
    }

    @Test fun otherExternalTypesAreNotBadgeTokens() {
        // Falls back to the raw payload (like the old app), so it's tried as a participant id, never a badge token.
        val r = NdefParser.parse(listOf(external("example.com:thing", token)))
        assertEquals(ScanInput(participantId = token, source = "nfc"), input(r))
        assertTrue(NdefParser.parse(listOf(external("example.com:thing", "hello"))) is NfcParseResult.Unrecognised)
    }

    @Test fun emptyExternalPayloadFallsThroughToOtherRecords() {
        val r = NdefParser.parse(listOf(external("hackclub.com:attend", ""), uri(0x00, "attend://checkin/$uuid")))
        assertEquals("attend://checkin/$uuid", input(r).participantId)
    }

    @Test fun uriRecordWithAttendCheckin() {
        val r = NdefParser.parse(listOf(uri(0x00, "attend://checkin/$uuid")))
        assertEquals(ScanInput(participantId = "attend://checkin/$uuid", source = "nfc"), input(r))
    }

    @Test fun uriRecordWithHttpsPrefixByte() {
        val r = NdefParser.parse(listOf(uri(0x04, "attend.hackclub.com/tickets/$uuid")))
        assertEquals(uuid, input(r).participantId)
    }

    @Test fun textRecordUtf8() {
        val r = NdefParser.parse(listOf(text(uuid)))
        assertEquals(uuid, input(r).participantId)
    }

    @Test fun textRecordUtf16WithLongLanguageCode() {
        val r = NdefParser.parse(listOf(text("attend:P:$uuid", lang = "en-AU", utf16 = true)))
        assertEquals(uuid, input(r).participantId)
    }

    @Test fun badgeUrlOnlyExplainsTheBadgeIsUnlinked() {
        val r = NdefParser.parse(listOf(uri(0x04, "badge.hackclub.com/t/U01ABCDEF")))
        assertEquals(NfcParseResult.Unrecognised(NdefParser.UNLINKED_BADGE), r)
    }

    @Test fun emptyTagSaysNoData() {
        assertEquals(NfcParseResult.Unrecognised(NdefParser.NO_DATA), NdefParser.parse(emptyList()))
    }

    @Test fun unrelatedUriIsUnrecognised() {
        val r = NdefParser.parse(listOf(uri(0x02, "example.com")))
        assertEquals(NfcParseResult.Unrecognised(NdefParser.UNRECOGNISED), r)
    }

    @Test fun unknownRecordTypeFallsBackToRawPayload() {
        val r = NdefParser.parse(listOf(RawNdefRecord(0x02, "text/plain".toByteArray(), uuid.toByteArray())))
        assertEquals(uuid, input(r).participantId)
    }

    @Test fun absoluteUriRecord() {
        val r = NdefParser.parse(listOf(RawNdefRecord(0x03, "attend://checkin/$uuid".toByteArray(), ByteArray(0))))
        assertEquals("attend://checkin/$uuid", input(r).participantId)
    }

    @Test fun uriDecodingUsesPrefixTable() {
        assertEquals("https://www.hackclub.com", NdefParser.decodeUri(byteArrayOf(0x02) + "hackclub.com".toByteArray()))
        assertEquals("tel:123", NdefParser.decodeUri(byteArrayOf(0x05) + "123".toByteArray()))
        assertEquals("urn:nfc:x", NdefParser.decodeUri(byteArrayOf(0x23) + "x".toByteArray()))
    }
}

package au.ingo.betterattend.scan

import android.nfc.NdefRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Round-trips the badge writer through the parser using the real platform NdefRecord. */
@RunWith(RobolectricTestRunner::class)
class NfcBadgeFormatTest {
    private val token = "e4b1f0c2-1111-4a7b-8c9d-0e1f2a3b4c5d"

    @Test fun writesUriThenExternalRecord() {
        val msg = NfcBadgeFormat.buildBadgeMessage("U01ABCDEF", token)
        assertEquals(2, msg.records.size)
        assertEquals("https://badge.hackclub.com/t/U01ABCDEF", msg.records[0].toUri().toString())
        assertEquals(NdefRecord.TNF_EXTERNAL_TYPE, msg.records[1].tnf)
        assertEquals("hackclub.com:attend", String(msg.records[1].type, Charsets.US_ASCII))
        assertEquals(token, String(msg.records[1].payload, Charsets.US_ASCII))
    }

    @Test fun omitsUriWithoutSlackId() {
        val msg = NfcBadgeFormat.buildBadgeMessage(null, token)
        assertEquals(1, msg.records.size)
    }

    @Test fun roundTripsThroughParser() {
        val msg = NfcBadgeFormat.buildBadgeMessage("U01ABCDEF", token)
        val result = NdefParser.parse(msg.records.map(RawNdefRecord::from))
        assertEquals(token, (result as NfcParseResult.Input).input.badgeToken)
        assertEquals("nfc", result.input.source)
    }
}

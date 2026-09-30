package au.ingo.betterattend.ui.people

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BadgeNdefTest {
    private val token = "e4b1c2d3-0000-4000-8000-123456789abc"

    @Test fun twoRecordsWithSlack() {
        val msg = BadgeNdef.buildMessage(token, "U01ABCDEF")
        assertEquals(2, msg.records.size)
        val uri = msg.records[0]
        assertEquals(NdefRecord.TNF_WELL_KNOWN, uri.tnf)
        assertEquals("https://badge.hackclub.com/t/U01ABCDEF", uri.toUri().toString())
        val ext = msg.records[1]
        assertEquals(NdefRecord.TNF_EXTERNAL_TYPE, ext.tnf)
        assertEquals("hackclub.com:attend", String(ext.type, Charsets.US_ASCII))
        assertArrayEquals(token.toByteArray(Charsets.US_ASCII), ext.payload)
        assertEquals(0, ext.id.size)
    }

    @Test fun onlyExternalRecordWithoutSlack() {
        val msg = BadgeNdef.buildMessage(token, null)
        assertEquals(1, msg.records.size)
        assertEquals(NdefRecord.TNF_EXTERNAL_TYPE, msg.records[0].tnf)
        assertEquals(1, BadgeNdef.buildMessage(token, "  ").records.size)
    }

    @Test fun roundTripsThroughBytes() {
        val bytes = BadgeNdef.buildMessage(token, "U1").toByteArray()
        assertEquals(token, BadgeNdef.readToken(NdefMessage(bytes)))
    }

    @Test fun readTokenIgnoresOtherRecords() {
        val onlyUri = NdefMessage(arrayOf(NdefRecord.createUri("https://badge.hackclub.com/t/U1")))
        assertNull(BadgeNdef.readToken(onlyUri))
        assertNull(BadgeNdef.readToken(null))
    }

    @Test fun noteValidation() {
        assertEquals("Write something first.", validateNote("   ", "ops", "normal"))
        assertEquals(null, validateNote("Hello", "safeguarding", "restricted"))
        assertEquals("Pick a note type.", validateNote("Hello", "medical", "normal"))
        assertEquals("Pick a sensitivity.", validateNote("Hello", "ops", "secret"))
        assertEquals(null, validateNote("x".repeat(MAX_NOTE_LENGTH), "ops", "normal"))
        assertEquals("Notes can be up to $MAX_NOTE_LENGTH characters.", validateNote("x".repeat(MAX_NOTE_LENGTH + 1), "ops", "normal"))
    }
}

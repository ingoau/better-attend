package au.ingo.betterattend.ui.blasts

import au.ingo.betterattend.data.model.SlackBlast
import au.ingo.betterattend.ui.preview.OrganizerSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlastLogicTest {
    private fun blast(status: String, recipients: Int = 120, sent: Int = 0, failed: Int = 0) =
        SlackBlast(id = "b", message = "m", status = status, recipientCount = recipients, sentCount = sent, failedCount = failed)

    @Test fun htmlKeepsLineBreaksAndEscapes() {
        assertEquals("Lunch &amp; snacks &lt;now&gt;", BlastLogic.toHtml("  Lunch & snacks <now>  "))
        assertEquals("Line one<br>Line two", BlastLogic.toHtml("Line one\nLine two"))
        assertEquals("<p>Para one</p><p>Para two<br>more</p>", BlastLogic.toHtml("Para one\n\n\nPara two\r\nmore"))
        assertEquals("", BlastLogic.toHtml("   \n  "))
    }

    @Test fun plainRoundTrips() {
        val text = "Buses at 9pm & don't be late.\nBring <everything>!\n\nThanks"
        assertEquals(text, BlastLogic.toPlain(BlastLogic.toHtml(text)))
        assertEquals("Hi\n\n• one\n• two", BlastLogic.toPlain("<p>Hi</p><ul><li>one</li><li>two</li></ul>"))
        assertEquals("Buses at 9pm sharp.\nBring it", BlastLogic.toPlain("Buses at <b>9pm sharp</b>.<br/>Bring it"))
    }

    @Test fun progressText() {
        assertEquals("118/120 sent · 2 failed", BlastLogic.progressText(blast("completed", sent = 118, failed = 2)))
        assertEquals("Sent to all 120", BlastLogic.progressText(blast("completed", sent = 120)))
        assertEquals("64 of 120 sent", BlastLogic.progressText(blast("in_progress", sent = 64)))
        assertEquals("Queued for 120 people…", BlastLogic.progressText(blast("pending")))
        assertEquals("Queued…", BlastLogic.progressText(blast("pending", recipients = 0)))
        assertEquals("Couldn't send", BlastLogic.progressText(blast("failed", recipients = 0)))
    }

    @Test fun fractionAndActive() {
        assertEquals(0.5f, BlastLogic.fraction(blast("in_progress", sent = 55, failed = 5)), 0.001f)
        assertEquals(0f, BlastLogic.fraction(blast("pending", recipients = 0)))
        assertTrue(BlastLogic.isActive(blast("pending")))
        assertTrue(BlastLogic.isActive(blast("in_progress")))
        assertFalse(BlastLogic.isActive(blast("completed")))
        assertFalse(BlastLogic.isActive(blast("failed")))
    }

    @Test fun recipientsAreConfirmedWithSlack() {
        // 120 complete, of whom every 30th has no Slack link (i = 29, 59, 89, 119).
        assertEquals(116, BlastLogic.estimateRecipients(OrganizerSamples.participants))
    }

    @Test fun upsertReplacesOrPrepends() {
        val list = OrganizerSamples.blasts
        val updated = list[0].copy(status = "completed", sentCount = 116)
        assertEquals(updated, BlastLogic.upsert(list, updated)[0])
        assertEquals(list.size, BlastLogic.upsert(list, updated).size)
        val fresh = blast("pending").copy(id = "new")
        assertEquals("new", BlastLogic.upsert(list, fresh).first().id)
    }
}

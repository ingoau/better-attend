package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.ParticipantEdit
import au.ingo.betterattend.data.model.Personal
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.util.Validation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParticipantEditLogicTest {
    private val maya = SampleData.participantDetail
    private val original = ParticipantEditLogic.formFrom(maya)

    @Test fun prefillsFromTheDetailedParticipant() {
        assertEquals(
            EditForm(
                legalFirstName = "Maya", legalLastName = "Chen", preferredName = "Maya", email = "maya@example.com",
                phone = "+61400000002", pronouns = "she/her", tshirtSize = "M", dateOfBirth = "2010-04-12",
            ),
            original,
        )
    }

    @Test fun prefillFallsBackToTheTopLevelSizeAndBlanks() {
        val p = SampleData.participants[0].copy(personal = null, tshirtSize = "L", pronouns = null, phone = null)
        val form = ParticipantEditLogic.formFrom(p)
        assertEquals("L", form.tshirtSize)
        assertEquals("", form.pronouns)
        assertEquals("", form.legalFirstName)
    }

    @Test fun unchangedFormSendsNothing() {
        assertTrue(ParticipantEditLogic.diff(original, original, includePii = true).isEmpty)
        // Whitespace-only differences aren't changes.
        assertTrue(ParticipantEditLogic.diff(original, original.copy(email = " maya@example.com "), includePii = true).isEmpty)
    }

    @Test fun onlyChangedFieldsAreSent() {
        val edit = ParticipantEditLogic.diff(original, original.copy(pronouns = "they/them", tshirtSize = "L"), includePii = true)
        assertEquals(ParticipantEdit(pronouns = "they/them", tshirtSize = "L"), edit)
        // And on the wire, the untouched fields don't appear at all.
        val json = AttendJson.encodeToJsonElement(ParticipantEdit.serializer(), edit).jsonObject
        assertEquals(setOf("pronouns", "tshirt_size"), json.keys)
    }

    @Test fun clearingAFieldSendsAnEmptyString() {
        val edit = ParticipantEditLogic.diff(original, original.copy(pronouns = "  ", preferredName = ""), includePii = true)
        assertEquals(ParticipantEdit(preferredName = "", pronouns = ""), edit)
        val json: JsonObject = AttendJson.encodeToJsonElement(ParticipantEdit.serializer(), edit).jsonObject
        assertEquals("\"\"", json["pronouns"].toString())
    }

    @Test fun valuesAreTrimmed() {
        val edit = ParticipantEditLogic.diff(original, original.copy(legalLastName = "  Chen-Wright "), includePii = true)
        assertEquals("Chen-Wright", edit.legalLastName)
    }

    @Test fun phoneAndBirthdayAreNeverSentWithoutPii() {
        val edited = original.copy(phone = "+61499999999", dateOfBirth = "2010-05-01", email = "maya.chen@example.com")
        assertEquals(ParticipantEdit(email = "maya.chen@example.com"), ParticipantEditLogic.diff(original, edited, includePii = false))
        assertEquals(
            ParticipantEdit(email = "maya.chen@example.com", phone = "+61499999999", dateOfBirth = "2010-05-01"),
            ParticipantEditLogic.diff(original, edited, includePii = true),
        )
    }

    @Test fun validFormHasNoErrors() {
        assertTrue(ParticipantEditLogic.validate(original, original.copy(pronouns = "they/them")).isEmpty())
    }

    @Test fun requiredNamesCantBeCleared() {
        val errors = ParticipantEditLogic.validate(original, original.copy(legalFirstName = " ", legalLastName = ""))
        assertEquals(setOf(EditField.LegalFirstName, EditField.LegalLastName), errors.keys)
        // A name that was never on file may stay blank.
        val blank = original.copy(legalFirstName = "")
        assertTrue(ParticipantEditLogic.validate(blank, blank).isEmpty())
    }

    @Test fun emailMustLookLikeOne() {
        assertEquals("Enter a valid email address", ParticipantEditLogic.validate(original, original.copy(email = "maya@"))[EditField.Email])
        assertEquals("Email is required", ParticipantEditLogic.validate(original, original.copy(email = ""))[EditField.Email])
        assertNull(ParticipantEditLogic.validate(original, original.copy(email = "Maya.Chen+camp@example.com.au"))[EditField.Email])
    }

    @Test fun phoneAndBirthdayAreChecked() {
        assertTrue(EditField.Phone in ParticipantEditLogic.validate(original, original.copy(phone = "call me")))
        assertTrue(EditField.Phone !in ParticipantEditLogic.validate(original, original.copy(phone = "+61 (2) 6123-4567")))
        val today = LocalDate.parse("2026-10-03")
        assertEquals(
            "Date of birth can't be in the future",
            ParticipantEditLogic.validate(original, original.copy(dateOfBirth = "2027-01-01"), today)[EditField.DateOfBirth],
        )
        assertTrue(ParticipantEditLogic.validate(original, original.copy(dateOfBirth = "2011-01-01"), today).isEmpty())
    }

    @Test fun tshirtOptionsKeepANonStandardSize() {
        assertEquals(ParticipantEditLogic.TSHIRT_SIZES, ParticipantEditLogic.tshirtOptions("M"))
        assertEquals(ParticipantEditLogic.TSHIRT_SIZES, ParticipantEditLogic.tshirtOptions("xl"))
        assertEquals(ParticipantEditLogic.TSHIRT_SIZES, ParticipantEditLogic.tshirtOptions(null))
        assertEquals(ParticipantEditLogic.TSHIRT_SIZES + "XXL", ParticipantEditLogic.tshirtOptions("XXL"))
    }

    @Test fun serverErrorsMapToFields() {
        assertEquals(EditField.Email, ParticipantEditLogic.fieldForServerError("Email has already been taken"))
        assertEquals(EditField.Phone, ParticipantEditLogic.fieldForServerError("Phone is not a valid phone number"))
        assertEquals(EditField.LegalFirstName, ParticipantEditLogic.fieldForServerError("Legal first name can't be blank"))
        assertNull(ParticipantEditLogic.fieldForServerError("Something went wrong"))
        assertNull(ParticipantEditLogic.fieldForServerError(null))
    }

    @Test fun applyLocallyUpdatesNamesAndClearsFields() {
        val edited = ParticipantEditLogic.applyLocally(maya, ParticipantEdit(legalLastName = "Wright", preferredName = "", pronouns = "", tshirtSize = "L"))
        assertEquals("Maya Wright", edited.fullName)
        assertEquals("Maya Wright", edited.displayName) // no preferred name any more
        assertNull(edited.pronouns)
        assertEquals("L", edited.personal?.tshirtSize)
        assertEquals("L", edited.tshirtSize)
        assertEquals("2010-04-12", edited.personal?.dateOfBirth)
        assertEquals(maya.email, edited.email)

        val preferred = ParticipantEditLogic.applyLocally(maya, ParticipantEdit(preferredName = "Mae"))
        assertEquals("Mae", preferred.displayName)
        assertEquals("Maya Chen", preferred.fullName)
    }

    @Test fun applyLocallyDoesntInventAPersonalBlock() {
        val p = SampleData.participants[0]
        assertNull(ParticipantEditLogic.applyLocally(p, ParticipantEdit(email = "new@example.com")).personal)
        assertEquals(Personal(legalFirstName = "Samantha"), ParticipantEditLogic.applyLocally(p, ParticipantEdit(legalFirstName = "Samantha")).personal)
    }

    @Test fun emailValidation() {
        assertTrue(Validation.looksLikeEmail("orpheus@hackclub.com"))
        assertTrue(Validation.looksLikeEmail("  a.b+c@sub.example.org "))
        assertFalse(Validation.looksLikeEmail("orpheus"))
        assertFalse(Validation.looksLikeEmail("orpheus@hackclub"))
        assertFalse(Validation.looksLikeEmail("or pheus@hackclub.com"))
        assertFalse(Validation.looksLikeEmail(""))
    }
}

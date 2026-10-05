package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ParticipantEdit
import au.ingo.betterattend.data.model.Personal
import au.ingo.betterattend.util.Validation
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The edit-details form's fields, as typed. Blank means empty. */
data class EditForm(
    val legalFirstName: String = "",
    val legalLastName: String = "",
    val preferredName: String = "",
    val email: String = "",
    val phone: String = "",
    val pronouns: String = "",
    val tshirtSize: String = "",
    /** yyyy-MM-dd, or blank. */
    val dateOfBirth: String = "",
)

enum class EditField { LegalFirstName, LegalLastName, PreferredName, Email, Phone, Pronouns, TshirtSize, DateOfBirth }

/**
 * Pure rules for "Edit details": prefill, validation and diffing (only changed fields are sent, so a
 * one-field edit can't overwrite a field someone else changed meanwhile).
 */
object ParticipantEditLogic {
    val TSHIRT_SIZES = listOf("XS", "S", "M", "L", "XL", "2XL", "3XL")

    /** Prefill from the detailed participant (the `show` payload has legal names, DOB and size under `personal`). */
    fun formFrom(p: Participant): EditForm {
        val personal = p.personal
        return EditForm(
            legalFirstName = personal?.legalFirstName.orEmpty(),
            legalLastName = personal?.legalLastName.orEmpty(),
            preferredName = personal?.preferredName.orEmpty(),
            email = p.email.orEmpty(),
            phone = p.phone.orEmpty(),
            pronouns = p.pronouns.orEmpty(),
            tshirtSize = (personal?.tshirtSize ?: p.tshirtSize).orEmpty(),
            dateOfBirth = personal?.dateOfBirth.orEmpty(),
        )
    }

    /** The standard sizes, plus the person's current size when it's something else ("XXL", "Youth M"). */
    fun tshirtOptions(current: String?): List<String> {
        val c = current?.trim().orEmpty()
        return if (c.isEmpty() || TSHIRT_SIZES.any { it.equals(c, ignoreCase = true) }) TSHIRT_SIZES else TSHIRT_SIZES + c
    }

    /**
     * Field-level problems, or an empty map when the form can be sent. Names the server requires
     * can't be cleared once present; an email must look like one.
     */
    fun validate(original: EditForm, edited: EditForm, today: LocalDate = LocalDate.now()): Map<EditField, String> = buildMap {
        if (original.legalFirstName.isNotBlank() && edited.legalFirstName.isBlank()) put(EditField.LegalFirstName, "First name is required")
        if (original.legalLastName.isNotBlank() && edited.legalLastName.isBlank()) put(EditField.LegalLastName, "Last name is required")
        val email = edited.email.trim()
        when {
            email.isEmpty() && original.email.isNotBlank() -> put(EditField.Email, "Email is required")
            email.isNotEmpty() && !Validation.looksLikeEmail(email) -> put(EditField.Email, "Enter a valid email address")
        }
        val phone = edited.phone.trim()
        if (phone.isNotEmpty() && (phone.any { !it.isDigit() && it !in "+-() ." } || phone.count(Char::isDigit) < 6)) {
            put(EditField.Phone, "Enter a phone number, including the country code")
        }
        val dob = edited.dateOfBirth.trim()
        if (dob.isNotEmpty() && dob != original.dateOfBirth.trim()) {
            val date = parseDate(dob)
            when {
                date == null -> put(EditField.DateOfBirth, "Pick a date")
                date.isAfter(today) -> put(EditField.DateOfBirth, "Date of birth can't be in the future")
            }
        }
    }

    /**
     * What to PATCH: only the fields whose trimmed value changed. Blank sends "" (clears the field).
     * Phone and date of birth are left out entirely unless [includePii]: roles that can't read
     * them never see them in the form, so they can't have changed them.
     */
    fun diff(original: EditForm, edited: EditForm, includePii: Boolean): ParticipantEdit {
        fun changed(a: String, b: String): String? = b.trim().takeIf { it != a.trim() }
        return ParticipantEdit(
            legalFirstName = changed(original.legalFirstName, edited.legalFirstName),
            legalLastName = changed(original.legalLastName, edited.legalLastName),
            preferredName = changed(original.preferredName, edited.preferredName),
            email = changed(original.email, edited.email),
            phone = if (includePii) changed(original.phone, edited.phone) else null,
            pronouns = changed(original.pronouns, edited.pronouns),
            tshirtSize = changed(original.tshirtSize, edited.tshirtSize),
            dateOfBirth = if (includePii) changed(original.dateOfBirth, edited.dateOfBirth) else null,
        )
    }

    /** Which field a server validation message is about (Rails full messages start with the attribute name). */
    fun fieldForServerError(message: String?): EditField? {
        val m = message?.trim()?.lowercase() ?: return null
        return when {
            m.startsWith("legal first name") -> EditField.LegalFirstName
            m.startsWith("legal last name") -> EditField.LegalLastName
            m.startsWith("preferred name") -> EditField.PreferredName
            m.startsWith("email") -> EditField.Email
            m.startsWith("phone") -> EditField.Phone
            m.startsWith("pronouns") -> EditField.Pronouns
            m.startsWith("tshirt size") || m.startsWith("t-shirt size") -> EditField.TshirtSize
            m.startsWith("date of birth") -> EditField.DateOfBirth
            else -> null
        }
    }

    /**
     * Applies a saved edit to our copy of the person, for when the PATCH succeeded but re-fetching the
     * full profile didn't. Mirrors upstream's display/full name rules closely enough for the list.
     */
    fun applyLocally(p: Participant, edit: ParticipantEdit): Participant {
        fun pick(new: String?, old: String?): String? = if (new != null) new.ifEmpty { null } else old
        val editsPersonal = listOf(edit.legalFirstName, edit.legalLastName, edit.preferredName, edit.tshirtSize, edit.dateOfBirth).any { it != null }
        val old = p.personal ?: Personal()
        val personal = old.copy(
            legalFirstName = pick(edit.legalFirstName, old.legalFirstName),
            legalLastName = pick(edit.legalLastName, old.legalLastName),
            preferredName = pick(edit.preferredName, old.preferredName),
            tshirtSize = pick(edit.tshirtSize, old.tshirtSize),
            dateOfBirth = pick(edit.dateOfBirth, old.dateOfBirth),
        )
        val nameChanged = edit.legalFirstName != null || edit.legalLastName != null || edit.preferredName != null
        // Upstream: full_name = legal names; display_name = preferred name, else full name.
        val fullName = if (nameChanged) listOfNotNull(personal.legalFirstName, personal.legalLastName).joinToString(" ").ifBlank { null } ?: p.fullName else p.fullName
        return p.copy(
            personal = if (p.personal == null && !editsPersonal) null else personal,
            email = pick(edit.email, p.email),
            phone = pick(edit.phone, p.phone),
            pronouns = pick(edit.pronouns, p.pronouns),
            tshirtSize = pick(edit.tshirtSize, p.tshirtSize),
            fullName = fullName,
            displayName = if (nameChanged) personal.preferredName?.takeIf { it.isNotBlank() } ?: fullName else p.displayName,
        )
    }

    fun valueOf(form: EditForm, field: EditField): String = when (field) {
        EditField.LegalFirstName -> form.legalFirstName
        EditField.LegalLastName -> form.legalLastName
        EditField.PreferredName -> form.preferredName
        EditField.Email -> form.email
        EditField.Phone -> form.phone
        EditField.Pronouns -> form.pronouns
        EditField.TshirtSize -> form.tshirtSize
        EditField.DateOfBirth -> form.dateOfBirth
    }

    fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value.trim()) } catch (_: DateTimeParseException) { null }
}

package au.ingo.betterattend.util

/** Client-side input checks shared by the forms that send email addresses to Attend. */
object Validation {
    // Close to what upstream accepts (URI::MailTo::EMAIL_REGEXP) without being stricter than it.
    private val EMAIL = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")

    fun looksLikeEmail(value: String): Boolean = EMAIL.matches(value.trim())
}

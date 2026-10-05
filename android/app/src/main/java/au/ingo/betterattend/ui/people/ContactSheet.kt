package au.ingo.betterattend.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant

/** One way to reach someone: "Call" · "+61 400 000 002". */
class ContactOption(val icon: ImageVector, val title: String, val detail: String, val onClick: () -> Unit)

/**
 * Every way to reach [p] that this viewer may use: phone actions need the PII permission, while
 * email and Slack are already on screen for anyone who can open the page.
 */
fun contactOptions(p: Participant, canViewPii: Boolean, actions: ContactActions): List<ContactOption> = buildList {
    p.phone?.takeIf { canViewPii && it.isNotBlank() }?.let { phone ->
        add(ContactOption(Icons.Outlined.Call, "Call", phone) { actions.call(phone) })
        add(ContactOption(Icons.Outlined.Sms, "Message", phone) { actions.sms(phone) })
        add(ContactOption(Icons.AutoMirrored.Outlined.Chat, "WhatsApp", phone) { actions.whatsApp(phone) })
    }
    p.email?.takeIf { it.isNotBlank() }?.let { email ->
        add(ContactOption(Icons.Outlined.Email, "Email", email) { actions.email(email) })
    }
    p.slackUserId?.takeIf { it.isNotBlank() }?.let { slack ->
        add(ContactOption(Icons.Outlined.Tag, "Slack", "Direct message · $slack") { actions.slack(slack) })
    }
}

@Composable
fun ContactSheet(name: String, options: List<ContactOption>, onDismiss: () -> Unit) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        // Close first so coming back from the dialer or Slack lands on the page, not the sheet.
        ContactSheetContent(name, options) { option -> onDismiss(); option.onClick() }
    }
}

/** The sheet's list: one segmented group, rounded at its ends, like Material 3 Expressive lists. */
@Composable
fun ContactSheetContent(name: String, options: List<ContactOption>, onPick: (ContactOption) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text("Contact $name", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, option ->
                val top = if (i == 0) 24.dp else 6.dp
                val bottom = if (i == options.lastIndex) 24.dp else 6.dp
                Surface(
                    onClick = { onPick(option) },
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(option.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                option.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

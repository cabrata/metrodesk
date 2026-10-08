package com.utaloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.utaloom.data.Stores
import com.utaloom.together.Connection
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import com.utaloom.together.TogetherState
import com.utaloom.together.toSong

/** Listen Together: create/join a room and manage members, requests and suggestions. */
@Composable
fun TogetherScreen() {
    val st by ListenTogether.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Groups, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Listen Together", style = MaterialTheme.typography.headlineMedium)
            }
            ConnectionLine(st, settings.ltServerUrl)
        }
        st.message?.let { msg ->
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f))
                        IconButton(ListenTogether::clearMessage) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }
        if (st.roomCode == null) item { JoinCard(st, settings.ltUsername) } else {
            item { RoomCard(st) }
            if (st.role == Role.HOST && st.joinRequests.isNotEmpty()) item { JoinRequests(st) }
            if (st.role == Role.HOST && st.suggestions.isNotEmpty()) item { Suggestions(st) }
            item { Members(st) }
        }
        item { Logs(st.logs) }
    }
}

@Composable
private fun ConnectionLine(st: TogetherState, url: String) {
    val (label, color) = when (st.connection) {
        Connection.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
        Connection.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.tertiary
        Connection.RECONNECTING -> "Reconnecting…" to MaterialTheme.colorScheme.tertiary
        Connection.ERROR -> "Connection error" to MaterialTheme.colorScheme.error
        Connection.DISCONNECTED -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.connection == Connection.CONNECTING || st.connection == Connection.RECONNECTING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(label, color = color, fontWeight = FontWeight.Medium)
        Text("• $url (change in Settings)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JoinCard(st: TogetherState, savedName: String) {
    var name by remember { mutableStateOf(savedName) }
    var code by remember { mutableStateOf("") }
    val busy = st.waitingApproval || st.connection == Connection.CONNECTING
    val validName = name.isNotBlank() && name.trim().length <= 32
    Card(Modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(32) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Button({ ListenTogether.createRoom(name.trim()) }, enabled = validName && !busy, modifier = Modifier.fillMaxWidth()) { Text("Create room") }
            Text("or join an existing one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    code, { v -> code = v.filter { it.isLetterOrDigit() }.uppercase().take(12) },
                    label = { Text("Room code") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy,
                )
                OutlinedButton({ ListenTogether.joinRoom(code, name.trim()) }, enabled = validName && code.length >= 4 && !busy) { Text("Join") }
            }
            if (st.waitingApproval) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Waiting for the host to approve…", Modifier.weight(1f))
                TextButton(ListenTogether::leaveRoom) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RoomCard(st: TogetherState) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (st.role == Role.HOST) "You are hosting" else "You are listening along", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.roomCode.orEmpty(), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton({ clipboard.setText(AnnotatedString(st.roomCode.orEmpty())) }) { Icon(Icons.Default.ContentCopy, "Copy room code") }
            }
            if (st.waitingForBuffer.isNotEmpty()) {
                val names = st.waitingForBuffer.map { id -> st.users.firstOrNull { it.id == id }?.name ?: id }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Waiting for ${names.joinToString()} to buffer", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.role == Role.GUEST) OutlinedButton(ListenTogether::requestSync) { Text("Resync") }
                Button(ListenTogether::leaveRoom) { Text("Leave room") }
            }
        }
    }
}

@Composable
private fun JoinRequests(st: TogetherState) = Section("Join requests") {
    st.joinRequests.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null)
            Text(r.username, Modifier.weight(1f).padding(start = 12.dp))
            IconButton({ ListenTogether.approve(r.userId) }) { Icon(Icons.Default.Check, "Approve ${r.username}", tint = MaterialTheme.colorScheme.primary) }
            IconButton({ ListenTogether.reject(r.userId) }) { Icon(Icons.Default.Close, "Reject ${r.username}", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Suggestions(st: TogetherState) = Section("Suggestions") {
    st.suggestions.forEach { s ->
        SongRow(s.track.toSong(), onClick = {}, trailing = {
            Text("from ${s.from}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ ListenTogether.approveSuggestion(s.id) }) { Icon(Icons.Default.Check, "Approve", tint = MaterialTheme.colorScheme.primary) }
            IconButton({ ListenTogether.rejectSuggestion(s.id) }) { Icon(Icons.Default.Close, "Reject", tint = MaterialTheme.colorScheme.error) }
        })
    }
}

@Composable
private fun Members(st: TogetherState) = Section("Members (${st.users.size})") {
    st.users.sortedByDescending { it.isHost }.forEach { u ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (u.isHost) Icons.Default.Star else Icons.Default.Person, null, tint = if (u.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(u.name + if (u.id == st.userId) " (you)" else "", fontWeight = if (u.isHost) FontWeight.SemiBold else FontWeight.Normal)
                val sub = listOfNotNull("Host".takeIf { u.isHost }, "Disconnected".takeIf { !u.connected }).joinToString(" • ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (st.role == Role.HOST && u.id != st.userId) {
                IconButton({ ListenTogether.transferHost(u.id) }) { Icon(Icons.Default.SwapHoriz, "Make ${u.name} host") }
                IconButton({ ListenTogether.kick(u.id) }) { Icon(Icons.Default.PersonRemove, "Kick ${u.name}", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Logs(logs: List<String>) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton({ open = !open }) { Text(if (open) "Hide connection log" else "Show connection log (${logs.size})") }
        if (open) {
            val list = rememberLazyListState()
            LaunchedEffect(logs.size) { if (logs.isNotEmpty()) list.scrollToItem(logs.lastIndex) }
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    LazyColumn(Modifier.heightIn(max = 280.dp).padding(12.dp), state = list) {
                        items(logs) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) = Card(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

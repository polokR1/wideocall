package pl.wideocall.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import pl.wideocall.app.call.AppUiState
import pl.wideocall.app.call.CallPhase
import pl.wideocall.app.call.CallViewModel
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.Contact
import pl.wideocall.app.data.FriendRequest
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.signaling.ContactPresence
import pl.wideocall.app.ui.theme.WideocallTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class NotificationAction(val action: String, val callId: String)

class MainActivity : ComponentActivity() {
    private val viewModel: CallViewModel by viewModels()
    private val notificationAction = MutableStateFlow<NotificationAction?>(null)
    private val fullScreenIntentAllowed = MutableStateFlow(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockedScreen()
        enableEdgeToEdge()
        refreshFullScreenIntentPermission()
        captureNotificationAction(intent)
        setContent {
            WideocallTheme {
                val action by notificationAction.collectAsState()
                val fullScreenAllowed by fullScreenIntentAllowed.collectAsState()
                PermissionGate(
                    viewModel = viewModel,
                    notificationAction = action,
                    fullScreenIntentAllowed = fullScreenAllowed,
                    requestFullScreenIntent = { CallNotifications.openFullScreenIntentSettings(this) },
                    consumeNotificationAction = { notificationAction.value = null },
                )
            }
        }
    }

    private fun configureLockedScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFullScreenIntentPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationAction(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshPreferences()
        viewModel.onAppVisible()
    }

    private fun captureNotificationAction(intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(
                CallNotifications.ACTION_ANSWER,
                CallNotifications.ACTION_DECLINE,
                CallNotifications.ACTION_HANG_UP,
                CallNotifications.ACTION_TELECOM_ANSWER,
                CallNotifications.ACTION_TELECOM_DECLINE,
                CallNotifications.ACTION_TELECOM_HANG_UP,
            )
        ) return
        val callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return
        CallNotifications.cancel(this, callId)
        when (action) {
            CallNotifications.ACTION_ANSWER -> TelecomCallBridge.answer(callId)
            CallNotifications.ACTION_DECLINE -> TelecomCallBridge.disconnectRejected(callId)
        }
        notificationAction.value = NotificationAction(action, callId)
    }

    private fun refreshFullScreenIntentPermission() {
        fullScreenIntentAllowed.value = CallNotifications.canUseFullScreenIntent(this)
    }
}

@Composable
private fun PermissionGate(
    viewModel: CallViewModel,
    notificationAction: NotificationAction?,
    fullScreenIntentAllowed: Boolean,
    requestFullScreenIntent: () -> Unit,
    consumeNotificationAction: () -> Unit,
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasMediaPermissions(context)) }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.CAMERA] == true &&
            results[Manifest.permission.RECORD_AUDIO] == true
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val state by viewModel.state.collectAsState()
    SecureChatWindow(
        enabled = state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT) || state.chatVisible,
    )
    LaunchedEffect(notificationAction, state.phase, state.callId, permissionsGranted) {
        val action = notificationAction ?: return@LaunchedEffect
        if (action.callId != state.callId) return@LaunchedEffect
        when (action.action) {
            CallNotifications.ACTION_ANSWER -> if (state.phase == CallPhase.INCOMING && permissionsGranted) {
                viewModel.acceptIncomingCallFromTelecom()
                consumeNotificationAction()
            }
            CallNotifications.ACTION_TELECOM_ANSWER -> if (state.phase == CallPhase.INCOMING && permissionsGranted) {
                viewModel.acceptIncomingCallFromTelecom()
                consumeNotificationAction()
            }
            CallNotifications.ACTION_DECLINE -> if (state.phase == CallPhase.INCOMING) {
                viewModel.rejectIncomingCallFromTelecom()
                consumeNotificationAction()
            }
            CallNotifications.ACTION_TELECOM_DECLINE -> if (state.phase == CallPhase.INCOMING) {
                viewModel.rejectIncomingCallFromTelecom()
                consumeNotificationAction()
            }
            CallNotifications.ACTION_HANG_UP -> if (state.phase in setOf(CallPhase.OUTGOING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)) {
                viewModel.hangUp()
                consumeNotificationAction()
            }
            CallNotifications.ACTION_TELECOM_HANG_UP -> if (state.phase in setOf(CallPhase.OUTGOING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL)) {
                viewModel.hangUpFromTelecom()
                consumeNotificationAction()
            }
        }
    }
    when (state.phase) {
        CallPhase.INCOMING -> IncomingCallScreen(state, viewModel, permissionsGranted) {
            mediaLauncher.launch(requiredMediaPermissions())
        }
        CallPhase.OUTGOING,
        CallPhase.CONNECTING_CALL,
        CallPhase.IN_CALL -> VideoCallScreen(state, viewModel)
        CallPhase.CONNECTING_CHAT -> ChatScreen(state, viewModel)
        CallPhase.IN_CHAT -> if (state.chatVisible) ChatScreen(state, viewModel) else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestPermissions = { mediaLauncher.launch(requiredMediaPermissions()) },
        )
        else -> HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestPermissions = { mediaLauncher.launch(requiredMediaPermissions()) },
        )
    }
}

@Composable
private fun SecureChatWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: AppUiState,
    viewModel: CallViewModel,
    permissionsGranted: Boolean,
    fullScreenIntentAllowed: Boolean,
    requestFullScreenIntent: () -> Unit,
    requestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    var showAddContact by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Wideocall", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Wideorozmowy i znikające wiadomości", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Ustawienia")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddContact = true }) {
                Icon(Icons.Default.Add, "Dodaj kontakt")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(state, onReconnect = viewModel::connect)
            }
            if (state.phase == CallPhase.IN_CHAT && !state.chatVisible) {
                item { HiddenChatCard(state, viewModel::openChat, viewModel::hangUp) }
            }
            if (!fullScreenIntentAllowed) {
                item { FullScreenCallCard(requestFullScreenIntent) }
            }
            item {
                MyIdCard(state.myId) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Wideocall ID", state.myId))
                    Toast.makeText(context, "ID skopiowane", Toast.LENGTH_SHORT).show()
                }
            }
            if (!permissionsGranted) {
                item {
                    PermissionCard(requestPermissions)
                }
            }
            item {
                Text(
                    "Kontakty",
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.contacts.isEmpty()) {
                item { EmptyContactsCard(onAdd = { showAddContact = true }) }
            } else {
                items(state.contacts, key = { it.id }) { contact ->
                    ContactCard(
                        contact = contact,
                        presence = state.contactPresence[contact.id],
                        callEnabled = state.phase == CallPhase.READY,
                        chatEnabled = state.phase == CallPhase.READY,
                        onCall = {
                            if (permissionsGranted) viewModel.call(contact) else requestPermissions()
                        },
                        onChat = { viewModel.startChat(contact) },
                        onDelete = { viewModel.removeContact(contact.id) },
                    )
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onSave = { id ->
                viewModel.sendFriendRequest(id)
                showAddContact = false
            },
        )
    }
    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSave = { name, watchEnabled ->
                viewModel.saveSettings(name, watchEnabled)
                showSettings = false
            },
        )
    }
    state.friendRequests.firstOrNull()?.let { request ->
        FriendRequestDialog(
            request = request,
            onAccept = { viewModel.acceptFriendRequest(request) },
            onReject = { viewModel.rejectFriendRequest(request) },
        )
    }
}

@Composable
private fun StatusCard(state: AppUiState, onReconnect: () -> Unit) {
    val connected = state.phase == CallPhase.READY || state.phase == CallPhase.IN_CHAT
    val dotColor = when (state.phase) {
        CallPhase.READY, CallPhase.IN_CHAT -> Color(0xFF3DDC97)
        CallPhase.CONNECTING_SERVER -> Color(0xFFFFC857)
        else -> Color(0xFFFF6B6B)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(dotColor, CircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    when {
                        state.phase == CallPhase.IN_CHAT -> "Czat działa w tle"
                        connected -> "Dostępny"
                        else -> "Połączenie z siecią"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.diagnostic?.let {
                    Text(
                        "Szczegóły: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.phase == CallPhase.SERVER_ERROR) {
                IconButton(onClick = onReconnect) { Icon(Icons.Default.Refresh, "Połącz ponownie") }
            }
        }
    }
}

@Composable
private fun MyIdCard(myId: String, onCopy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Twój identyfikator", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                Text(myId, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Kopiuj ID") }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Potrzebny dostęp do kamery i mikrofonu", fontWeight = FontWeight.SemiBold)
            Text("Bez tych uprawnień rozmowa wideo nie może się rozpocząć.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRequest, modifier = Modifier.align(Alignment.End)) { Text("Nadaj dostęp") }
        }
    }
}

@Composable
private fun FullScreenCallCard(onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Włącz ekran połączeń przychodzących", fontWeight = FontWeight.SemiBold)
            Text(
                "Android blokuje automatyczne pokazanie ekranu rozmowy. Bez tej zgody usłyszysz dzwonek, ale zobaczysz tylko powiadomienie.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequest, modifier = Modifier.align(Alignment.End)) {
                Text("Otwórz ustawienia")
            }
        }
    }
}

@Composable
private fun EmptyContactsCard(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Videocam, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Nie masz jeszcze kontaktów", fontWeight = FontWeight.SemiBold)
        Text("Dodaj identyfikator drugiego telefonu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAdd) { Text("Dodaj kontakt") }
    }
}

@Composable
private fun HiddenChatCard(state: AppUiState, onOpen: () -> Unit, onEnd: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(state.remoteName ?: state.remoteId.orEmpty(), fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.unreadChatCount > 0) "Nowe wiadomości: ${state.unreadChatCount}" else "Szyfrowany czat działa w tle",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onEnd) { Text("Zakończ") }
            Button(onClick = onOpen) { Text("Otwórz") }
        }
    }
}

@Composable
private fun FriendRequestDialog(
    request: FriendRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Zaproszenie do kontaktów") },
        text = {
            Text("${request.displayName} (${request.fromId}) chce dodać Cię do kontaktów.")
        },
        confirmButton = { Button(onClick = onAccept) { Text("Akceptuj") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Odrzuć") } },
    )
}

@Composable
private fun ContactCard(
    contact: Contact,
    presence: ContactPresence?,
    callEnabled: Boolean,
    chatEnabled: Boolean,
    onCall: () -> Unit,
    onChat: () -> Unit,
    onDelete: () -> Unit,
) {
    val presenceColor = when {
        presence?.busy == true -> Color(0xFFFF6B6B)
        presence?.online == true -> Color(0xFF3DDC97)
        presence?.watchReady == true -> Color(0xFF3C8DFF)
        presence?.pushReady == true -> Color(0xFFFFC857)
        else -> MaterialTheme.colorScheme.outline
    }
    val presenceText = when {
        presence?.busy == true -> "Zajęty"
        presence?.online == true -> "Dostępny"
        presence?.watchReady == true -> "Czuwanie aktywne"
        presence?.pushReady == true -> "Dostępny przez powiadomienie"
        presence == null -> "Dostępność sprawdzana przy połączeniu"
        presence.lastSeen > 0L -> "Offline • ostatnio ${relativeLastSeen(presence.lastSeen)}"
        else -> "Offline"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(contact.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(contact.name, fontWeight = FontWeight.SemiBold)
                Text(contact.id, style = MaterialTheme.typography.bodySmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(presenceColor, CircleShape))
                    Text(
                        presenceText,
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Usuń kontakt", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onChat, enabled = chatEnabled) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    "Otwórz szyfrowany czat",
                    tint = if (chatEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onCall, enabled = callEnabled) {
                Icon(Icons.Default.Videocam, "Zadzwoń", tint = if (callEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ChatScreen(state: AppUiState, viewModel: CallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Lock,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    state.remoteName ?: state.remoteId.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::closeChatLocally) {
                Icon(Icons.Default.Close, "Zamknij czat lokalnie")
            }
        }
        Spacer(Modifier.height(12.dp))
        SecurityStatusIcon(state)
        Spacer(Modifier.height(12.dp))
        EphemeralChatPanel(
            state = state,
            onClose = viewModel::closeChatLocally,
            onSend = viewModel::sendChatMessage,
            sendEnabled = state.phase == CallPhase.IN_CHAT && state.chatReady,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Treść nie jest zapisywana w bazie. Minuta jest liczona osobno na każdym telefonie od chwili otwarcia wiadomości.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IncomingCallScreen(
    state: AppUiState,
    viewModel: CallViewModel,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val ringtone = runCatching {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }.getOrNull()
        onDispose { ringtone?.stop() }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF07130F)).statusBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(112.dp).background(Color(0xFF173B2E), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Videocam, null, modifier = Modifier.size(54.dp), tint = Color(0xFF62E6A7))
        }
        Spacer(Modifier.height(30.dp))
        Text("Połączenie przychodzące", color = Color.White.copy(alpha = .65f), fontSize = 16.sp)
        Text(state.remoteName ?: state.remoteId.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(state.remoteId.orEmpty(), color = Color.White.copy(alpha = .55f), letterSpacing = 1.sp)
        Spacer(Modifier.height(64.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            RoundCallButton(Color(0xFFE84C4F), Icons.Default.CallEnd, "Odrzuć", viewModel::rejectIncomingCall)
            RoundCallButton(Color(0xFF39C27F), Icons.Default.Call, "Odbierz") {
                if (permissionsGranted) viewModel.acceptIncomingCall() else requestPermissions()
            }
        }
    }
}

@Composable
private fun VideoCallScreen(state: AppUiState, viewModel: CallViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
            eglContext = viewModel.eglContext(),
            mirror = false,
            bind = viewModel::bindRemoteRenderer,
        )
        VideoSurface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(width = 112.dp, height = 168.dp)
                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(18.dp)),
            eglContext = viewModel.eglContext(),
            mirror = state.frontCamera,
            bind = viewModel::bindLocalRenderer,
        )
        Column(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp),
        ) {
            Text(state.remoteName ?: state.remoteId.orEmpty(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(state.status, color = Color.White.copy(alpha = .7f))
            SecurityStatusIcon(state, modifier = Modifier.padding(top = 8.dp), dark = true)
        }
        if (state.chatVisible) {
            EphemeralChatPanel(
                state = state,
                onClose = viewModel::toggleChat,
                onSend = viewModel::sendChatMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 128.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                background = Color.White.copy(alpha = .18f),
                icon = if (state.microphoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                description = "Mikrofon",
                onClick = viewModel::toggleMicrophone,
            )
            RoundIconButton(
                background = Color(0xFFE84C4F),
                icon = Icons.Default.CallEnd,
                description = "Zakończ",
                size = 68,
                onClick = viewModel::hangUp,
            )
            RoundIconButton(
                background = Color.White.copy(alpha = .18f),
                icon = Icons.Default.Cameraswitch,
                description = "Zmień kamerę",
                onClick = viewModel::switchCamera,
            )
            RoundIconButton(
                background = if (state.chatVisible) Color(0xFF3C8DFF) else Color.White.copy(alpha = .18f),
                icon = Icons.AutoMirrored.Filled.Chat,
                description = "Znikający czat",
                enabled = state.phase == CallPhase.IN_CALL && state.chatReady,
                onClick = viewModel::toggleChat,
            )
        }
    }
}

@Composable
private fun SecurityStatusIcon(
    state: AppUiState,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    if (state.safetyNumber == null) return
    val container = if (dark) Color(0xCC173B2E) else Color(0xFFE3F8ED)
    val content = if (dark) Color(0xFF62E6A7) else Color(0xFF16734A)
    Surface(
        modifier = modifier.size(28.dp),
        color = container,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Tożsamość i szyfrowanie połączenia są prawidłowe",
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EphemeralChatPanel(
    state: AppUiState,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = true,
) {
    var text by remember { mutableStateOf("") }
    Card(
        modifier = modifier.fillMaxWidth().heightIn(max = 360.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE101820)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Color(0xFF62E6A7), modifier = Modifier.size(18.dp))
                Text(
                    "Szyfrowane • wiadomości znikają po minucie",
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                    color = Color.White.copy(alpha = .8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Close, "Zamknij czat", tint = Color.White)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.chatMessages, key = { it.id }) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            color = if (message.mine) Color(0xFF285FA8) else Color(0xFF26323D),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                message.text,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1_000) },
                    modifier = Modifier.weight(1f),
                    enabled = sendEnabled,
                    placeholder = { Text("Wiadomość…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        disabledTextColor = Color.White.copy(alpha = .55f),
                        focusedBorderColor = Color(0xFF62E6A7),
                        unfocusedBorderColor = Color.White.copy(alpha = .45f),
                        disabledBorderColor = Color.White.copy(alpha = .25f),
                        focusedPlaceholderColor = Color.White.copy(alpha = .55f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = .55f),
                        cursorColor = Color(0xFF62E6A7),
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }),
                )
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    enabled = sendEnabled && text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Wyślij", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    eglContext: EglBase.Context,
    mirror: Boolean,
    bind: (SurfaceViewRenderer?) -> Unit,
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }
    LaunchedEffect(mirror) { renderer.setMirror(mirror) }
    DisposableEffect(renderer) {
        bind(renderer)
        onDispose {
            bind(null)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun RoundCallButton(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIconButton(color, icon, label, size = 68, onClick = onClick)
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
    }
}

@Composable
private fun RoundIconButton(
    background: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: Int = 56,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size.dp).background(background, CircleShape),
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = if (enabled) 1f else .35f), modifier = Modifier.size((size * .42f).dp))
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    val normalized = pl.wideocall.app.data.AppPreferences.normalizeId(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaproś kontakt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.take(9) },
                    label = { Text("ID, np. AB3D-EF7H") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(normalized) }, enabled = normalized.length == 9) { Text("Wyślij zaproszenie") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun SettingsDialog(state: AppUiState, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(state.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ustawienia") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nazwa tego telefonu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Odbieranie w tle", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Zamknięty telefon jest budzony przez systemowe powiadomienie o połączeniu. Nie ma stałego połączenia z Firebase ani trwałego powiadomienia czuwania.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Połączenia są zestawiane przez szybką usługę Wideocall. Bez kont i logowania.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, false) }, enabled = name.isNotBlank()) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun hasMediaPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun requiredMediaPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun relativeLastSeen(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "przed chwilą"
        minutes < 60 -> "$minutes min temu"
        minutes < 1_440 -> "${minutes / 60} godz. temu"
        else -> "${minutes / 1_440} dni temu"
    }
}

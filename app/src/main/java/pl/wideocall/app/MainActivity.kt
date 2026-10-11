package pl.wideocall.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import pl.wideocall.app.call.CallScreenAwakePolicy
import pl.wideocall.app.call.CallScreenController
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import pl.wideocall.app.call.AppUiState
import pl.wideocall.app.call.BackNavigationAction
import pl.wideocall.app.call.BackNavigationPolicy
import pl.wideocall.app.call.CallPhase
import pl.wideocall.app.call.CallViewModel
import pl.wideocall.app.call.TelecomCallBridge
import pl.wideocall.app.data.AppPreferences
import pl.wideocall.app.data.Contact
import pl.wideocall.app.data.FriendRequest
import pl.wideocall.app.data.ContactNamePolicy
import pl.wideocall.app.data.SentFriendRequest
import pl.wideocall.app.data.VideoQualityMode
import pl.wideocall.app.notifications.CallNotifications
import pl.wideocall.app.notifications.ChatNotifications
import pl.wideocall.app.notifications.FileTransferNotifications
import pl.wideocall.app.filetransfer.FileTransferDirection
import pl.wideocall.app.filetransfer.FileTransferStatus
import pl.wideocall.app.signaling.ContactPresence
import pl.wideocall.app.ui.theme.WideocallTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class NotificationAction(val action: String, val callId: String, val peerId: String? = null)

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
        CallScreenController.bind(this, window, viewModel.state)
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
        viewModel.onAppVisible()
        applyCallScreenAwake()
    }

    private fun applyCallScreenAwake() {
        CallScreenController.apply(window, CallScreenAwakePolicy.enabled(viewModel.state.value.phase))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyCallScreenAwake()
    }

    override fun onStop() {
        CallScreenController.apply(window, false)
        super.onStop()
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
                ChatNotifications.ACTION_OPEN_CHAT,
                FileTransferNotifications.ACTION_OPEN_FILE_TRANSFER,
            )
        ) return
        val callId = when (action) {
            ChatNotifications.ACTION_OPEN_CHAT -> intent.getStringExtra(ChatNotifications.EXTRA_SESSION_ID)
            FileTransferNotifications.ACTION_OPEN_FILE_TRANSFER -> intent.getStringExtra(FileTransferNotifications.EXTRA_SESSION_ID)
            else -> intent.getStringExtra(CallNotifications.EXTRA_CALL_ID)
        } ?: return
        if (action in setOf(CallNotifications.ACTION_ANSWER, CallNotifications.ACTION_DECLINE)) {
            CallNotifications.cancel(this, callId)
        }
        if (action == CallNotifications.ACTION_DECLINE) {
            TelecomCallBridge.disconnectRejected(callId)
        }
        notificationAction.value = NotificationAction(action, callId, intent.getStringExtra(ChatNotifications.EXTRA_PEER_ID))
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
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var pendingMediaAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showMediaExplanation by remember { mutableStateOf(false) }
    var mediaDenied by remember { mutableStateOf(false) }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsGranted = hasMediaPermissions(context)
        val pending = pendingMediaAction
        pendingMediaAction = null
        if (permissionsGranted) pending?.invoke()
        else mediaDenied = true
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationGranted = hasNotificationPermission(context) }
    val requestMediaPermissionsAndThen: (() -> Unit) -> Unit = { action ->
        if (hasMediaPermissions(context)) {
            permissionsGranted = true
            action()
        } else {
            pendingMediaAction = action
            showMediaExplanation = true
        }
    }
    val permissionLifecycle = (context.findActivity() as? ComponentActivity)?.lifecycle
    DisposableEffect(permissionLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasMediaPermissions(context)
                notificationGranted = hasNotificationPermission(context)
            }
        }
        permissionLifecycle?.addObserver(observer)
        onDispose { permissionLifecycle?.removeObserver(observer) }
    }

    if (showMediaExplanation) {
        MediaPermissionExplanation(
            onDismiss = { showMediaExplanation = false; pendingMediaAction = null },
            onContinue = { showMediaExplanation = false; mediaLauncher.launch(requiredMediaPermissions()) },
        )
    }
    if (mediaDenied) {
        AlertDialog(
            onDismissRequest = { mediaDenied = false },
            title = { Text("Rozmowa nie została uruchomiona") },
            text = { Text("Nie udzielono dostępu do kamery i mikrofonu. Czat i pliki nadal działają bez tych zgód. Jeśli Android nie pyta ponownie, dostęp można włączyć w ustawieniach aplikacji.") },
            confirmButton = { TextButton(onClick = { mediaDenied = false }) { Text("Rozumiem") } },
            dismissButton = { TextButton(onClick = {
                mediaDenied = false
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }) { Text("Ustawienia aplikacji") } },
        )
    }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val state by viewModel.state.collectAsState()
    var showExitConfirmation by remember { mutableStateOf(false) }
    SecureChatWindow(
        enabled = state.phase in setOf(
            CallPhase.CONNECTING_CHAT,
            CallPhase.IN_CHAT,
            CallPhase.CONNECTING_FILE,
            CallPhase.IN_FILE,
        ) || state.chatVisible || state.fileVisible,
    )
    LaunchedEffect(notificationAction, state.phase, state.callId, permissionsGranted) {
        val action = notificationAction ?: return@LaunchedEffect
        if (action.action == ChatNotifications.ACTION_OPEN_CHAT && action.peerId != null) {
            viewModel.openLocalChat(action.peerId)
            consumeNotificationAction()
            return@LaunchedEffect
        }
        if (action.callId != state.callId) return@LaunchedEffect
        when (action.action) {
            CallNotifications.ACTION_ANSWER -> if (state.phase == CallPhase.INCOMING) {
                consumeNotificationAction()
                requestMediaPermissionsAndThen {
                    if (viewModel.state.value.callId == action.callId) viewModel.acceptIncomingCall()
                }
            }
            CallNotifications.ACTION_TELECOM_ANSWER -> if (state.phase == CallPhase.INCOMING) {
                consumeNotificationAction()
                requestMediaPermissionsAndThen {
                    if (viewModel.state.value.callId == action.callId) viewModel.acceptIncomingCallFromTelecom()
                }
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
            ChatNotifications.ACTION_OPEN_CHAT -> if (state.phase == CallPhase.INCOMING_CHAT) {
                viewModel.acceptIncomingChat(action.callId)
                consumeNotificationAction()
            } else if (state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT, CallPhase.IN_CALL)) {
                viewModel.openChat()
                consumeNotificationAction()
            }
            FileTransferNotifications.ACTION_OPEN_FILE_TRANSFER -> if (state.phase == CallPhase.INCOMING_FILE) {
                viewModel.openFileTransfer()
                consumeNotificationAction()
            } else if (state.phase in setOf(CallPhase.CONNECTING_FILE, CallPhase.IN_FILE)) {
                viewModel.openFileTransfer()
                consumeNotificationAction()
            }
        }
    }
    BackHandler {
        if (state.fileVisible && (state.phase in setOf(CallPhase.INCOMING_FILE, CallPhase.CONNECTING_FILE, CallPhase.IN_FILE) || state.fileTransfer != null)) {
            viewModel.hideFileTransfer()
        } else {
            when (BackNavigationPolicy.resolve(state.phase, state.chatVisible, state.callVisible)) {
                BackNavigationAction.CLOSE_CHAT -> viewModel.closeChatLocally()
                BackNavigationAction.HIDE_CALL -> viewModel.hideCallScreen()
                BackNavigationAction.CONFIRM_EXIT -> showExitConfirmation = true
            }
        }
    }
    if (state.chatVisible && state.chatPeerId != null && state.phase in setOf(CallPhase.READY, CallPhase.SERVER_ERROR, CallPhase.CONNECTING_SERVER, CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT, CallPhase.IN_CALL)) {
        ChatScreen(state, viewModel)
    } else if (state.phase == CallPhase.READY && state.fileVisible && state.fileTransfer != null) {
        FileTransferScreen(state, viewModel)
    } else when (state.phase) {
        CallPhase.INCOMING -> if (state.callVisible) {
            IncomingCallScreen(state, viewModel, permissionsGranted) {
                requestMediaPermissionsAndThen {
                    if (viewModel.state.value.callId == state.callId) viewModel.acceptIncomingCall()
                }
            }
        } else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        CallPhase.OUTGOING,
        CallPhase.CONNECTING_CALL,
        CallPhase.IN_CALL -> if (state.callVisible) VideoCallScreen(state, viewModel) else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        CallPhase.CONNECTING_CHAT -> if (state.chatVisible) ChatScreen(state, viewModel) else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        CallPhase.IN_CHAT -> if (state.chatVisible) ChatScreen(state, viewModel) else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        CallPhase.CONNECTING_FILE,
        CallPhase.IN_FILE -> if (state.fileVisible) FileTransferScreen(state, viewModel) else HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        else -> HomeScreen(
            state = state,
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            notificationGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestFullScreenIntent = requestFullScreenIntent,
            requestCallPermissions = requestMediaPermissionsAndThen,
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
    if (state.phase == CallPhase.INCOMING_FILE && state.fileVisible) {
        AlertDialog(
            onDismissRequest = viewModel::hideFileTransfer,
            title = { Text("Prośba o przesłanie pliku") },
            text = { Text("${state.remoteName ?: state.remoteId.orEmpty()} chce przesłać plik. Pokażemy nazwę i rozmiar przed rozpoczęciem odbioru. Plik nie zostanie odebrany automatycznie.") },
            confirmButton = {
                Button(onClick = { state.callId?.let(viewModel::acceptIncomingFile) }) { Text("Zobacz plik") }
            },
            dismissButton = { TextButton(onClick = viewModel::rejectIncomingDataSession) { Text("Odrzuć") } },
        )
    }
    if (showExitConfirmation) {
        val backgroundWork = state.callId != null || state.chatSaving || state.chatMessages.any { it.pending }
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Wyjść z Wideocall?") },
            text = { Text(if (backgroundWork) "Aplikacja zostanie schowana. Sesja i kolejka wiadomości nie zostaną zakończone. Aby zakończyć czat, użyj przycisku zakończenia." else "Aplikacja zostanie zamknięta. Nadal możesz otrzymywać połączenia przez powiadomienia systemowe.") },
            confirmButton = {
                Button(onClick = {
                    showExitConfirmation = false
                    val activity = context.findActivity()
                    if (backgroundWork) {
                        if (activity?.moveTaskToBack(true) != true) Toast.makeText(context, "Nie udało się schować aplikacji. Sesja nadal trwa.", Toast.LENGTH_SHORT).show()
                    } else activity?.finish()
                }) { Text(if (backgroundWork) "Schowaj" else "Wyjdź") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) { Text("Zostań") }
            },
        )
    }
    state.chatEndConfirmationId?.let { id ->
        EndChatConfirmationDialog(
            peerName = state.remoteName ?: "rozmówcą",
            onCancel = viewModel::cancelEndChat,
            onConfirm = { viewModel.confirmEndChat(id) },
        )
    }
}

@Composable
internal fun EndChatConfirmationDialog(peerName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Zakończyć połączenie czatu?") },
        text = { Text("Zakończysz połączenie z $peerName. Wysyłanie w tej sesji zostanie zatrzymane; niewysłane wiadomości zostaną w zaszyfrowanej kolejce. Okno na drugim telefonie nie zostanie zamknięte. Jeśli chcesz tylko wrócić do aplikacji, wybierz minimalizację.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Zakończ połączenie czatu") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Zostaw czat aktywny") } },
    )
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
    notificationGranted: Boolean,
    fullScreenIntentAllowed: Boolean,
    requestFullScreenIntent: () -> Unit,
    requestCallPermissions: (() -> Unit) -> Unit,
    requestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    var showAddContact by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<Contact?>(null) }
    var requestToCancel by remember { mutableStateOf<SentFriendRequest?>(null) }
    var fileTarget by remember { mutableStateOf<Contact?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = fileTarget
        fileTarget = null
        if (uri != null && target != null) viewModel.startFileTransfer(target, uri)
    }

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
            if (state.phase == CallPhase.INCOMING_CHAT) {
                item {
                    IncomingDataInvitationCard(
                        title = "Zaproszenie do szyfrowanego czatu",
                        peer = state.remoteName ?: state.remoteId.orEmpty(),
                        onAccept = { state.callId?.let(viewModel::acceptIncomingChat) },
                        onReject = viewModel::rejectIncomingDataSession,
                    )
                }
            }
            if (state.phase == CallPhase.INCOMING_FILE) {
                item {
                    IncomingDataInvitationCard(
                        title = "Bezpośredni transfer pliku",
                        peer = state.remoteName ?: state.remoteId.orEmpty(),
                        onAccept = { state.callId?.let(viewModel::acceptIncomingFile) },
                        onReject = viewModel::rejectIncomingDataSession,
                    )
                }
            }
            if (state.phase in setOf(CallPhase.OUTGOING, CallPhase.INCOMING, CallPhase.CONNECTING_CALL, CallPhase.IN_CALL) && !state.callVisible) {
                item {
                    HiddenCallCard(
                        state = state,
                        onOpen = viewModel::openCallScreen,
                        onEnd = viewModel::hangUp,
                    )
                }
            }
            if (state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT) && !state.chatVisible) {
                item { HiddenChatCard(state, viewModel::openChat, viewModel::requestEndChat) }
            }
            if (state.phase in setOf(CallPhase.CONNECTING_FILE, CallPhase.IN_FILE) && !state.fileVisible) {
                item { HiddenFileTransferCard(state, viewModel::openFileTransfer, viewModel::hangUp) }
            }
            if (state.phase == CallPhase.READY && state.fileTransfer?.status == FileTransferStatus.COMPLETE) {
                item { CompletedFileTransferCard(state, viewModel::openFileTransfer) }
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
            if (state.sentFriendRequests.isNotEmpty()) {
                item {
                    Text(
                        "Wysłane zaproszenia",
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.sentFriendRequests, key = { it.requestId }) { request ->
                    SentFriendRequestCard(request = request, onCancel = { requestToCancel = request })
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
                        callEnabled = state.phase == CallPhase.READY || (state.signalingConnected && state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)),
                        chatEnabled = true,
                        fileEnabled = state.phase == CallPhase.READY || (state.signalingConnected && state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)),
                        onCall = {
                            requestCallPermissions { viewModel.call(contact) }
                        },
                        onChat = { viewModel.openContactChat(contact) },
                        onFile = {
                            fileTarget = contact
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onDelete = { viewModel.removeContact(contact.id) },
                        onEdit = { contactToEdit = contact },
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
            mediaPermissionsGranted = permissionsGranted,
            notificationPermissionGranted = notificationGranted,
            fullScreenIntentAllowed = fullScreenIntentAllowed,
            requestNotificationPermission = requestNotifications,
            requestFullScreenIntent = requestFullScreenIntent,
            testConnection = viewModel::connect,
            onDismiss = { showSettings = false },
            onSave = { name, quality, frontCamera ->
                viewModel.saveSettings(name, quality, frontCamera)
                showSettings = false
            },
        )
    }
    contactToEdit?.let { contact ->
        EditContactNameDialog(contact, onDismiss = { contactToEdit = null }, onSave = { name ->
            viewModel.renameContact(contact.id, name).also { if (it.isSuccess) contactToEdit = null }
        })
    }
    requestToCancel?.let { request ->
        AlertDialog(
            onDismissRequest = { requestToCancel = null },
            title = { Text("Cofnąć zaproszenie?") },
            text = { Text("Zaproszenie wysłane do ${request.toId} zostanie anulowane także na drugim telefonie.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.cancelFriendRequest(request)
                    requestToCancel = null
                }) { Text("Cofnij zaproszenie") }
            },
            dismissButton = {
                TextButton(onClick = { requestToCancel = null }) { Text("Anuluj") }
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
internal fun MediaPermissionExplanation(onDismiss: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kamera i mikrofon podczas rozmowy") },
        text = { Text("Kamera przesyła obraz, a mikrofon Twój głos na żywo do rozmówcy. Wideocall nie zapisuje nagrania rozmowy. Android może nazwać tę zgodę „nagrywaniem” — jest ona potrzebna także do transmisji na żywo. Kamera i mikrofon nie są używane przez czat ani transfer plików. W oknie systemowym możesz wybrać „Podczas używania aplikacji” lub dostęp jednorazowy, jeśli telefon oferuje te opcje.") },
        confirmButton = { Button(onClick = onContinue) { Text("Przejdź do zgody Androida") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nie teraz") } },
    )
}

@Composable
internal fun EditContactNameDialog(contact: Contact, onDismiss: () -> Unit, onSave: (String) -> Result<Unit>) {
    var name by remember(contact.id) { mutableStateOf(contact.name) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zmień nazwę urządzenia") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${contact.id} • nazwa tylko na tym telefonie")
                OutlinedTextField(value = name, onValueChange = { name = it; error = null },
                    label = { Text("Nazwa urządzenia") }, singleLine = true, isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            val valid = ContactNamePolicy.validate(name)
            error = valid.exceptionOrNull()?.message
            if (valid.isSuccess) error = onSave(valid.getOrThrow()).exceptionOrNull()?.message
        }) { Text("Zapisz nazwę") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
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
                    if (state.phase == CallPhase.CONNECTING_CHAT) "Czat łączy się w tle" else "Szyfrowany czat działa w tle",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onEnd) { Text("Zakończ") }
            Button(onClick = onOpen) { Text("Otwórz") }
        }
    }
}

@Composable
private fun HiddenFileTransferCard(state: AppUiState, onOpen: () -> Unit, onEnd: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(state.fileTransfer?.fileName ?: "Transfer pliku", fontWeight = FontWeight.SemiBold)
                Text(state.status, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onEnd) { Text("Anuluj") }
            Button(onClick = onOpen) { Text("Otwórz") }
        }
    }
}

@Composable
private fun CompletedFileTransferCard(state: AppUiState, onOpen: () -> Unit) {
    val transfer = state.fileTransfer ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(transfer.fileName, fontWeight = FontWeight.SemiBold)
                Text("Transfer zakończony • ${formatFileSize(transfer.totalBytes)}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpen) { Text(if (transfer.direction == FileTransferDirection.RECEIVE) "Zapisz" else "Szczegóły") }
        }
    }
}

@Composable
private fun IncomingDataInvitationCard(
    title: String,
    peer: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(peer, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sesja rozpocznie się dopiero po Twojej zgodzie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReject) { Text("Odrzuć") }
                Button(onClick = onAccept) { Text("Otwórz") }
            }
        }
    }
}

@Composable
private fun HiddenCallCard(state: AppUiState, onOpen: () -> Unit, onEnd: () -> Unit) {
    val title = when (state.phase) {
        CallPhase.INCOMING -> "Połączenie przychodzące"
        CallPhase.OUTGOING -> "Dzwonienie…"
        CallPhase.CONNECTING_CALL -> "Łączenie rozmowy…"
        else -> "Rozmowa trwa"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    state.remoteName ?: state.remoteId.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.phase != CallPhase.INCOMING) {
                TextButton(onClick = onEnd) { Text("Zakończ") }
            }
            Button(onClick = onOpen) { Text("Otwórz") }
        }
    }
}

@Composable
private fun SentFriendRequestCard(request: SentFriendRequest, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(request.toId, fontWeight = FontWeight.SemiBold)
                Text(
                    "Oczekuje na odpowiedź",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text("Cofnij") }
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
    fileEnabled: Boolean,
    onCall: () -> Unit,
    onChat: () -> Unit,
    onFile: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Opcje kontaktu") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Zmień nazwę") }, onClick = { showMenu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Usuń kontakt") }, onClick = { showMenu = false; onDelete() })
                }
            }
            IconButton(onClick = onFile, enabled = fileEnabled) {
                Icon(
                    Icons.Default.AttachFile,
                    "Wyślij plik bezpośrednio",
                    tint = if (fileEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
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
    val thisPeerConnected = state.chatReady && state.chatPeerId == state.remoteId && state.phase != CallPhase.IN_FILE
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
                    state.chatPeerName ?: state.chatPeerId.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (thisPeerConnected) "Połączenie szyfrowane" else "Skrzynka lokalna • wiadomości czekają na połączenie",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::closeChatLocally) {
                Icon(Icons.Default.KeyboardArrowDown, "Minimalizuj czat")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.chatPeerId == state.remoteId) {
            SecurityStatusIcon(state, onVerify = { viewModel.markCurrentPeerVerified(state.callId, state.safetyNumber.orEmpty()) })
        }
        Spacer(Modifier.height(12.dp))
        EphemeralChatPanel(
            state = state,
            onClose = viewModel::closeChatLocally,
            onSend = viewModel::sendChatMessage,
            onDraftChanged = viewModel::setChatDraft,
            sendEnabled = state.chatPeerId != null && !state.chatSaving,
            expanded = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Minuta od otwarcia na tym telefonie; u odbiorcy samo powiadomienie nie uruchamia licznika. „Dostarczono” nie oznacza przeczytania. Treść jest zaszyfrowana lokalnie; niewysłane czekają na bezpośrednie połączenie.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = viewModel::refreshChatDelivery) { Text("Sprawdź dostarczenie") }
        if (!thisPeerConnected && state.phase == CallPhase.READY) {
            TextButton(onClick = { state.chatPeerId?.let { viewModel.connectChat(Contact(it, state.chatPeerName ?: it)) } }) {
                Text("Połącz z rozmówcą")
            }
        }
        if (state.phase in setOf(CallPhase.CONNECTING_CHAT, CallPhase.IN_CHAT)) {
            TextButton(onClick = viewModel::requestEndChat) { Text("Zakończ połączenie czatu") }
        }
        if (state.chatMessages.any { it.pending }) {
            TextButton(onClick = viewModel::discardPendingChat) { Text("Usuń niewysłane wiadomości") }
        }
    }
}

@Composable
private fun FileTransferScreen(state: AppUiState, viewModel: CallViewModel) {
    val transfer = state.fileTransfer
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(transfer?.mimeType ?: "application/octet-stream")
    ) { destination ->
        if (destination != null) viewModel.saveReceivedFile(destination)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Bezpośredni transfer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(state.remoteName ?: state.remoteId.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = viewModel::hideFileTransfer) {
                Icon(Icons.Default.KeyboardArrowDown, "Minimalizuj transfer")
            }
        }
        Spacer(Modifier.height(36.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(104.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(transfer?.fileName ?: "Oczekiwanie na informacje o pliku…", fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            transfer?.let { "${formatFileSize(it.transferredBytes)} / ${formatFileSize(it.totalBytes)}" } ?: state.status,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { transfer?.progress ?: 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(state.status, textAlign = TextAlign.Center)
        transfer?.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (transfer?.status == FileTransferStatus.WAITING_FOR_ACCEPTANCE) {
            Text("Odbiór rozpocznie się tylko za Twoją zgodą.", textAlign = TextAlign.Center)
            Button(onClick = viewModel::acceptReceivedFile, modifier = Modifier.fillMaxWidth()) { Text("Odbierz plik") }
            Spacer(Modifier.height(10.dp))
        }
        if (transfer?.direction == FileTransferDirection.RECEIVE && transfer.status == FileTransferStatus.COMPLETE) {
            Button(
                onClick = { saveLauncher.launch(transfer.fileName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Save, null)
                Text("Zapisz plik", modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = viewModel::hangUp, modifier = Modifier.fillMaxWidth()) {
            Text(if (transfer?.status == FileTransferStatus.COMPLETE) "Zakończ" else "Anuluj transfer")
        }
        Text(
            "Plik płynie szyfrowanym kanałem WebRTC bez zapisywania treści na serwerze.",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun IncomingCallScreen(
    state: AppUiState,
    viewModel: CallViewModel,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    // The call notification owns ringing. Rendering this screen must not start a second player.
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
                requestPermissions()
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
            SecurityStatusIcon(state, onVerify = { viewModel.markCurrentPeerVerified(state.callId, state.safetyNumber.orEmpty()) }, modifier = Modifier.padding(top = 8.dp), dark = true)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                background = Color.White.copy(alpha = .18f),
                icon = Icons.AutoMirrored.Filled.Chat,
                description = "Znikający czat",
                enabled = state.phase == CallPhase.IN_CALL && state.chatReady,
                onClick = viewModel::openChat,
            )
            RoundIconButton(
                background = if (state.speakerphoneEnabled) Color(0xFF3C8DFF) else Color.White.copy(alpha = .18f),
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                description = "Wyjście audio: ${state.audioRouteName}",
                enabled = state.phase == CallPhase.IN_CALL && state.canSwitchAudioRoute && !state.audioRouteChanging,
                onClick = viewModel::toggleSpeaker,
            )
        }
    }
}

@Composable
internal fun SecurityStatusIcon(
    state: AppUiState,
    onVerify: () -> Boolean,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val number = state.safetyNumber ?: return
    var showVerification by remember(state.callId, number) { mutableStateOf(false) }
    var verificationError by remember(state.callId, number) { mutableStateOf<String?>(null) }
    val container = if (dark) Color(0xCC173B2E) else Color(0xFFE3F8ED)
    val content = if (!state.peerIdentityVerified) Color(0xFFE5A326) else if (dark) Color(0xFF62E6A7) else Color(0xFF16734A)
    Surface(
        onClick = { showVerification = true },
        modifier = modifier.size(44.dp),
        color = container,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (state.peerIdentityVerified) Icons.Default.CheckCircle else Icons.Default.Lock,
                contentDescription = if (state.peerIdentityVerified) "Tożsamość ręcznie potwierdzona — numer bezpieczeństwa" else "Transport szyfrowany — dotknij, aby zweryfikować rozmówcę",
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (showVerification) {
        AlertDialog(
            onDismissRequest = { showVerification = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Zweryfikuj rozmówcę") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Porównaj cały numer z ${state.remoteName ?: state.remoteId.orEmpty()} przez niezależny, zaufany kanał lub osobiście. Oba telefony muszą pokazywać identyczny numer. Samo szyfrowanie nie potwierdza, kim jest druga osoba.")
                    Text(number, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(if (state.peerIdentityVerified) "Tożsamość została ręcznie potwierdzona." else "Tożsamość nie została jeszcze ręcznie potwierdzona.")
                    verificationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onVerify()) showVerification = false
                    else verificationError = "Nie zapisano potwierdzenia. Sprawdź, czy sesja nadal trwa i numer nie zmienił się."
                }, enabled = !state.peerIdentityVerified) { Text("Numery są identyczne") }
            },
            dismissButton = { TextButton(onClick = { showVerification = false }) { Text("Zamknij") } },
        )
    }
}

@Composable
internal fun EphemeralChatPanel(
    state: AppUiState,
    onClose: () -> Unit,
    onSend: (String) -> Boolean,
    onDraftChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = true,
    expanded: Boolean = false,
) {
    val text = state.chatDraft
    val cardModifier = if (expanded) {
        modifier.fillMaxWidth().fillMaxHeight()
    } else {
        modifier.fillMaxWidth().heightIn(max = 360.dp)
    }
    Card(
        modifier = cardModifier,
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
                    Icon(Icons.Default.KeyboardArrowDown, "Schowaj czat", tint = Color.White)
                }
            }
            val messagesModifier = if (expanded) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp)
            }
            LazyColumn(
                modifier = messagesModifier,
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
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(message.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                if (message.mine) Text(if (message.pending) "Czeka na dostarczenie" else "Dostarczono na telefon",
                                    color = Color(0xFFCEDFF6), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            state.chatSendError?.let {
                Text(it, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onDraftChanged,
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
                        }
                    }),
                )
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
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
private fun SettingsDialog(
    state: AppUiState,
    mediaPermissionsGranted: Boolean,
    notificationPermissionGranted: Boolean,
    fullScreenIntentAllowed: Boolean,
    requestNotificationPermission: () -> Unit,
    requestFullScreenIntent: () -> Unit,
    testConnection: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, VideoQualityMode, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(state.displayName) }
    var quality by remember { mutableStateOf(state.videoQualityMode) }
    var frontCamera by remember { mutableStateOf(state.startWithFrontCamera) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Ustawienia",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij") }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SettingsSection("To urządzenie") {
                OutlinedTextField(
                    value = name,
                                    onValueChange = { name = it.take(80) },
                    label = { Text("Nazwa tego telefonu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                            Text("Identyfikator: ${state.myId}", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Wideocall ID", state.myId))
                                        Toast.makeText(context, "ID skopiowane", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "Nie udało się skopiować ID", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("Kopiuj") }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        val invitation = "Dodaj mnie w Wideocall. Mój identyfikator: ${state.myId}"
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, invitation)
                                                },
                                                "Udostępnij zaproszenie",
                                            )
                                        )
                                    }.onFailure {
                                        Toast.makeText(context, "Nie udało się udostępnić zaproszenia", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("Zaproś") }
                            }
                        }
                    }

                    item {
                        SettingsSection("Jakość rozmowy") {
                            Text(
                                "Zmiana obowiązuje od następnego połączenia.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            QualityOption(
                                selected = quality == VideoQualityMode.AUTO,
                                title = "Automatyczna",
                                description = "Dopasowuje obraz do Wi‑Fi lub sieci komórkowej.",
                                onClick = { quality = VideoQualityMode.AUTO },
                            )
                            QualityOption(
                                selected = quality == VideoQualityMode.DATA_SAVER,
                                title = "Oszczędna",
                                description = "Do 360p/24 kl./s z adaptacją — płynniejsza w sieci komórkowej.",
                                onClick = { quality = VideoQualityMode.DATA_SAVER },
                            )
                            QualityOption(
                                selected = quality == VideoQualityMode.HIGH,
                                title = "Wysoka",
                                description = "Do 720p/30 kl./s — wymaga stabilnego łącza.",
                                onClick = { quality = VideoQualityMode.HIGH },
                            )
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Domyślna kamera", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (frontCamera) "Przednia" else "Tylna",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = frontCamera, onCheckedChange = { frontCamera = it })
                            }
                            Text(
                                "Głośnik nie wymaga osobnego uprawnienia. Wyjścia audio przełącza system Android. Aplikacja nie prosi o lokalizację ani dostęp do urządzeń w pobliżu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        SettingsSection("Gotowość telefonu") {
                            StatusLine("Kamera i mikrofon do rozmów na żywo", mediaPermissionsGranted)
                            Text("O zgodę poprosimy dopiero przy dzwonieniu lub odbieraniu. Rozmowy nie są zapisywane jako nagrania.", style = MaterialTheme.typography.bodySmall)
                            StatusLine("Powiadomienia", notificationPermissionGranted)
                            if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                TextButton(onClick = requestNotificationPermission) { Text("Włącz powiadomienia") }
                            }
                            StatusLine("Pełny ekran połączenia", fullScreenIntentAllowed)
                            if (!fullScreenIntentAllowed) {
                                TextButton(onClick = requestFullScreenIntent) { Text("Zezwól w systemie") }
                            }
                            val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                                ?.isIgnoringBatteryOptimizations(context.packageName) == true
                            StatusLine(
                                "Działanie w tle",
                                true,
                                if (batteryUnrestricted) "bez ograniczeń" else "FCM aktywne",
                            )
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            "package:${context.packageName}".toUri(),
                                        )
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "Nie udało się otworzyć ustawień systemowych", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Ustawienia systemowe aplikacji") }
                            Text(
                                "Po zamknięciu aplikacji telefon jest budzony przez powiadomienie FCM. Nie jest utrzymywane stałe powiadomienie czuwania.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        SettingsSection("Prywatność i bezpieczeństwo") {
                            StatusLine("Szyfrowanie transportu WebRTC", true)
                            StatusLine("Podpisywanie tożsamości kontaktów", true)
                            StatusLine("Blokada zrzutów w tajnym czacie", true)
                            StatusLine("Pliki bez bazy • SHA-256", true)
                            Text(
                                "Wiadomości nie są zapisywane w bazie i znikają 60 sekund po ich otwarciu. Pliki są przesyłane szyfrowanym kanałem WebRTC i weryfikowane przed zapisem. Dotknij kłódki w rozmowie lub czacie, aby porównać numer bezpieczeństwa. Złota kłódka oznacza tożsamość niepotwierdzoną ręcznie; zielona — potwierdzoną.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        SettingsSection("Stan usług") {
                            StatusLine(
                                "Sygnalizacja Cloudflare",
                                state.signalingConnected,
                                if (state.signalingConnected) "połączono" else "odnawianie",
                            )
                            StatusLine("Wybudzanie FCM", BuildConfig.FCM_ENABLED)
                            StatusLine("Przekaźnik TURN", BuildConfig.TURN_CREDENTIALS_URL.isNotBlank(), "automatyczny")
                            OutlinedButton(onClick = testConnection) { Text("Odśwież połączenie") }
                            state.chatSendError?.let {
                                Text("Czat: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            state.diagnostic?.let {
                                Text(
                                    "Ostatnia informacja: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    item {
                        SettingsSection("O aplikacji") {
                            Text("Wideocall ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Wariant: ${BuildConfig.BUILD_TYPE}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nUrządzenie: ${Build.MANUFACTURER} ${Build.MODEL}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Bez konta użytkownika. Identyfikator i ustawienia są przechowywane lokalnie na urządzeniu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Anuluj") }
                    Button(
                        onClick = { onSave(name.trim(), quality, frontCamera) },
                        enabled = name.isNotBlank(),
                    ) { Text("Zapisz") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun QualityOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String = if (ok) "gotowe" else "wymaga uwagi") {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hasMediaPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun requiredMediaPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
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

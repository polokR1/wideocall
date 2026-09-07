package pl.wideocall.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import pl.wideocall.app.data.Contact
import pl.wideocall.app.ui.theme.WideocallTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WideocallTheme {
                PermissionGate(viewModel)
            }
        }
    }
}

@Composable
private fun PermissionGate(viewModel: CallViewModel) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasMediaPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.CAMERA] == true &&
            results[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            launcher.launch(requiredPermissions())
        }
    }

    val state by viewModel.state.collectAsState()
    when (state.phase) {
        CallPhase.INCOMING -> IncomingCallScreen(state, viewModel, permissionsGranted) {
            launcher.launch(requiredPermissions())
        }
        CallPhase.OUTGOING,
        CallPhase.CONNECTING_CALL,
        CallPhase.IN_CALL -> VideoCallScreen(state, viewModel)
        else -> HomeScreen(state, viewModel, permissionsGranted) {
            launcher.launch(requiredPermissions())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: AppUiState,
    viewModel: CallViewModel,
    permissionsGranted: Boolean,
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
                    Text("Tylko rozmowy wideo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        callEnabled = state.phase == CallPhase.READY && permissionsGranted,
                        onCall = { viewModel.call(contact) },
                        onDelete = { viewModel.removeContact(contact.id) },
                    )
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onSave = { name, id ->
                viewModel.addContact(name, id)
                showAddContact = false
            },
        )
    }
    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSave = { name ->
                viewModel.saveSettings(name)
                showSettings = false
            },
        )
    }
}

@Composable
private fun StatusCard(state: AppUiState, onReconnect: () -> Unit) {
    val connected = state.phase == CallPhase.READY
    val dotColor = when (state.phase) {
        CallPhase.READY -> Color(0xFF3DDC97)
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
                Text(if (connected) "Dostępny" else "Połączenie z siecią", fontWeight = FontWeight.SemiBold)
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ContactCard(contact: Contact, callEnabled: Boolean, onCall: () -> Unit, onDelete: () -> Unit) {
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
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Usuń kontakt", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onCall, enabled = callEnabled) {
                Icon(Icons.Default.Videocam, "Zadzwoń", tint = if (callEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
        }
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp).background(background, CircleShape),
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size((size * .42f).dp))
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    val normalized = pl.wideocall.app.data.AppPreferences.normalizeId(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowy kontakt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nazwa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            Button(onClick = { onSave(name, normalized) }, enabled = normalized.length == 9) { Text("Dodaj") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun SettingsDialog(state: AppUiState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
                Text(
                    "Połączenia są zestawiane automatycznie przez Firebase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun hasMediaPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
}.toTypedArray()

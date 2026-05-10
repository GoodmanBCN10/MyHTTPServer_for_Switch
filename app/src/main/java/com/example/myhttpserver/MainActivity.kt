package com.example.myhttpserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myhttpserver.ui.theme.MyHTTPServerTheme

class MainActivity : ComponentActivity() {

    private val torrentsList = mutableStateListOf<TorrentStats>()

    private val torrentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "TORRENT_PROGRESS") {
                val statsBundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra("stats_list", Bundle::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra("stats_list")
                }
                
                statsBundles?.let { bundles ->
                    val newList = bundles.map { b ->
                        TorrentStats(
                            id = b.getString("id", ""),
                            name = b.getString("name", "Cargando..."),
                            progress = b.getFloat("progress", 0f),
                            downloadSpeed = b.getLong("speed", 0L),
                            peers = b.getInt("peers", 0),
                            seeds = b.getInt("seeds", 0),
                            dhtNodes = b.getInt("dht", 0),
                            state = b.getString("state", "")
                        )
                    }
                    torrentsList.clear()
                    torrentsList.addAll(newList)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        
        val filter = IntentFilter("TORRENT_PROGRESS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(torrentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(torrentReceiver, filter)
        }

        setContent {
            MyHTTPServerTheme {
                ServerScreen(
                    onStartServer = { uri -> startService(uri) },
                    onStopServer = { stopService() },
                    onSelectTorrentFile = { uri -> startTorrentFileDownload(uri) },
                    onRemoveTorrent = { id -> removeTorrent(id) },
                    torrents = torrentsList
                )
            }
        }
    }

    private fun startService(uri: Uri) {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "START"
            putExtra("directoryUri", uri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startTorrentFileDownload(uri: Uri) {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "START_TORRENT_FILE"
            putExtra("torrentUri", uri.toString())
        }
        startService(intent)
    }

    private fun removeTorrent(id: String) {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "REMOVE_TORRENT"
            putExtra("torrentId", id)
        }
        startService(intent)
    }

    private fun stopService() {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(torrentReceiver)
        super.onDestroy()
    }
}

@Composable
fun ServerScreen(
    onStartServer: (Uri) -> Unit, 
    onStopServer: () -> Unit,
    onSelectTorrentFile: (Uri) -> Unit,
    onRemoveTorrent: (String) -> Unit,
    torrents: List<TorrentStats>
) {
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("No conectado") }
    
    var hasAllFilesPermission by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    // Observador para detectar cuándo vuelves a la app y actualizar el permiso
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Escuchar cuando el usuario vuelve de la configuración de permisos
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
            selectedUri = uri
        }
    }

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onSelectTorrentFile(uri)
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            ipAddress = NetworkUtils.getLocalIpAddress() ?: "WiFi no disponible"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "Logo",
                modifier = Modifier.fillMaxWidth(0.85f).height(170.dp).padding(vertical = 4.dp),
                contentScale = ContentScale.Fit
            )

            if (!hasAllFilesPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF621B1B))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Faltan permisos", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Para descargar en carpetas públicas se necesita acceso total.", color = Color.LightGray, fontSize = 12.sp)
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            Text("Conceder Acceso")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Gestor Torrent", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            torrents.forEach { stats ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = stats.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(text = "[${stats.state}] Compañeros: ${stats.peers} | Nodos: ${stats.dhtNodes}", color = Color.Cyan, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (stats.progress < 0.1f) 0.05f else stats.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color.Cyan,
                                trackColor = Color.DarkGray,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "${String.format("%.1f", stats.progress)}%", color = Color.Gray, fontSize = 11.sp)
                                Text(text = "${stats.downloadSpeed / 1024} KB/s", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                    TextButton(onClick = { onRemoveTorrent(stats.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Quitar", color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { torrentFileLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("Descargar juego")
            }

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Color.Gray, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Switch HTTP Server", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(8.dp))

            if (isRunning) {
                Text(text = "Servidor Activo", color = Color.Green, fontWeight = FontWeight.Medium)
                Text(text = "http://$ipAddress:8080", color = Color.Cyan, fontSize = 18.sp)
            } else {
                Text(text = "Servidor Inactivo", color = Color.Red, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = { launcher.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Text(text = if (selectedUri == null) "1. Seleccionar Carpeta SD" else "Carpeta Lista")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isRunning) {
                        onStopServer()
                        isRunning = false
                    } else {
                        selectedUri?.let {
                            onStartServer(it)
                            isRunning = true
                        }
                    }
                },
                enabled = selectedUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color.Red else Color(0xFF4CAF50))
            ) {
                Text(text = if (isRunning) "2. Detener Servidor" else "2. Iniciar Servidor")
            }

            Spacer(modifier = Modifier.height(48.dp))
            HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Apoya el proyecto",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.me/GoodmanBCN"))
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0070BA)),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text("Donar con PayPal", color = Color.White)
            }
        }
    }
}

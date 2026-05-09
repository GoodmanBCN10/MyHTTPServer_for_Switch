package com.example.myhttpserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.myhttpserver.ui.theme.MyHTTPServerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            MyHTTPServerTheme {
                ServerScreen(
                    onStartServer = { uri -> startService(uri) },
                    onStopServer = { stopService() },
                    onStartTorrent = { magnet -> startTorrentDownload(magnet) }
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

    private fun startTorrentDownload(magnet: String) {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "START_TORRENT"
            putExtra("magnetUri", magnet)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(this, ServerService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }
}

@Composable
fun ServerScreen(
    onStartServer: (Uri) -> Unit, 
    onStopServer: () -> Unit,
    onStartTorrent: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("No conectado") }
    var magnetUri by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            selectedUri = uri
        }
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            ipAddress = NetworkUtils.getLocalIpAddress() ?: "WiFi no disponible"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "Logo",
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(170.dp)
                    .padding(vertical = 4.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Switch HTTP Server + Torrent",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isRunning) {
                Text(text = "Servidor Activo", color = Color.Green, fontWeight = FontWeight.Medium)
                Text(text = "http://$ipAddress:8080", color = Color.Cyan, fontSize = 18.sp)
            } else {
                Text(text = "Servidor Inactivo", color = Color.Red, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color.Red else Color(0xFF4CAF50)
                )
            ) {
                Text(text = if (isRunning) "2. Iniciar Servidor" else "2. Iniciar Servidor")
            }

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider(color = Color.Gray, thickness = 0.5.dp)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Gestor Torrent",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = magnetUri,
                onValueChange = { magnetUri = it },
                label = { Text("Pegar enlace Magnet", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Cyan,
                    unfocusedBorderColor = Color.Gray
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onStartTorrent(magnetUri)
                    magnetUri = ""
                },
                enabled = magnetUri.startsWith("magnet:"),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("Descargar Juego")
            }
        }
    }
}

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
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myhttpserver.ui.theme.MyHTTPServerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var model: MainViewModel

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
                            name = b.getString("name", "Loading..."),
                            progress = b.getFloat("progress", 0f),
                            downloadSpeed = b.getLong("speed", 0L),
                            peers = b.getInt("peers", 0),
                            seeds = b.getInt("seeds", 0),
                            dhtNodes = b.getInt("dht", 0),
                            state = b.getString("state", "")
                        )
                    }
                    model.torrentsList.clear()
                    model.torrentsList.addAll(newList)
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
            model = viewModel()
            MyHTTPServerTheme {
                MainContent(
                    viewModel = model,
                    onStartServer = { uri -> startServer(uri) },
                    onStopServer = { stopServer() },
                    onSelectTorrentFile = { uri -> startTorrentFileDownload(uri) },
                    onRemoveTorrent = { id -> removeTorrent(id) }
                )
            }
        }
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data: Uri? = intent?.data
        if (Intent.ACTION_VIEW == action && data != null) {
            startTorrentFileDownload(data)
            model.currentView = MainViewModel.ViewType.TORRENT
        }
    }

    private fun startServer(uri: Uri) {
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
            putExtra("directoryUri", model.selectedUri.toString())
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

    private fun stopServer() {
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
fun MainContent(
    viewModel: MainViewModel,
    onStartServer: (Uri) -> Unit,
    onStopServer: () -> Unit,
    onSelectTorrentFile: (Uri) -> Unit,
    onRemoveTorrent: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var hasAllFilesPermission by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

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

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Mostrar diálogo de idioma si no hay uno seleccionado
        if (viewModel.selectedLanguage == null) {
            viewModel.showLanguageDialog = true
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {}
            viewModel.selectedUri = uri
        }
    }

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onSelectTorrentFile(uri)
    }

    val zipPartsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.selectedZipParts = uris.sortedBy { getFileName(context, it) }
            viewModel.decompressionStatus = "${viewModel.selectedZipParts.size} parts selected"
        }
    }

    BackHandler(enabled = viewModel.currentView != MainViewModel.ViewType.DASHBOARD) {
        viewModel.currentView = MainViewModel.ViewType.DASHBOARD
    }

    LaunchedEffect(viewModel.isServerRunning) {
        if (viewModel.isServerRunning) {
            viewModel.ipAddress = NetworkUtils.getLocalIpAddress() ?: "WiFi unavailable"
        }
    }

    if (viewModel.showLanguageDialog) {
        LanguageSelectionDialog(
            onLanguageSelected = { 
                viewModel.updateLanguage(it)
                viewModel.showLanguageDialog = false
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Content based on view type
            when (viewModel.currentView) {
                MainViewModel.ViewType.DASHBOARD -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!hasAllFilesPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            PermissionCard(viewModel, context)
                        }
                        DashboardScreen(
                            viewModel = viewModel,
                            onNavigate = { viewModel.currentView = it }
                        )
                    }
                }
                MainViewModel.ViewType.TORRENT -> {
                    TorrentSection(
                        viewModel = viewModel,
                        onBack = { viewModel.currentView = MainViewModel.ViewType.DASHBOARD },
                        onSelectFolder = { folderLauncher.launch(null) },
                        onRemoveTorrent = onRemoveTorrent,
                        onSelectFile = { torrentFileLauncher.launch("*/*") }
                    )
                }
                MainViewModel.ViewType.DECOMPRESSOR -> {
                    DecompressorSection(
                        viewModel = viewModel,
                        onBack = { viewModel.currentView = MainViewModel.ViewType.DASHBOARD },
                        onSelectFolder = { folderLauncher.launch(null) },
                        onSelectParts = { zipPartsLauncher.launch(arrayOf("*/*")) },
                        onDecompress = {
                            scope.launch {
                                viewModel.isDecompressing = true
                                viewModel.decompressionStatus = "Decompressing..."
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        Decompressor.decompress(context, viewModel.selectedZipParts, viewModel.selectedUri!!) { progress ->
                                            viewModel.decompressionStatus = progress
                                        }
                                    }
                                    viewModel.extractedZipFiles = result
                                    viewModel.decompressionStatus = viewModel.getString("extraction_success")
                                } catch (e: Exception) {
                                    viewModel.decompressionStatus = "Error: ${e.message}"
                                } finally {
                                    viewModel.isDecompressing = false
                                }
                            }
                        }
                    )
                }
                MainViewModel.ViewType.SERVER -> {
                    ServerScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.currentView = MainViewModel.ViewType.DASHBOARD },
                        onSelectFolder = { folderLauncher.launch(null) },
                        onStartServer = onStartServer,
                        onStopServer = onStopServer
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSelectionDialog(onLanguageSelected: (Language) -> Unit) {
    Dialog(onDismissRequest = {}) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Select Language", 
                    color = Color.White, 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                Language.values().forEach { lang ->
                    Button(
                        onClick = { onLanguageSelected(lang) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text(lang.displayName, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: MainViewModel, onNavigate: (MainViewModel.ViewType) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { viewModel.showLanguageDialog = true }) {
                Text(viewModel.getString("language_selector"), color = Color.Cyan)
            }
        }

        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "Logo",
            modifier = Modifier.size(120.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "My HTTP Server Tool",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "by GoodmanBCN",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        DashboardCard(
            title = viewModel.getString("dashboard_torrent_title"),
            subtitle = viewModel.getString("dashboard_torrent_subtitle"),
            color = Color(0xFF2196F3),
            onClick = { onNavigate(MainViewModel.ViewType.TORRENT) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        DashboardCard(
            title = viewModel.getString("dashboard_decompressor_title"),
            subtitle = viewModel.getString("dashboard_decompressor_subtitle"),
            color = Color(0xFF4CAF50),
            onClick = { onNavigate(MainViewModel.ViewType.DECOMPRESSOR) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        DashboardCard(
            title = viewModel.getString("dashboard_server_title"),
            subtitle = viewModel.getString("dashboard_server_subtitle"),
            statusText = if (viewModel.isServerRunning) viewModel.getString("dashboard_server_active") else null,
            color = Color(0xFF9C27B0),
            onClick = { onNavigate(MainViewModel.ViewType.SERVER) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        DonationSection(viewModel, context)
        
        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
fun DashboardCard(title: String, subtitle: String, color: Color, statusText: String? = null, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(subtitle, color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
                if (statusText != null) {
                    Text(statusText, color = Color(0xFFE60012), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PermissionCard(viewModel: MainViewModel, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF621B1B))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(viewModel.getString("permission_missing"), color = Color.White, fontWeight = FontWeight.Bold)
            Text(viewModel.getString("permission_description"), color = Color.LightGray, fontSize = 12.sp)
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text(viewModel.getString("grant_access"))
            }
        }
    }
}

@Composable
fun TorrentSection(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSelectFolder: () -> Unit,
    onRemoveTorrent: (String) -> Unit,
    onSelectFile: () -> Unit
) {
    val context = LocalContext.current
    val switchRed = Color(0xFFE60012)
    val switchBlue = Color(0xFF2196F3)

    Column(modifier = Modifier.fillMaxSize()) {
        // Botón Volver arriba
        Text(
            viewModel.getString("back_button"), 
            color = Color.Cyan, 
            modifier = Modifier.clickable { onBack() }.padding(vertical = 16.dp).padding(horizontal = 16.dp).align(Alignment.Start)
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                Text(
                    text = viewModel.getString("dashboard_torrent_title").substringAfter(". "),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(modifier = Modifier.width(100.dp), thickness = 2.dp, color = switchRed)
            }

            Column(modifier = Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(viewModel.getString("torrent_file"), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSelectFile,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = viewModel.selectedUri != null,
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.selectedUri != null) switchBlue else Color.Gray),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (viewModel.selectedUri == null) viewModel.getString("select_folder_first") else viewModel.getString("select_torrent"), maxLines = 1)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(viewModel.getString("destination_folder"), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = switchBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    val folderName = if (viewModel.selectedUri == null) viewModel.getString("select_folder") else getFileName(context, viewModel.selectedUri!!)
                    Text(text = folderName, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(16.dp))

            Text(viewModel.getString("active_downloads"), color = Color.White, fontWeight = FontWeight.Bold)

            viewModel.torrentsList.forEach { stats ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = stats.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(text = "[${stats.state}] Peers: ${stats.peers} | Nodes: ${stats.dhtNodes}", color = Color.Cyan, fontSize = 11.sp)
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
                        Text(viewModel.getString("remove_torrent"), color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DecompressorSection(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectParts: () -> Unit,
    onDecompress: () -> Unit
) {
    val context = LocalContext.current
    val switchRed = Color(0xFFE60012)
    val switchBlue = Color(0xFF2196F3)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            viewModel.getString("back_button"), 
            color = Color.Cyan, 
            modifier = Modifier.clickable { onBack() }.padding(vertical = 16.dp).padding(horizontal = 16.dp).align(Alignment.Start)
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                Text(
                    text = viewModel.getString("dashboard_decompressor_title").substringAfter(". "),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(modifier = Modifier.width(100.dp), thickness = 2.dp, color = switchRed)
            }

            Column(modifier = Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(viewModel.getString("destination_folder"), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = switchBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    val folderName = if (viewModel.selectedUri == null) viewModel.getString("select_folder") else getFileName(context, viewModel.selectedUri!!)
                    Text(text = folderName, maxLines = 1)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(viewModel.getString("decompress_files"), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onSelectParts,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = viewModel.selectedUri != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (viewModel.selectedUri == null) viewModel.getString("select_folder_first") else viewModel.getString("select_zip"), maxLines = 1)
                }
            }
            
            if (viewModel.selectedZipParts.isNotEmpty()) {
                Text(text = viewModel.decompressionStatus, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                
                if (!viewModel.isDecompressing && viewModel.extractedZipFiles.isEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDecompress, 
                        modifier = Modifier.fillMaxWidth(0.8f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(viewModel.getString("start_decompression"), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            if (viewModel.isDecompressing) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = Color.Cyan)
            }
            
            if (viewModel.extractedZipFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = viewModel.getString("extraction_success"), color = Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ServerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSelectFolder: () -> Unit,
    onStartServer: (Uri) -> Unit,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val switchRed = Color(0xFFE60012)
    val switchBlue = Color(0xFF2196F3)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            viewModel.getString("back_button"), 
            color = Color.Cyan, 
            modifier = Modifier.clickable { onBack() }.padding(vertical = 16.dp).padding(horizontal = 16.dp).align(Alignment.Start)
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                Text(
                    text = viewModel.getString("dashboard_server_title").substringAfter(". "),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(modifier = Modifier.width(100.dp), thickness = 2.dp, color = switchRed)
            }

            Card(
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (viewModel.isServerRunning) {
                        Text(text = viewModel.getString("server_active_status"), color = Color.Green, fontSize = 16.sp)
                        Text(text = "http://${viewModel.ipAddress}:8080", color = Color.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(text = viewModel.getString("server_inactive_status"), color = Color.Red, fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = switchBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    val folderName = if (viewModel.selectedUri == null) viewModel.getString("select_folder") else getFileName(context, viewModel.selectedUri!!)
                    Text(text = folderName, maxLines = 1)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (viewModel.isServerRunning) {
                            onStopServer()
                            viewModel.isServerRunning = false
                        } else {
                            viewModel.selectedUri?.let {
                                onStartServer(it)
                                viewModel.isServerRunning = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = viewModel.selectedUri != null,
                    colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isServerRunning) Color.Red else Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = if (viewModel.isServerRunning) viewModel.getString("stop_server") else viewModel.getString("start_server"))
                }
            }
        }
    }
}

@Composable
fun DonationSection(viewModel: MainViewModel, context: Context) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.me/GoodmanBCN"))
                context.startActivity(intent)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0070BA)),
            modifier = Modifier.height(44.dp),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 32.dp)
        ) {
            Text(viewModel.getString("donation_button"), color = Color.White, fontSize = 14.sp)
        }
    }
}

fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "Unknown"
    try {
        if (uri.toString().contains("/tree/")) {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name?.let {
                name = it
            }
        } else if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } else {
            name = uri.path?.substringAfterLast('/') ?: "Unknown"
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return name
}

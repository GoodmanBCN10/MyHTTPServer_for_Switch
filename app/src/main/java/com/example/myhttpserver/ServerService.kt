package com.example.myhttpserver

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.util.Log
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File

class ServerService : Service() {
    private var switchServer: SwitchServer? = null
    private var torrentManager: TorrentManager? = null
    private var currentDirectoryUri: Uri? = null
    private val CHANNEL_ID = "SwitchServerChannel"
    private val NOTIFICATION_ID = 1
    
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        switchServer = SwitchServer(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "START" -> {
                val uriString = intent.getStringExtra("directoryUri")
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    currentDirectoryUri = uri
                    switchServer?.start(uri)
                    startForeground(NOTIFICATION_ID, createNotification("Servidor Activo", "Sirviendo archivos"))
                }
            }
            "STOP" -> stopAll()
            "START_TORRENT_FILE" -> {
                val uriString = intent.getStringExtra("torrentUri")
                if (uriString != null) {
                    ensureTorrentManager()
                    try {
                        val uri = Uri.parse(uriString)
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val torrentInfo = TorrentInfo.bdecode(bytes)
                            torrentManager?.downloadTorrent(torrentInfo)
                            
                            // Asegurar que solo hay un loop de progreso
                            handler.removeCallbacks(progressRunnable)
                            handler.post(progressRunnable)
                        }
                    } catch (e: Exception) {
                        Log.e("ServerService", "Error cargando torrent", e)
                    }
                }
            }
            "REMOVE_TORRENT" -> {
                val id = intent.getStringExtra("torrentId")
                if (id != null) {
                    torrentManager?.removeTorrent(id)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureTorrentManager() {
        if (torrentManager == null) {
            val downloadDir = getDownloadDirectory()
            torrentManager = TorrentManager(downloadDir)
        }
    }

    private fun getDownloadDirectory(): File {
        // Carpeta pública de descargas
        val publicDownloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        
        // Intentar usar Download pública, si no, usar la interna de la app
        return if (publicDownloadDir.exists() && publicDownloadDir.canWrite()) {
            publicDownloadDir
        } else {
            getExternalFilesDir("downloads") ?: filesDir
        }
    }

    private fun updateProgress() {
        val allStats = torrentManager?.getAllStats() ?: emptyList()
        val statsBundles = ArrayList<Bundle>()
        
        allStats.forEach { stats ->
            statsBundles.add(Bundle().apply {
                putString("id", stats.id)
                putString("name", stats.name)
                putFloat("progress", stats.progress)
                putLong("speed", stats.downloadSpeed)
                putInt("peers", stats.peers)
                putInt("seeds", stats.seeds)
                putInt("dht", stats.dhtNodes)
                putString("state", stats.state)
            })
        }

        val intent = Intent("TORRENT_PROGRESS").apply {
            setPackage(packageName)
            putParcelableArrayListExtra("stats_list", statsBundles)
        }
        sendBroadcast(intent)

        if (allStats.isNotEmpty()) {
            val totalSpeed = allStats.sumOf { it.downloadSpeed } / 1024
            val content = "Bajando ${allStats.size} juegos (${totalSpeed} KB/s)"
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification("Gestor Torrent", content))
        }
    }

    private fun stopAll() {
        handler.removeCallbacks(progressRunnable)
        switchServer?.stop()
        torrentManager?.stop()
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "MyServerChannel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}

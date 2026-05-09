package com.example.myhttpserver

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class ServerService : Service() {
    private var switchServer: SwitchServer? = null
    private var torrentManager: TorrentManager? = null
    private val CHANNEL_ID = "SwitchServerChannel"
    private val NOTIFICATION_ID = 1
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
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
                    acquireLocks()
                    val uri = Uri.parse(uriString)
                    switchServer?.start(uri)
                    startForeground(NOTIFICATION_ID, createNotification("Servidor Activo", "Sirviendo archivos a la Switch"))
                }
            }
            "STOP" -> {
                stopAll()
            }
            "START_TORRENT" -> {
                val magnetUri = intent.getStringExtra("magnetUri")
                if (magnetUri != null) {
                    acquireLocks()
                    if (torrentManager == null) {
                        val downloadDir = getExternalFilesDir("downloads") ?: filesDir
                        torrentManager = TorrentManager(downloadDir)
                    }
                    torrentManager?.downloadMagnet(magnetUri)
                    handler.post(progressRunnable)
                    startForeground(NOTIFICATION_ID, createNotification("Descargando Torrent", "Iniciando descarga..."))
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun updateProgress() {
        val stats = torrentManager?.getStats()
        if (stats != null) {
            // Enviar broadcast a la Activity
            val intent = Intent("TORRENT_PROGRESS")
            intent.putExtra("progress", stats.progress)
            intent.putExtra("speed", stats.downloadSpeed)
            intent.putExtra("name", stats.name)
            sendBroadcast(intent)

            // Actualizar notificación
            val speedKb = stats.downloadSpeed / 1024
            val content = "${stats.name} - ${stats.progress.toInt()}% (${speedKb} KB/s)"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification("Descargando Juego", content))
        }
    }

    private fun stopAll() {
        handler.removeCallbacks(progressRunnable)
        switchServer?.stop()
        torrentManager?.stop()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwitchServer::WakeLock").apply {
                acquire()
            }
        }

        if (wifiLock == null) {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SwitchServer::WifiLock").apply {
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Switch Server Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}

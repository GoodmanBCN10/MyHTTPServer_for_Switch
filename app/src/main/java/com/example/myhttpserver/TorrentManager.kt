package com.example.myhttpserver

import android.util.Log
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert
import java.io.File

class TorrentManager(private val downloadDir: File) {
    private val session = SessionManager()
    private val TAG = "TorrentManager"

    interface TorrentListener {
        fun onProgress(progress: Float, speed: Long)
        fun onFinished(file: File)
        fun onError(message: String)
    }

    private var listener: TorrentListener? = null

    init {
        // Iniciar la sesión de libtorrent
        session.start()
        
        // Listener para capturar eventos (alertas) de la sesión
        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null // Escuchar todas las alertas

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.TORRENT_FINISHED -> {
                        val torrentAlert = alert as TorrentFinishedAlert
                        Log.d(TAG, "Descarga finalizada: ${torrentAlert.torrentName()}")
                        listener?.onFinished(downloadDir)
                    }
                    AlertType.TORRENT_ERROR -> {
                        Log.e(TAG, "Error en el torrent: ${alert.toString()}")
                        listener?.onError(alert.toString())
                    }
                    AlertType.TRACKER_ERROR -> {
                        Log.w(TAG, "Error de tracker: ${alert.toString()}")
                    }
                    else -> {
                        // Podemos monitorear el progreso aquí o con un timer
                    }
                }
            }
        })
    }

    fun setListener(listener: TorrentListener) {
        this.listener = listener
    }

    /**
     * Inicia la descarga de un enlace Magnet
     */
    fun downloadMagnet(magnetUri: String) {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        
        try {
            Log.d(TAG, "Iniciando descarga de magnet: $magnetUri")
            session.download(magnetUri, downloadDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar descarga de magnet", e)
            listener?.onError(e.message ?: "Error desconocido")
        }
    }

    /**
     * Obtiene estadísticas actuales del primer torrent activo
     */
    fun getStats(): TorrentStats? {
        val torrents = session.swig().get_torrents()
        if (torrents == null || torrents.empty()) return null
        
        val handle = TorrentHandle(torrents.get(0))
        val status = handle.status()
        
        return TorrentStats(
            progress = status.progress() * 100,
            downloadSpeed = status.downloadPayloadRate().toLong(),
            name = status.name()
        )
    }

    fun stop() {
        session.stop()
    }
}

data class TorrentStats(
    val progress: Float, 
    val downloadSpeed: Long,
    val name: String
)

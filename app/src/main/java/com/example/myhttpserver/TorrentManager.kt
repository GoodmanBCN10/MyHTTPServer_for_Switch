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
        val settings = SettingsPack()
        
        // Identidad confiable
        settings.setString(com.frostwire.jlibtorrent.swig.settings_pack.string_types.user_agent.swigValue(), "qBittorrent/4.5.2")
        
        // Red y Puertos
        settings.listenInterfaces("0.0.0.0:6881")
        settings.enableDht(true)
        
        session.start(SessionParams(settings))
        
        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null

            override fun alert(alert: Alert<*>) {
                val type = alert.type()
                
                when (type) {
                    AlertType.TORRENT_FINISHED -> {
                        Log.d(TAG, "Descarga finalizada")
                        listener?.onFinished(downloadDir)
                    }
                    AlertType.TORRENT_ERROR -> {
                        Log.e(TAG, "Error en torrent: ${alert.swig().message()}")
                        listener?.onError(alert.swig().message())
                    }
                    else -> {}
                }
            }
        })
    }

    fun setListener(listener: TorrentListener) {
        this.listener = listener
    }

    fun downloadTorrent(torrentInfo: TorrentInfo) {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        try {
            Log.d(TAG, "Iniciando torrent: ${torrentInfo.name()}")
            session.download(torrentInfo, downloadDir)
            session.swig().post_torrent_updates()
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar", e)
        }
    }

    fun downloadMagnet(magnetUri: String) {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        try {
            session.download(magnetUri, downloadDir)
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar magnet", e)
        }
    }

    fun getStats(): TorrentStats? {
        val torrents = session.swig().get_torrents()
        if (torrents == null || torrents.empty()) return null
        
        val handle = TorrentHandle(torrents.get(0))
        val status = handle.status()
        
        // Solo forzar re-anuncio si realmente estamos buscando
        if (status.numPeers() == 0 && status.progress() < 1.0) {
            handle.forceReannounce()
        }
        
        return TorrentStats(
            progress = status.progress() * 100,
            downloadSpeed = status.downloadPayloadRate().toLong(),
            name = if (status.name().isEmpty()) "Cargando..." else status.name(),
            peers = status.numPeers(),
            seeds = status.numSeeds()
        )
    }

    fun stop() {
        session.stop()
    }
}

data class TorrentStats(
    val progress: Float, 
    val downloadSpeed: Long,
    val name: String,
    val peers: Int = 0,
    val seeds: Int = 0
)

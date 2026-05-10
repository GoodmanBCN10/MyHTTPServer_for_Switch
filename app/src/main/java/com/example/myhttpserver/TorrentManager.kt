package com.example.myhttpserver

import android.util.Log
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.swig.settings_pack
import java.io.File

class TorrentManager(private val downloadDir: File) {
    private val session = SessionManager()
    private val TAG = "TorrentManager"

    init {
        val settings = SettingsPack()
        settings.setString(settings_pack.string_types.user_agent.swigValue(), "uTorrent/3550(45507)")
        settings.listenInterfaces("0.0.0.0:6881")
        settings.enableDht(true)
        
        // Límites globales para múltiples descargas
        settings.activeLimit(20)
        settings.activeDownloads(10)
        settings.connectionsLimit(500)
        
        session.start(SessionParams(settings))
    }

    fun downloadTorrent(torrentInfo: TorrentInfo) {
        if (!downloadDir.exists()) downloadDir.mkdirs()
        try {
            Log.d(TAG, "Añadiendo torrent a la cola: ${torrentInfo.name()}")
            session.download(torrentInfo, downloadDir)
            
            // Forzar anuncio inicial para el nuevo torrent
            val torrents = session.swig().get_torrents()
            if (!torrents.empty()) {
                val lastHandle = TorrentHandle(torrents.get(torrents.size().toInt() - 1))
                lastHandle.resume()
                lastHandle.forceReannounce()
            }
            session.swig().post_torrent_updates()
        } catch (e: Exception) {
            Log.e(TAG, "Error al añadir torrent", e)
        }
    }

    /**
     * Obtiene estadísticas de TODOS los torrents activos
     */
    fun getAllStats(): List<TorrentStats> {
        val statsList = mutableListOf<TorrentStats>()
        val torrents = session.swig().get_torrents()
        
        if (torrents == null || torrents.empty()) return statsList

        val dhtNodes = session.stats().dhtNodes().toInt()

        for (i in 0 until torrents.size().toInt()) {
            val handle = TorrentHandle(torrents.get(i))
            val status = handle.status()
            
            val name = if (status.name().isNullOrEmpty()) {
                if (handle.name().isNullOrEmpty()) "Descargando..." else handle.name()
            } else {
                status.name()
            }

            statsList.add(TorrentStats(
                id = handle.infoHash().toString(),
                progress = status.progress() * 100,
                downloadSpeed = status.downloadPayloadRate().toLong(),
                name = name,
                peers = status.numPeers(),
                seeds = status.numSeeds(),
                dhtNodes = dhtNodes,
                state = status.state().toString()
            ))
            
            // Auto-reanuncio si no hay peers
            if (status.numPeers() == 0 && status.progress() < 1.0) {
                handle.forceReannounce()
            }
        }
        
        return statsList
    }

    fun stop() {
        session.stop()
    }

    fun removeTorrent(id: String) {
        val torrents = session.swig().get_torrents()
        if (torrents == null || torrents.empty()) return

        for (i in 0 until torrents.size().toInt()) {
            val handle = TorrentHandle(torrents.get(i))
            if (handle.infoHash().toString() == id) {
                session.remove(handle)
                break
            }
        }
    }
}

data class TorrentStats(
    val id: String,
    val progress: Float, 
    val downloadSpeed: Long,
    val name: String,
    val peers: Int = 0,
    val seeds: Int = 0,
    val dhtNodes: Int = 0,
    val state: String = ""
)

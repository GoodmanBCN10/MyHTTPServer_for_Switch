package com.example.myhttpserver

import android.util.Log
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.swig.settings_pack
import com.frostwire.jlibtorrent.swig.torrent_handle_vector
import java.io.File

class TorrentManager(private val downloadDir: File) {
    private val TAG = "TorrentManager"
    private val session = SessionManager()

    init {
        setupSession()
    }

    private fun setupSession() {
        Log.d(TAG, "Configurando sesión de Torrent...")
        val settings = SettingsPack()
        
        // Identidad del cliente
        settings.setString(settings_pack.string_types.user_agent.swigValue(), "qBittorrent/4.5.2")
        
        // RED: Escuchar en todas las interfaces. Puerto 0 deja que el sistema asigne uno libre.
        // Esto es CRUCIAL para que el tráfico fluya.
        settings.listenInterfaces("0.0.0.0:0,[::]:0")
        
        // Optimización para Android (evitar agotar recursos)
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 10)
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 5)
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), 20)
        
        // Priorizar descargas y asegurar que el motor no se duerma
        settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        
        // Protocolos de Red (Habilitar todos para máxima conectividad)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        
        // Configurar máscara de alertas para diagnóstico
        settings.setInteger(settings_pack.int_types.alert_mask.swigValue(), 0xFFFFFFFF.toInt())

        session.addListener(object : AlertListener {
            override fun types(): IntArray? = null 

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.LISTEN_SUCCEEDED -> Log.i(TAG, "Escuchando en puerto: ${alert.toString()}")
                    AlertType.LISTEN_FAILED -> Log.w(TAG, "Fallo al abrir puerto: ${alert.toString()}")
                    AlertType.ADD_TORRENT -> Log.i(TAG, "Torrent añadido con éxito")
                    AlertType.METADATA_RECEIVED -> Log.i(TAG, "Metadatos recibidos")
                    AlertType.TORRENT_FINISHED -> Log.i(TAG, "Descarga completada")
                    AlertType.PEER_CONNECT -> Log.v(TAG, "Conectado a un peer")
                    else -> {}
                }
            }
        })

        session.start(SessionParams(settings))
        session.startDht()
        Log.i(TAG, "Sesión Torrent lista. Directorio: ${downloadDir.absolutePath}")
    }

    fun downloadTorrent(torrentInfo: TorrentInfo, customDir: File? = null) {
        val targetDir = customDir ?: downloadDir
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            Log.d(TAG, "Creando carpeta de descarga: $created")
        }

        Log.i(TAG, "Iniciando descarga de: ${torrentInfo.name()} en ${targetDir.absolutePath}")
        
        // Trackers públicos para asegurar conectividad
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:80/announce",
            "udp://9.rarbg.com:2810/announce",
            "udp://exodus.desync.com:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"
        )
        
        trackers.forEach { torrentInfo.addTracker(it) }

        try {
            session.download(torrentInfo, targetDir)
            
            // Forzar despertar el motor para el torrent específico añadido
            val handle = session.find(torrentInfo.infoHash())
            handle?.apply {
                resume()
                forceReannounce()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fatal al añadir torrent", e)
        }
    }

    fun getAllStats(): List<TorrentStats> {
        val statsList = mutableListOf<TorrentStats>()
        val swigTorrents = session.swig().get_torrents()
        
        if (swigTorrents == null || swigTorrents.empty()) return statsList

        val dhtNodes = session.stats().dhtNodes().toInt()

        for (i in 0 until swigTorrents.size().toInt()) {
            val handle = TorrentHandle(swigTorrents.get(i))
            val status = handle.status()
            
            // Si no tiene nombre aún (está bajando metadatos), usar un placeholder o el hash
            val name = if (status.name().isNullOrEmpty()) {
                if (handle.name().isNullOrEmpty()) "Obteniendo info..." else handle.name()
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
                state = translateState(status.state())
            ))
            
            // Empujar el anuncio si no hay nadie conectado
            if (status.numPeers() == 0 && status.progress() < 1.0) {
                handle.forceReannounce()
            }
        }
        
        return statsList
    }

    private fun translateState(state: TorrentStatus.State): String {
        return when (state) {
            TorrentStatus.State.CHECKING_FILES -> "Verificando"
            TorrentStatus.State.DOWNLOADING_METADATA -> "Buscando info"
            TorrentStatus.State.DOWNLOADING -> "Descargando"
            TorrentStatus.State.FINISHED -> "Completado"
            TorrentStatus.State.SEEDING -> "Compartiendo"
            TorrentStatus.State.CHECKING_RESUME_DATA -> "Resumiendo"
            else -> "En espera"
        }
    }

    fun stop() {
        Log.i(TAG, "Apagando gestor de torrents")
        session.stop()
    }

    fun removeTorrent(id: String) {
        val swigTorrents = session.swig().get_torrents()
        if (swigTorrents == null || swigTorrents.empty()) return

        for (i in 0 until swigTorrents.size().toInt()) {
            val handle = TorrentHandle(swigTorrents.get(i))
            if (handle.infoHash().toString() == id) {
                Log.i(TAG, "Eliminando torrent: ${handle.name()}")
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
    val peers: Int,
    val seeds: Int,
    val dhtNodes: Int,
    val state: String
)

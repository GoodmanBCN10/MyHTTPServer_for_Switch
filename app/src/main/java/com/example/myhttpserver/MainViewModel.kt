package com.example.myhttpserver

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // Shared State
    var selectedUri by mutableStateOf<Uri?>(null)
    
    // Server State
    var isServerRunning by mutableStateOf(false)
    var ipAddress by mutableStateOf("No conectado")
    
    // Torrent State
    val torrentsList = mutableStateListOf<TorrentStats>()
    
    // Decompressor State
    var selectedZipParts by mutableStateOf<List<Uri>>(emptyList())
    var extractedZipFiles by mutableStateOf<List<Uri>>(emptyList())
    var decompressionStatus by mutableStateOf("Selecciona archivos .zip o partes .7z")
    var isDecompressing by mutableStateOf(false)
    
    // UI Navigation State
    var currentView by mutableStateOf(ViewType.TORRENT)
    
    enum class ViewType {
        TORRENT, DECOMPRESSOR
    }
}

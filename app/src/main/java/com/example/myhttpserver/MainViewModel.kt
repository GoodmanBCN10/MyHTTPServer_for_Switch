package com.example.myhttpserver

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

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
    var currentView by mutableStateOf(ViewType.DASHBOARD)
    
    // Language State
    var selectedLanguage by mutableStateOf<Language?>(null)
    var showLanguageDialog by mutableStateOf(false)

    init {
        val savedLang = prefs.getString("language", null)
        if (savedLang != null) {
            try {
                selectedLanguage = Language.valueOf(savedLang)
            } catch (e: Exception) {
                selectedLanguage = null
            }
        }
    }

    fun updateLanguage(language: Language) {
        selectedLanguage = language
        prefs.edit().putString("language", language.name).apply()
    }

    fun getString(key: String): String {
        return Translations.get(key, selectedLanguage ?: Language.ENGLISH)
    }
    
    enum class ViewType {
        DASHBOARD, TORRENT, DECOMPRESSOR, SERVER
    }
}

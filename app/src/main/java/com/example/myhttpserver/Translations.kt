package com.example.myhttpserver

enum class Language(val displayName: String, val code: String) {
    SPANISH("Español", "es"),
    ENGLISH("English", "en")
}

object Translations {
    private val strings = mapOf(
        Language.SPANISH to mapOf(
            "dashboard_torrent_title" to "1. Gestor Torrent",
            "dashboard_torrent_subtitle" to "Descarga juegos .torrent",
            "dashboard_decompressor_title" to "2. Descompresor",
            "dashboard_decompressor_subtitle" to "Extraer archivos .zip y .7z por partes",
            "dashboard_server_title" to "3. Servidor HTTP",
            "dashboard_server_subtitle" to "Iniciar servidor para la Switch",
            "dashboard_server_active" to "Servidor activo",
            "donation_button" to "Apoyar Proyecto (PayPal)",
            "back_button" to "< Volver",
            "select_folder" to "Seleccionar Carpeta",
            "select_torrent" to "Seleccionar archivo .torrent",
            "select_zip" to "Seleccionar .zip o .7z",
            "destination_folder" to "Carpeta de Destino",
            "torrent_file" to "Archivo Torrent",
            "decompress_files" to "Archivos a Descomprimir",
            "active_downloads" to "Descargas Activas",
            "start_decompression" to "INICIAR DESCOMPRESIÓN",
            "extraction_success" to "¡Archivos extraídos con éxito!",
            "server_active_status" to "Servidor Activo",
            "server_inactive_status" to "Servidor Inactivo",
            "stop_server" to "Detener Servidor",
            "start_server" to "Iniciar Servidor",
            "select_folder_first" to "Selecciona carpeta primero",
            "permission_missing" to "Faltan permisos",
            "permission_description" to "Se necesita acceso total a los archivos.",
            "grant_access" to "Conceder Acceso",
            "language_selector" to "Idioma",
            "remove_torrent" to "Quitar"
        ),
        Language.ENGLISH to mapOf(
            "dashboard_torrent_title" to "1. Torrent Manager",
            "dashboard_torrent_subtitle" to "Download .torrent games",
            "dashboard_decompressor_title" to "2. Decompressor",
            "dashboard_decompressor_subtitle" to "Extract .zip and .7z parts",
            "dashboard_server_title" to "3. HTTP Server",
            "dashboard_server_subtitle" to "Start server for Switch",
            "dashboard_server_active" to "Server active",
            "donation_button" to "Support Project (PayPal)",
            "back_button" to "< Back",
            "select_folder" to "Select Folder",
            "select_torrent" to "Select .torrent file",
            "select_zip" to "Select .zip or .7z",
            "destination_folder" to "Destination Folder",
            "torrent_file" to "Torrent File",
            "decompress_files" to "Files to Decompress",
            "active_downloads" to "Active Downloads",
            "start_decompression" to "START DECOMPRESSION",
            "extraction_success" to "Files extracted successfully!",
            "server_active_status" to "Server Active",
            "server_inactive_status" to "Server Inactive",
            "stop_server" to "Stop Server",
            "start_server" to "Start Server",
            "select_folder_first" to "Select folder first",
            "permission_missing" to "Permissions missing",
            "permission_description" to "Full file access is required.",
            "grant_access" to "Grant Access",
            "language_selector" to "Language",
            "remove_torrent" to "Remove"
        )
    )

    fun get(key: String, language: Language): String {
        return strings[language]?.get(key) ?: strings[Language.ENGLISH]?.get(key) ?: key
    }
}

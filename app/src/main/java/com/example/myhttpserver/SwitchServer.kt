package com.example.myhttpserver

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class SwitchServer(private val context: Context) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var cachedFiles = listOf<Triple<String, Uri, Long>>()

    fun start(directoryUri: Uri, port: Int = 8080) {
        stop()
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                get("/") {
                    // Combinar archivos de la carpeta seleccionada y la carpeta de descargas
                    val safFiles = getFilesFromUri(directoryUri)
                    
                    val internalTorrentDir = context.getExternalFilesDir("downloads")
                    val internalFiles = if (internalTorrentDir != null) getFilesFromLocalDir(internalTorrentDir) else emptyList()
                    
                    val publicTorrentDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val publicFiles = if (publicTorrentDir.exists()) getFilesFromLocalDir(publicTorrentDir) else emptyList()
                    
                    val allFilesMap = mutableMapOf<String, Triple<String, Uri, Long>>()
                    (safFiles + internalFiles + publicFiles).forEach { allFilesMap[it.first] = it }
                    cachedFiles = allFilesMap.values.toList()
                    
                    val html = buildString {
                        append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>\n")
                        append("<h2>Archivos disponibles</h2>")
                        cachedFiles.forEach { (name, _, _) ->
                            append("<a href=\"${Uri.encode(name)}\">$name</a><br>\n")
                        }
                        append("</body></html>")
                    }
                    call.respondText(html, ContentType.Text.Html)
                }

                route("/{filename...}") {
                    handle {
                        val filename = call.parameters.getAll("filename")?.joinToString("/")?.let { Uri.decode(it) } 
                            ?: return@handle call.respond(HttpStatusCode.BadRequest)
                        
                        val fileTriple = cachedFiles.find { it.first == filename } ?: return@handle call.respond(HttpStatusCode.NotFound)
                        val fileUri = fileTriple.second
                        val size = fileTriple.third
                        
                        // Agregar cabeceras para evitar timeouts en DBI
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.Connection, "keep-alive")

                        if (call.request.local.method == HttpMethod.Head) {
                            call.response.header(HttpHeaders.ContentLength, size.toString())
                            call.respond(HttpStatusCode.OK)
                            return@handle
                        }

                        val rangeHeader = call.request.header(HttpHeaders.Range)
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            try {
                                val parts = rangeHeader.substring(6).split("-")
                                val start = parts[0].toLong()
                                val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else size - 1
                                val contentLength = end - start + 1

                                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
                                call.response.header(HttpHeaders.ContentLength, contentLength.toString())
                                
                                call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.PartialContent) {
                                    transferData(fileUri, start, contentLength, this)
                                }
                                return@handle
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        call.response.header(HttpHeaders.ContentLength, size.toString())
                        call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.OK) {
                            transferData(fileUri, 0, size, this)
                        }
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    private suspend fun transferData(uri: Uri, start: Long, length: Long, outputStream: java.io.OutputStream) {
        withContext(Dispatchers.IO) {
            openInputStream(uri)?.use { input ->
                // Skip robusto para Android
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip(start - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                
                val buffer = ByteArray(1024 * 1024) // 1MB buffer para velocidad y estabilidad
                var remaining = length
                while (remaining > 0) {
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    outputStream.write(buffer, 0, read)
                    remaining -= read
                }
                outputStream.flush()
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else {
                File(uri.path ?: return null).inputStream()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        val current = server
        server = null
        try {
            current?.stop(500, 1000)
        } catch (e: Exception) {}
    }

    fun refresh() {
        cachedFiles = emptyList()
    }

    private fun getFilesFromUri(directoryUri: Uri): List<Triple<String, Uri, Long>> {
        val files = mutableListOf<Triple<String, Uri, Long>>()
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(directoryUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, rootDocId)
            context.contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val id = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2)
                    if (name.endsWith(".nsp", true) || name.endsWith(".nsz", true) || name.endsWith(".xci", true)) {
                        files.add(Triple(name, DocumentsContract.buildDocumentUriUsingTree(directoryUri, id), size))
                    }
                }
            }
        } catch (e: Exception) {}
        return files
    }

    private fun getFilesFromLocalDir(directory: File): List<Triple<String, Uri, Long>> {
        val files = mutableListOf<Triple<String, Uri, Long>>()
        directory.walkTopDown().forEach { file ->
            if (file.isFile && (file.name.endsWith(".nsp", true) || file.name.endsWith(".nsz", true) || file.name.endsWith(".xci", true))) {
                files.add(Triple(file.name, Uri.fromFile(file), file.length()))
            }
        }
        return files
    }
}

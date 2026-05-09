package com.example.myhttpserver

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toFile
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
                    val torrentDir = context.getExternalFilesDir("downloads")
                    val torrentFiles = if (torrentDir != null) getFilesFromLocalDir(torrentDir) else emptyList()
                    
                    cachedFiles = safFiles + torrentFiles
                    
                    val html = buildString {
                        append("<!DOCTYPE html><html><body>\n")
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
                        
                        if (call.request.local.method == HttpMethod.Head) {
                            call.response.header(HttpHeaders.ContentLength, size.toString())
                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
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
                                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                
                                call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.PartialContent) {
                                    val out = this
                                    withContext(Dispatchers.IO) {
                                        openInputStream(fileUri)?.use { input ->
                                            input.skip(start)
                                            val buffer = ByteArray(256 * 1024)
                                            var remaining = contentLength
                                            while (remaining > 0) {
                                                val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                                                val read = input.read(buffer, 0, toRead)
                                                if (read == -1) break
                                                out.write(buffer, 0, read)
                                                remaining -= read
                                            }
                                        }
                                    }
                                }
                                return@handle
                            } catch (e: Exception) {}
                        }

                        call.response.header(HttpHeaders.ContentLength, size.toString())
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        
                        call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.OK) {
                            val out = this
                            withContext(Dispatchers.IO) {
                                openInputStream(fileUri)?.use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        out.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    private fun openInputStream(uri: Uri): InputStream? {
        return if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)
        } else {
            File(uri.path!!).inputStream()
        }
    }

    fun stop() {
        val current = server
        server = null
        try {
            current?.stop(100, 500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun refresh() {
        cachedFiles = emptyList() // Forzar re-escaneo en la próxima petición
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

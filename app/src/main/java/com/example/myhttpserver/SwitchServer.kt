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

class SwitchServer(private val context: Context) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var cachedFiles = listOf<Triple<String, Uri, Long>>()

    fun start(directoryUri: Uri, port: Int = 8080) {
        stop()
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                // Listado de archivos con caché para velocidad instantánea
                get("/") {
                    cachedFiles = getFilesFromUri(directoryUri)
                    val html = buildString {
                        append("<!DOCTYPE html><html><body>\n")
                        cachedFiles.forEach { (name, _, _) ->
                            append("<a href=\"${Uri.encode(name)}\">$name</a><br>\n")
                        }
                        append("</body></html>")
                    }
                    call.respondText(html, ContentType.Text.Html)
                }

                // Manejo de archivos (GET y HEAD)
                route("/{filename...}") {
                    handle {
                        val filename = call.parameters.getAll("filename")?.joinToString("/")?.let { Uri.decode(it) } 
                            ?: return@handle call.respond(HttpStatusCode.BadRequest)
                        
                        // Usar caché para evitar re-escanear la microSD en cada petición (HEAD, GET, Range)
                        val files = if (cachedFiles.isNotEmpty()) cachedFiles else getFilesFromUri(directoryUri)
                        val fileTriple = files.find { it.first == filename } ?: return@handle call.respond(HttpStatusCode.NotFound)
                        val fileUri = fileTriple.second
                        val size = fileTriple.third
                        
                        // HEAD es vital para que DBI vea el tamaño y no diga "Nada que instalar"
                        if (call.request.local.method == HttpMethod.Head) {
                            call.response.header(HttpHeaders.ContentLength, size.toString())
                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
                            call.respond(HttpStatusCode.OK)
                            return@handle
                        }

                        // Soporte de Range (Crítico para NSZ)
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
                                        context.contentResolver.openInputStream(fileUri)?.use { input ->
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

                        // Respuesta completa (Instalación NSP rápida)
                        call.response.header(HttpHeaders.ContentLength, size.toString())
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        
                        call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.OK) {
                            val out = this
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(fileUri)?.use { input ->
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

    fun stop() {
        val current = server
        server = null
        try {
            current?.stop(100, 500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
}

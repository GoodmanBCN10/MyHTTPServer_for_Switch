package com.example.myhttpserver

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object Decompressor {
    private const val TAG = "Decompressor"

    fun decompress(
        context: Context,
        partUris: List<Uri>,
        destFolderUri: Uri,
        onProgress: (String) -> Unit
    ): List<Uri> {
        if (partUris.isEmpty()) return emptyList()
        
        val firstFile = partUris[0]
        val name = getFileName(context, firstFile).lowercase()
        
        return when {
            name.endsWith(".7z") || name.contains(".7z.") -> decompress7z(context, partUris, destFolderUri, onProgress)
            name.endsWith(".zip") -> decompressZip(context, firstFile, destFolderUri, onProgress)
            else -> throw Exception("Formato no soportado. Usa .7z o .zip")
        }
    }

    private fun decompressZip(
        context: Context,
        uri: Uri,
        destFolderUri: Uri,
        onProgress: (String) -> Unit
    ): List<Uri> {
        val extractedUris = mutableListOf<Uri>()
        val destFolder = DocumentFile.fromTreeUri(context, destFolderUri)
            ?: throw Exception("No se puede acceder a la carpeta de destino")

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        onProgress("Extrayendo ${entry.name}...")
                        
                        val newFile = destFolder.createFile("application/octet-stream", entry.name)
                            ?: throw Exception("No se pudo crear el archivo: ${entry.name}")
                        
                        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                            val buffer = ByteArray(1024 * 1024) // 1MB buffer
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                out.write(buffer, 0, len)
                            }
                        }
                        extractedUris.add(newFile.uri)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return extractedUris
    }

    fun decompress7z(
        context: Context, 
        partUris: List<Uri>, 
        destFolderUri: Uri, 
        onProgress: (String) -> Unit
    ): List<Uri> {
        val extractedUris = mutableListOf<Uri>()
        val pfds = mutableListOf<ParcelFileDescriptor>()
        val destFolder = DocumentFile.fromTreeUri(context, destFolderUri) 
            ?: throw Exception("No se puede acceder a la carpeta de destino")
        
        try {
            val channels = partUris.map { uri ->
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("No se pudo abrir la parte: $uri")
                pfds.add(pfd)
                FileInputStream(pfd.fileDescriptor).channel
            }

            val combinedChannel = MultiReadOnlySeekableByteChannel(channels)
            
            SevenZFile.Builder().setSeekableByteChannel(combinedChannel).get().use { sevenZFile ->
                var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        onProgress("Extrayendo ${entry.name}...")
                        
                        val newFile = destFolder.createFile("application/octet-stream", entry.name)
                            ?: throw Exception("No se pudo crear el archivo: ${entry.name}")
                        
                        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                            val buffer = ByteArray(1024 * 1024) // 1MB buffer
                            var bytesRead: Int
                            while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                            }
                        }
                        extractedUris.add(newFile.uri)
                    }
                    entry = sevenZFile.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en 7z", e)
            throw e
        } finally {
            pfds.forEach { try { it.close() } catch (_: Exception) {} }
        }
        
        return extractedUris
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "archivo"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name
    }
}

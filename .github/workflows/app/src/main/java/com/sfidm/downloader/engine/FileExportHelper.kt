package com.sfidm.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.sfidm.downloader.model.FileCategory
import java.io.File
import java.io.FileInputStream

object FileExportHelper {

    fun getCategoryWorkingDir(context: Context, category: FileCategory): File {
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SFIDM/${category.folderName}")
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun getVaultDir(context: Context): File {
        val vault = File(context.filesDir, "private_vault")
        if (!vault.exists()) vault.mkdirs()
        val noMedia = File(vault, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return vault
    }

    fun exportToPublicCategoryFolder(
        context: Context,
        sourceFile: File,
        category: FileCategory,
        fileName: String = sourceFile.name
    ) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/SFIDM/${category.folderName}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SFIDM/${category.folderName}"
                )
                if (!publicDir.exists()) publicDir.mkdirs()
                val dest = File(publicDir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            }
        } catch (_: Exception) {
            // Remains in app external category directory
        }
    }

    fun moveFileToVault(context: Context, currentPath: String, fileName: String): String? {
        val src = File(currentPath)
        if (!src.exists()) return null
        val vaultDir = getVaultDir(context)
        val dest = File(vaultDir, sanitizeFileName(fileName))
        if (dest.exists()) dest.delete()
        src.copyTo(dest, overwrite = true)
        src.delete()
        return dest.absolutePath
    }

    fun exportFileFromVault(context: Context, currentPath: String, category: FileCategory, fileName: String): String? {
        val src = File(currentPath)
        if (!src.exists()) return null
        val catDir = getCategoryWorkingDir(context, category)
        val dest = File(catDir, sanitizeFileName(fileName))
        if (dest.exists()) dest.delete()
        src.copyTo(dest, overwrite = true)
        src.delete()
        exportToPublicCategoryFolder(context, dest, category, dest.name)
        return dest.absolutePath
    }

    fun sanitizeFileName(name: String): String {
        val invalidChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return name.map { if (it in invalidChars) '_' else it }
            .joinToString("")
            .trim()
            .ifBlank { "SFIDM_${System.currentTimeMillis()}.bin" }
    }
}

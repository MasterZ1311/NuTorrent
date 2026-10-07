package com.nutorrent.app.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nutorrent.app.core.model.TorrentMetadata
import java.io.File
import java.util.Locale

object FileOpener {

    fun getMimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac", "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "iso" -> "application/x-iso9660-image"
            "txt", "nfo" -> "text/plain"
            "srt", "vtt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "*/*"
        }
    }

    fun openFile(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist on storage: ${file.name}", Toast.LENGTH_LONG).show()
            return
        }

        if (file.isDirectory) {
            openFolder(context, file)
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open ${file.extension.uppercase()} file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to open file ${file.absolutePath}", e)
            Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(context: Context, file: File) {
        if (!file.exists() || file.isDirectory) {
            Toast.makeText(context, "Cannot share directory or missing file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = getMimeType(file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share file via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to share file ${file.absolutePath}", e)
            Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openFolder(context: Context, folder: File) {
        val targetDir = if (folder.isDirectory) folder else folder.parentFile ?: folder

        // Copy path to clipboard as a helpful fallback
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Folder Path", targetDir.absolutePath))

        try {
            val authority = "${context.packageName}.fileprovider"
            val folderUri = try {
                FileProvider.getUriForFile(context, authority, targetDir)
            } catch (ignored: Exception) {
                Uri.fromFile(targetDir)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open folder in file manager...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Folder path copied to clipboard:\n${targetDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun resolvePrimaryTarget(destinationPath: String, metadata: TorrentMetadata? = null, torrentName: String? = null): File {
        val destDir = File(destinationPath)
        if (!destDir.exists()) return destDir

        if (metadata != null && metadata.files.isNotEmpty()) {
            if (metadata.files.size == 1) {
                val single = File(destDir, metadata.files[0].relativePath)
                if (single.exists()) return single
            }

            val largest = metadata.files.maxByOrNull { it.length }
            if (largest != null) {
                val mediaCandidate = File(destDir, largest.relativePath)
                if (mediaCandidate.exists()) return mediaCandidate
            }

            val subDir = File(destDir, metadata.name)
            if (subDir.exists() && subDir.isDirectory) {
                return subDir
            }
        }

        val searchRoot = if (torrentName != null) {
            val candidate = File(destDir, torrentName)
            if (candidate.exists() && candidate.isDirectory) candidate else destDir
        } else {
            destDir
        }

        val largestFile = searchRoot.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .maxByOrNull { it.length() }

        if (largestFile != null && largestFile.length() > 0) {
            return largestFile
        }

        return searchRoot
    }
}

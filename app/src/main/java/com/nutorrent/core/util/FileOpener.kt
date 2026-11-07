package com.nutorrent.core.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.nutorrent.core.model.TorrentMetadata
import java.io.File
import java.util.Locale

object FileOpener {

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val frameworkMime = try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
        } catch (_: Throwable) {
            null
        }
        return frameworkMime ?: when (extension) {
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

    fun getMimeType(file: File): String = getMimeType(file.name)

    /**
     * Finds a DocumentFile under a SAF tree URI by relative path.
     */
    fun findDocumentFile(context: Context, treeUri: Uri, relativePath: String): DocumentFile? {
        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            null
        } ?: return null

        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (segment == "." || segment == "..") return null
        }
        var current = root
        for (i in 0 until segments.size - 1) {
            current = current.findFile(segments[i]) ?: return null
        }
        return if (segments.isNotEmpty()) current.findFile(segments.last()) else current
    }

    /**
     * Opens a file either from a regular filesystem path or from a SAF tree document URI.
     */
    /**
     * Searches a DocumentFile directory (and immediate subdirectories) for the largest non-hidden file.
     */
    fun findDeepestCandidateFile(directory: DocumentFile): DocumentFile? {
        val files = try { directory.listFiles() } catch (e: Exception) { emptyArray() }
        val immediateFile = files.filter { it.isFile && !it.name.orEmpty().startsWith(".") }
            .maxByOrNull { it.length() }
        if (immediateFile != null && immediateFile.length() > 0) return immediateFile

        for (sub in files.filter { it.isDirectory }) {
            val subFiles = try { sub.listFiles() } catch (e: Exception) { emptyArray() }
            val subCandidate = subFiles.filter { it.isFile && !it.name.orEmpty().startsWith(".") }
                .maxByOrNull { it.length() }
            if (subCandidate != null && subCandidate.length() > 0) return subCandidate
        }
        return immediateFile
    }

    /**
     * Opens a single DocumentFile using Intent.ACTION_VIEW.
     */
    fun openDocumentFile(context: Context, docFile: DocumentFile) {
        try {
            val mimeType = docFile.type ?: getMimeType(docFile.name ?: "")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docFile.uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            val ext = docFile.name?.substringAfterLast('.', "")?.uppercase(Locale.ROOT) ?: "this"
            Toast.makeText(context, "No app found to open $ext file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to open SAF document: ${docFile.uri}", e)
            Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens a file either from a regular filesystem path or from a SAF tree document URI.
     */
    fun openFile(context: Context, destinationPath: String, relativePath: String? = null, displayName: String? = null) {
        val trimmed = destinationPath.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val treeUri = Uri.parse(trimmed)
            val docFile = if (relativePath != null && relativePath.isNotEmpty()) {
                findDocumentFile(context, treeUri, relativePath)
            } else {
                DocumentFile.fromTreeUri(context, treeUri)
            }

            if (docFile == null || !docFile.exists()) {
                val targetName = displayName ?: relativePath ?: trimmed
                Toast.makeText(context, "File does not exist on storage: $targetName", Toast.LENGTH_LONG).show()
                return
            }

            if (docFile.isDirectory) {
                val candidateFile = findDeepestCandidateFile(docFile)
                if (candidateFile != null && candidateFile.exists() && candidateFile.isFile) {
                    openDocumentFile(context, candidateFile)
                    return
                }
                openFolder(context, destinationPath)
                return
            }

            openDocumentFile(context, docFile)
        } else {
            val baseFile = File(trimmed).canonicalFile
            val targetFile = if (relativePath != null && relativePath.isNotEmpty()) {
                File(baseFile, relativePath).canonicalFile
            } else {
                baseFile
            }
            val basePath = baseFile.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
            if (targetFile != baseFile && !targetFile.path.startsWith(basePath)) {
                Toast.makeText(context, "Invalid file path traversal: $relativePath", Toast.LENGTH_SHORT).show()
                return
            }
            openFile(context, targetFile)
        }
    }

    /**
     * Opens a local file via FileProvider.
     */
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
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open ${file.extension.uppercase()} file", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Lg.e(LogCategory.STORAGE, "File cannot be shared via FileProvider (outside configured paths): ${file.absolutePath}", e)
            Toast.makeText(context, "File path is outside permitted application storage", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to open file ${file.absolutePath}", e)
            Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares a file via Intent.ACTION_SEND.
     */
    fun shareFile(context: Context, destinationPath: String, relativePath: String? = null) {
        val trimmed = destinationPath.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val treeUri = Uri.parse(trimmed)
            val docFile = if (relativePath != null && relativePath.isNotEmpty()) {
                findDocumentFile(context, treeUri, relativePath)
            } else {
                DocumentFile.fromTreeUri(context, treeUri)
            }

            if (docFile == null || !docFile.exists() || docFile.isDirectory) {
                Toast.makeText(context, "Cannot share directory or missing file", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val mimeType = docFile.type ?: getMimeType(docFile.name ?: "")
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, docFile.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "Share file via...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Lg.e(LogCategory.STORAGE, "Failed to share SAF document: ${docFile.uri}", e)
                Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            val baseFile = File(trimmed).canonicalFile
            val targetFile = if (relativePath != null && relativePath.isNotEmpty()) {
                File(baseFile, relativePath).canonicalFile
            } else {
                baseFile
            }
            val basePath = baseFile.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
            if (targetFile != baseFile && !targetFile.path.startsWith(basePath)) {
                Toast.makeText(context, "Invalid file path traversal: $relativePath", Toast.LENGTH_SHORT).show()
                return
            }
            shareFile(context, targetFile)
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share file via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: IllegalArgumentException) {
            Lg.e(LogCategory.STORAGE, "File cannot be shared via FileProvider (outside configured paths): ${file.absolutePath}", e)
            Toast.makeText(context, "File path is outside permitted application storage", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Lg.e(LogCategory.STORAGE, "Failed to share file ${file.absolutePath}", e)
            Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the destination folder in the system file manager.
     * Always copies the location to clipboard as a reliable fallback.
     * Never claims the folder was opened if the system did not open it.
     */
    fun openFolder(context: Context, destinationPath: String) {
        val trimmed = destinationPath.trim()

        // Always copy path/URI to clipboard first
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Folder Location", trimmed))

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val treeUri = Uri.parse(trimmed)
            try {
                val docUri = if (DocumentsContract.isTreeUri(treeUri)) {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                } else {
                    treeUri
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Open folder in file manager...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Toast.makeText(context, "Opening folder in file manager...", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                try {
                    val browseIntent = Intent("android.provider.action.BROWSE").apply {
                        data = treeUri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browseIntent)
                    return
                } catch (fallbackEx: Exception) {
                    Toast.makeText(
                        context,
                        "Folder location copied to clipboard:\n${FormatUtils.formatStoragePath(trimmed)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            val folder = File(trimmed)
            openFolder(context, folder)
        }
    }

    fun openFolder(context: Context, folder: File) {
        val targetDir = if (folder.isDirectory) folder else folder.parentFile ?: folder

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Folder Path", targetDir.absolutePath))

        if (!targetDir.exists()) {
            Toast.makeText(
                context,
                "Folder location copied to clipboard:\n${targetDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (targetDir.absolutePath.contains("/Android/data/")) {
            Toast.makeText(
                context,
                "Files are in app-private storage.\nFolder path copied to clipboard. (View files in the 'Files' tab or choose a custom download folder in Settings)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val opened = tryOpenSystemFileManager(context, targetDir)
        if (!opened) {
            Toast.makeText(
                context,
                "Folder path copied to clipboard:\n${targetDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun tryOpenSystemFileManager(context: Context, folder: File): Boolean {
        try {
            val relPath = folder.absolutePath.substringAfter("/storage/emulated/0/", "").trim('/')
            if (relPath.isNotEmpty()) {
                val docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relPath"
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Open folder in file manager...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Toast.makeText(context, "Opening folder in file manager...", Toast.LENGTH_SHORT).show()
                return true
            }
        } catch (e: Exception) {
            // Fallback
        }
        return false
    }

    /**
     * Resolves the primary target file or folder for a completed torrent.
     */
    fun openPrimaryTarget(
        context: Context,
        destinationPath: String,
        metadata: TorrentMetadata? = null,
        torrentName: String? = null
    ) {
        val trimmed = destinationPath.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val treeUri = Uri.parse(trimmed)
            val root = try { DocumentFile.fromTreeUri(context, treeUri) } catch (e: Exception) { null }
            if (root == null || !root.exists()) {
                Toast.makeText(context, "Download location not accessible", Toast.LENGTH_SHORT).show()
                return
            }

            if (metadata != null && metadata.files.isNotEmpty()) {
                val targetFileMeta = if (metadata.files.size == 1) metadata.files[0] else metadata.files.maxByOrNull { it.length }
                val targetDoc = targetFileMeta?.let { findDocumentFile(context, treeUri, it.relativePath) }
                if (targetDoc != null && targetDoc.exists() && targetDoc.isFile) {
                    openDocumentFile(context, targetDoc)
                    return
                }
            }

            val searchDir = if (!torrentName.isNullOrBlank()) {
                root.findFile(torrentName) ?: root
            } else {
                root
            }

            val candidateFile = if (searchDir.isDirectory) {
                findDeepestCandidateFile(searchDir)
            } else if (searchDir.isFile) {
                searchDir
            } else null

            if (candidateFile != null && candidateFile.exists() && candidateFile.isFile) {
                openDocumentFile(context, candidateFile)
            } else {
                openFolder(context, destinationPath)
            }
        } else {
            val targetFile = resolvePrimaryTarget(destinationPath, metadata, torrentName)
            openFile(context, targetFile)
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

        val largestFile = try {
            searchRoot.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .maxByOrNull { it.length() }
        } catch (e: Exception) {
            null
        }

        if (largestFile != null && largestFile.length() > 0) {
            return largestFile
        }

        return searchRoot
    }
}

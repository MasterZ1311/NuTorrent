package com.nutorrent.ui.addtorrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import com.nutorrent.ui.components.NuLogoLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.repository.SettingsRepository
import com.nutorrent.data.repository.TorrentRepository
import com.nutorrent.data.storage.StorageCalculator
import com.nutorrent.torrent.metainfo.MetainfoParser
import com.nutorrent.ui.components.StatItem
import com.nutorrent.ui.theme.NuError
import com.nutorrent.ui.theme.NuWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentScreen(
    fileUri: Uri,
    repository: TorrentRepository,
    settingsRepository: SettingsRepository,
    onStartDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var metadata by remember { mutableStateOf<TorrentMetadata?>(null) }
    var rawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var destinationPath by remember { mutableStateOf(settingsRepository.settings.value.defaultDownloadPath) }
    var selectedFileIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showLowStorageDialog by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Lg.w(LogCategory.STORAGE, "Failed to persist URI permission: ${e.message}")
            }
            destinationPath = uri.toString()
        }
    }

    LaunchedEffect(fileUri) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = readUriBytes(context, fileUri)
                val textCandidate = try { String(bytes, Charsets.UTF_8).trim() } catch (_: Exception) { "" }
                val isLikelyMagnet = textCandidate.startsWith("magnet:", ignoreCase = true) ||
                    textCandidate.startsWith("thunder://", ignoreCase = true) ||
                    textCandidate.contains("xt=urn:btih:", ignoreCase = true) ||
                    textCandidate.contains("xt=urn:btmh:", ignoreCase = true) ||
                    fileUri.toString().endsWith(".magnet", ignoreCase = true)

                if (isLikelyMagnet) {
                    try {
                        val magnet = com.nutorrent.torrent.metainfo.MagnetUri.parse(textCandidate)
                        repository.addMagnetTorrent(magnet, destinationPath, autoStart = true)
                        withContext(Dispatchers.Main) {
                            onStartDownload()
                        }
                        return@withContext
                    } catch (_: Exception) {}
                }

                val meta = try {
                    MetainfoParser.parse(bytes)
                } catch (bencodeErr: Exception) {
                    if (textCandidate.isNotEmpty()) {
                        try {
                            val magnet = com.nutorrent.torrent.metainfo.MagnetUri.parse(textCandidate)
                            repository.addMagnetTorrent(magnet, destinationPath, autoStart = true)
                            withContext(Dispatchers.Main) {
                                onStartDownload()
                            }
                            return@withContext
                        } catch (_: Exception) {}
                    }
                    throw bencodeErr
                }
                rawBytes = bytes
                metadata = meta
                selectedFileIndices = meta.files.map { it.index }.toSet()
                isLoading = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to read or parse torrent file"
                isLoading = false
            }
        }
    }

    val assessment = remember(metadata, selectedFileIndices, destinationPath) {
        metadata?.let { meta ->
            StorageCalculator.assessStorage(context, destinationPath, meta, selectedFileIndices)
        }
    }

    if (showLowStorageDialog && assessment != null) {
        AlertDialog(
            onDismissRequest = { showLowStorageDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NuError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Not Enough Storage", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The selected destination does not have sufficient free space to complete this download.",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Required: ${FormatUtils.formatBytes(assessment.requiredBytes)}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Available: ${FormatUtils.formatBytes(assessment.availableBytes ?: 0L)}",
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Shortfall: ${FormatUtils.formatBytes(assessment.shortfallBytes ?: 0L)}",
                        color = NuError,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose another location or free up storage before downloading.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLowStorageDialog = false
                        folderPickerLauncher.launch(null)
                    }
                ) {
                    Text("Choose Another Location")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLowStorageDialog = false
                        // User chooses to proceed anyway
                        scope.launch {
                            val bytes = rawBytes ?: return@launch
                            val meta = metadata ?: return@launch
                            repository.addTorrent(meta, bytes, destinationPath, autoStart = true, selectedFileIndices = selectedFileIndices)
                            onStartDownload()
                        }
                    }
                ) {
                    Text("Download Anyway", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Torrent", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    NuLogoLoadingIndicator(
                        modifier = Modifier.fillMaxSize(),
                        message = "Reading torrent metainfo...",
                        logoSize = 56.dp
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Invalid Torrent File",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(onClick = onCancel) {
                            Text("Go Back")
                        }
                    }
                }
                metadata != null -> {
                    metadata?.let { meta ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Overview card
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = meta.name,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            StatItem(
                                                label = "Total Size",
                                                value = FormatUtils.formatBytes(meta.totalLength),
                                                modifier = Modifier.weight(1f)
                                            )
                                            StatItem(
                                                label = "Pieces",
                                                value = "${meta.piecesCount} (${FormatUtils.formatBytes(meta.pieceLength)})",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            StatItem(
                                                label = "Files Count",
                                                value = "${meta.files.size}",
                                                modifier = Modifier.weight(1f)
                                            )
                                            StatItem(
                                                label = "Info Hash",
                                                value = meta.torrentId.hex.take(12) + "...",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                // Storage Capacity Check Card
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(
                                                1.dp,
                                                if (assessment?.isInsufficient == true) NuError else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Download Location",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                            TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                                                Text("Change", fontSize = 13.sp)
                                            }
                                        }

                                        Text(
                                            text = FormatUtils.formatStoragePath(destinationPath),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (destinationPath.startsWith("content://")) {
                                            Text(
                                                text = destinationPath,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Assessment feedback
                                        if (assessment != null) {
                                            if (assessment.isInsufficient) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(NuError.copy(alpha = 0.12f))
                                                        .border(1.dp, NuError.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                        .padding(12.dp)
                                                ) {
                                                    Text(
                                                        text = "Not enough storage",
                                                        color = NuError,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Required: ${FormatUtils.formatBytes(assessment.requiredBytes)}",
                                                        fontSize = 12.sp
                                                    )
                                                    Text(
                                                        text = "Available: ${FormatUtils.formatBytes(assessment.availableBytes ?: 0L)}",
                                                        fontSize = 12.sp
                                                    )
                                                    Text(
                                                        text = "Shortfall: ${FormatUtils.formatBytes(assessment.shortfallBytes ?: 0L)}",
                                                        color = NuError,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 12.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Choose another location or free up storage.",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            } else if (assessment.isCapacityUnknown) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(NuWarning.copy(alpha = 0.1f))
                                                        .padding(10.dp)
                                                ) {
                                                    Text(
                                                        text = "Required Space: ${FormatUtils.formatBytes(assessment.requiredBytes)}",
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = "Storage capacity cannot be verified for this location. Ensure your storage has sufficient space available.",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            } else {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "Required: ${FormatUtils.formatBytes(assessment.requiredBytes)}",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = "Available: ${FormatUtils.formatBytes(assessment.availableBytes ?: 0L)}",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Trackers
                                if (meta.announceList.isNotEmpty()) {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                                .padding(16.dp)
                                        ) {
                                            Text(
                                                text = "TRACKERS (${meta.announceList.size})",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            for (tr in meta.announceList.take(3)) {
                                                Text(
                                                    text = tr,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                        }
                                    }
                                }

                                // Files header with select/deselect all
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "FILES (${selectedFileIndices.size}/${meta.files.size} selected)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row {
                                            TextButton(
                                                onClick = { selectedFileIndices = meta.files.map { it.index }.toSet() },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Text("All", fontSize = 12.sp)
                                            }
                                            TextButton(
                                                onClick = { selectedFileIndices = emptySet() },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Text("None", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }

                                items(meta.files) { file ->
                                    val isSelected = file.index in selectedFileIndices
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .clickable {
                                                selectedFileIndices = if (isSelected) {
                                                    selectedFileIndices - file.index
                                                } else {
                                                    selectedFileIndices + file.index
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedFileIndices = if (checked) {
                                                        selectedFileIndices + file.index
                                                    } else {
                                                        selectedFileIndices - file.index
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = file.relativePath,
                                                fontSize = 13.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = FormatUtils.formatBytes(file.length),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Bottom actions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onCancel,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        if (assessment?.isInsufficient == true) {
                                            showLowStorageDialog = true
                                        } else {
                                            scope.launch {
                                                val bytes = rawBytes ?: return@launch
                                                repository.addTorrent(meta, bytes, destinationPath, autoStart = true, selectedFileIndices = selectedFileIndices)
                                                onStartDownload()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = if (assessment?.isInsufficient == true) {
                                        ButtonDefaults.buttonColors(containerColor = NuWarning)
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    }
                                ) {
                                    Text(if (assessment?.isInsufficient == true) "Low Space" else "Start Download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readUriBytes(context: Context, uri: Uri): ByteArray {
    val inputStream: InputStream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Could not open input stream from URI: $uri")
    val buffer = ByteArray(4096)
    val out = ByteArrayOutputStream()
    inputStream.use { s ->
        var read: Int
        while (s.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

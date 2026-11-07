package com.nutorrent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.nutorrent.core.util.FormatUtils
import com.nutorrent.core.util.UniversalLinkDecoder
import com.nutorrent.torrent.metainfo.MagnetUri
import kotlinx.coroutines.delay

sealed class MagnetValidationResult {
    object Empty : MagnetValidationResult()
    data class Valid(
        val infoHashHex: String,
        val displayName: String?,
        val trackersCount: Int,
        val rawUrl: String
    ) : MagnetValidationResult()
    data class Invalid(val errorReason: String) : MagnetValidationResult()
}

sealed class FieldValidationResult {
    object Valid : FieldValidationResult()
    data class Warning(val message: String) : FieldValidationResult()
    data class Error(val message: String) : FieldValidationResult()
}

object SmartFormValidator {

    /**
     * Live validation of a Magnet or universal torrent link.
     */
    fun validateMagnet(input: String): MagnetValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return MagnetValidationResult.Empty

        val decoded = UniversalLinkDecoder.decode(trimmed)
        if (!decoded.startsWith("magnet:", ignoreCase = true) && !decoded.startsWith("thunder://", ignoreCase = true)) {
            return MagnetValidationResult.Invalid("Must start with 'magnet:?' or 'thunder://'")
        }

        return try {
            val parsed = MagnetUri.parse(decoded)
            MagnetValidationResult.Valid(
                infoHashHex = parsed.torrentId.hex,
                displayName = parsed.displayName,
                trackersCount = parsed.trackers.size,
                rawUrl = decoded
            )
        } catch (e: Exception) {
            val msg = e.message ?: "Invalid magnet structure"
            MagnetValidationResult.Invalid("Could not parse magnet info-hash: $msg")
        }
    }

    /**
     * Validates incoming BitTorrent listening ports.
     */
    fun validatePort(portStr: String): FieldValidationResult {
        val trimmed = portStr.trim()
        if (trimmed.isEmpty()) return FieldValidationResult.Error("Port number is required")
        val port = trimmed.toIntOrNull() ?: return FieldValidationResult.Error("Port must be a valid integer")
        if (port < 1024) return FieldValidationResult.Error("Ports below 1024 are reserved by the OS")
        if (port > 65535) return FieldValidationResult.Error("Port cannot exceed 65535")
        if (port in 6881..6889) return FieldValidationResult.Warning("Standard 6881-6889 ports are often throttled by ISPs")
        return FieldValidationResult.Valid
    }

    /**
     * Validates transfer speed limits in KB/s.
     */
    fun validateSpeedLimit(inputStr: String): Pair<FieldValidationResult, Long> {
        val trimmed = inputStr.trim()
        if (trimmed.isEmpty() || trimmed == "0") return Pair(FieldValidationResult.Valid, 0L)
        val kb = trimmed.toLongOrNull()
        if (kb == null || kb < 0) return Pair(FieldValidationResult.Error("Must be positive number"), 0L)
        val bps = kb * 1024L
        if (kb > 10_000_000L) return Pair(FieldValidationResult.Warning("Speed limit exceeds 10 GB/s"), bps)
        return Pair(FieldValidationResult.Valid, bps)
    }
}

/**
 * Hook to provide debounced state emission in Compose.
 */
@Composable
fun <T> rememberDebouncedValue(
    value: T,
    delayMillis: Long = 300L
): T {
    val debouncedState = remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        delay(delayMillis)
        debouncedState.value = value
    }
    return debouncedState.value
}

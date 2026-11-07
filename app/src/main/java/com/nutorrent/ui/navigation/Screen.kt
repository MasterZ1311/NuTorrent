package com.nutorrent.ui.navigation

import android.net.Uri

sealed class Screen {
    object Home : Screen()
    data class AddTorrent(val fileUri: Uri) : Screen()
    data class Details(val infoHash: String) : Screen()
    object Settings : Screen()
    object CommunityReferrals : Screen()
    object RouteNotFound : Screen()

    // Edge case screens
    data class TorrentNotFound(val infoHash: String = "") : Screen()
    data class InvalidMagnet(val rawUrl: String = "", val errorReason: String = "") : Screen()
    data class InvalidTorrentFile(val fileName: String = "", val errorReason: String = "") : Screen()
}

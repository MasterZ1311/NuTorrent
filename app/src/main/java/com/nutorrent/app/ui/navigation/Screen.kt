package com.nutorrent.app.ui.navigation

import android.net.Uri

sealed class Screen {
    object Home : Screen()
    data class AddTorrent(val fileUri: Uri) : Screen()
    data class Details(val infoHash: String) : Screen()
    object Settings : Screen()
}

package com.nutorrent.app.core.util

import android.util.Log

enum class LogCategory {
    BENCODE,
    METAINFO,
    TRACKER,
    PEER,
    PIECE,
    STORAGE,
    ENGINE,
    SERVICE,
    DHT,
    NETWORK
}

object Lg {
    var isDebugEnabled: Boolean = true

    fun d(category: LogCategory, message: String) {
        if (isDebugEnabled) {
            try {
                Log.d("nutorrent/${category.name}", message)
            } catch (e: Throwable) {
                println("[DEBUG] nutorrent/${category.name}: $message")
            }
        }
    }

    fun i(category: LogCategory, message: String) {
        try {
            Log.i("nutorrent/${category.name}", message)
        } catch (e: Throwable) {
            println("[INFO] nutorrent/${category.name}: $message")
        }
    }

    fun w(category: LogCategory, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w("nutorrent/${category.name}", message, throwable)
            } else {
                Log.w("nutorrent/${category.name}", message)
            }
        } catch (e: Throwable) {
            println("[WARN] nutorrent/${category.name}: $message ${throwable?.message ?: ""}")
        }
    }

    fun e(category: LogCategory, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.e("nutorrent/${category.name}", message, throwable)
            } else {
                Log.e("nutorrent/${category.name}", message)
            }
        } catch (e: Throwable) {
            println("[ERROR] nutorrent/${category.name}: $message ${throwable?.message ?: ""}")
        }
    }
}

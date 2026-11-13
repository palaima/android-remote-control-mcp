package com.danielealbano.androidremotecontrolmcp.utils

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build

/**
 * Whether [e] is Android refusing a foreground service start from the background
 * ([ForegroundServiceStartNotAllowedException], Android 12+). Android 11 has no such refusal.
 */
fun isForegroundStartRefused(e: IllegalStateException): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException

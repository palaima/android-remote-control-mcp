package com.danielealbano.androidremotecontrolmcp.services.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "MCP:ReverseGeocoder"

/** Framework-only (GMS-free) reverse geocoding shared by all LocationProvider implementations. */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal suspend fun reverseGeocode(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? {
    if (!Geocoder.isPresent()) {
        Log.d(TAG, "Geocoder not present on this device")
        return null
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return reverseGeocodeBlocking(context, latitude, longitude)
    }
    return try {
        suspendCancellableCoroutine { cont ->
            Geocoder(context, Locale.getDefault()).getFromLocation(
                latitude,
                longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: List<Address>) {
                        cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                    }

                    override fun onError(errorMessage: String?) {
                        Log.d(TAG, "Geocoder onError: $errorMessage")
                        cont.resume(null)
                    }
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Reverse geocoding failed: ${e.message}")
        null
    }
}

/**
 * Android 11 and 12 have only the blocking Geocoder call (the listener form is Android 13+).
 * It runs on [Dispatchers.IO].
 */
private suspend fun reverseGeocodeBlocking(
    context: Context,
    latitude: Double,
    longitude: Double,
): String? =
    withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        } catch (e: IOException) {
            Log.d(TAG, "Reverse geocoding failed: ${e.message}")
            null
        }
    }

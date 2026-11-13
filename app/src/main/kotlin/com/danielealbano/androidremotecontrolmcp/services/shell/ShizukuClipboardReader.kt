package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

private const val CLIPBOARD_SERVICE = "clipboard"
private const val CLIPBOARD_INTERFACE = "android.content.IClipboard"

/** Signature prefix that exempts `IClipboard` and its `Stub` from the hidden API restrictions. */
private const val CLIPBOARD_HIDDEN_API_PREFIX = "Landroid/content/IClipboard"
private const val SHELL_PACKAGE = "com.android.shell"
private const val DEFAULT_DEVICE_ID = 0

/** Size of each user's uid range (`UserHandle.PER_USER_RANGE`). */
private const val PER_USER_RANGE = 100_000

/** The outcome of a clipboard read through Shizuku. */
sealed interface ShizukuClipboardRead {
    /** Shizuku is not ready or elevated commands are turned off; nothing was read. */
    data object Unavailable : ShizukuClipboardRead

    /** The clipboard was read. [text] is null when the clipboard is empty or its first item has no text. */
    data class Read(
        val text: String?,
    ) : ShizukuClipboardRead
}

/**
 * Reads the clipboard through the system clipboard service as the shell user.
 *
 * Android lets only the focused app and the default input method read the clipboard. The shell user holds
 * `READ_CLIPBOARD_IN_BACKGROUND`, so a read made as the shell user also works while this app is in the background.
 */
interface ShizukuClipboardReader {
    /**
     * Reads the text of the first item of the primary clip.
     *
     * @throws IllegalStateException when Shizuku is available but the clipboard service could not be called.
     */
    suspend fun readText(): ShizukuClipboardRead
}

/**
 * Calls the hidden `IClipboard.getPrimaryClip` on the clipboard service binder wrapped by Shizuku. The SDK has no
 * public API for a read as another user, so the interface is reached through reflection after exempting it from the
 * hidden API restrictions. The method comes from the device's own framework, so its transaction code is always the
 * right one; only its arguments are chosen here, from its parameter types (see [getPrimaryClipCall]).
 */
@Singleton
class ShizukuClipboardReaderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val runner: ShizukuCommandRunner,
        private val api: ShizukuApi,
    ) : ShizukuClipboardReader {
        private val hiddenApiExempted by lazy { HiddenApiBypass.addHiddenApiExemptions(CLIPBOARD_HIDDEN_API_PREFIX) }

        override suspend fun readText(): ShizukuClipboardRead {
            if (!runner.isAvailable()) return ShizukuClipboardRead.Unavailable
            val clip = withContext(Dispatchers.IO) { primaryClip() }
            val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val text = item?.let { it.text?.toString() ?: it.coerceToText(context)?.toString() }
            return ShizukuClipboardRead.Read(text)
        }

        private fun primaryClip(): ClipData? {
            val binder =
                checkNotNull(api.systemServiceBinder(CLIPBOARD_SERVICE)) { "The clipboard service is not available" }
            check(hiddenApiExempted) { "The clipboard service interface cannot be accessed" }
            return try {
                val (method, arguments) =
                    getPrimaryClipCall(Class.forName(CLIPBOARD_INTERFACE), Process.myUid() / PER_USER_RANGE)
                val service =
                    Class
                        .forName("$CLIPBOARD_INTERFACE\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder)
                method.invoke(service, *arguments) as ClipData?
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("The clipboard service failed: ${e.targetException}", e)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("The clipboard service cannot be called: $e", e)
            }
        }
    }

/**
 * The `getPrimaryClip` method of [clipboardInterface] and its arguments, reading as the shell package for [userId].
 * The arguments are chosen from the parameter types:
 * - `(String pkg, int userId)`, used up to Android 13
 * - `(String pkg, String attributionTag, int userId, int deviceId)`, used from Android 14, with no attribution tag
 *   and the default device
 *
 * @throws IllegalStateException when no `getPrimaryClip` has one of these signatures; nothing is called then.
 */
internal fun getPrimaryClipCall(
    clipboardInterface: Class<*>,
    userId: Int,
): Pair<Method, Array<Any?>> {
    val string = String::class.java
    val int = Integer.TYPE
    return clipboardInterface.methods
        .filter { it.name == "getPrimaryClip" }
        .firstNotNullOfOrNull { method ->
            when (method.parameterTypes.toList()) {
                listOf(string, int) -> {
                    method to arrayOf<Any?>(SHELL_PACKAGE, userId)
                }

                listOf(string, string, int, int) -> {
                    method to arrayOf<Any?>(SHELL_PACKAGE, null, userId, DEFAULT_DEVICE_ID)
                }

                else -> {
                    null
                }
            }
        } ?: throw IllegalStateException(
        "This Android version has no supported IClipboard.getPrimaryClip signature; the clipboard cannot be read " +
            "through Shizuku",
    )
}

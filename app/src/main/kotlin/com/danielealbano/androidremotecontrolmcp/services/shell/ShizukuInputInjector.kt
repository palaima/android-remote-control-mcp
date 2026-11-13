package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MCP:ShizukuInputInjector"
private val INPUT_COMMAND_TIMEOUT = 10.seconds

/** Characters `input text` passes through unchanged: printable ASCII except the space, which it reads as `%s`. */
private val INPUT_TEXT_CHARS = (0x21..0x7E).map { it.toChar() }.toSet()

/**
 * Injects text and key events with the `input` command run through Shizuku. Used for typing on Android 11 and 12,
 * where an accessibility service cannot act as an input method, when the focused field does not accept
 * ACTION_SET_TEXT.
 *
 * It runs only when Shizuku is ready and elevated commands are turned on in the Shell settings. `input` only types
 * what an accessibility service can already type, so no per-command approval is asked.
 */
interface ShizukuInputInjector {
    /** Whether Shizuku is ready and elevated commands are enabled. */
    suspend fun isAvailable(): Boolean

    /**
     * Types [text] at the cursor of the focused field.
     *
     * @return false when the injector is not available, [text] holds a character `input text` cannot type (anything
     *   but printable ASCII), or the command failed.
     */
    suspend fun typeText(text: String): Boolean

    /**
     * Sends [keyCode] (a `KeyEvent.KEYCODE_*`) as a key press.
     *
     * @return false when the injector is not available or the command failed.
     */
    suspend fun pressKey(keyCode: Int): Boolean
}

@Singleton
class ShizukuInputInjectorImpl
    @Inject
    constructor(
        private val shizukuStatus: ShizukuStatusProvider,
        private val shizuku: ShizukuShellBackend,
        private val settingsRepository: SettingsRepository,
    ) : ShizukuInputInjector {
        // Negative ids keep these one-off commands apart from the shell tools' processes, which count up from 1
        private val nextProcessId = AtomicInteger(-1)

        override suspend fun isAvailable(): Boolean =
            shizukuStatus.state() is ShizukuState.Ready &&
                settingsRepository.getServerConfig().shellConfig.elevationEnabled

        override suspend fun typeText(text: String): Boolean {
            val arg = inputTextArgument(text) ?: return false
            return isAvailable() && run("input text $arg")
        }

        override suspend fun pressKey(keyCode: Int): Boolean = isAvailable() && run("input keyevent $keyCode")

        private suspend fun run(script: String): Boolean {
            val processId = nextProcessId.getAndDecrement()
            return try {
                val spawned = shizuku.spawn(processId, script)
                val exit =
                    withTimeoutOrNull(INPUT_COMMAND_TIMEOUT) {
                        withContext(Dispatchers.IO) { spawned.output.use { it.readBytes() } }
                        spawned.exit.await()
                    }
                if (exit == null) spawned.kill(null)
                exit?.code == 0
            } catch (e: IllegalStateException) {
                Log.w(TAG, "input command failed", e)
                false
            } finally {
                shizuku.release(processId)
            }
        }
    }

/**
 * [text] as one shell-quoted `input text` argument (spaces as `%s`), or null when it is empty or holds a character
 * `input text` cannot type.
 */
@Suppress("ReturnCount")
internal fun inputTextArgument(text: String): String? {
    if (text.isEmpty() || text.any { it != ' ' && it !in INPUT_TEXT_CHARS }) return null
    // `input text` reads %s as a space, so a literal "%s" in the text cannot be typed
    if (text.contains("%s")) return null
    return ShellScriptBuilder.quoteForShell(text.replace(" ", "%s"))
}

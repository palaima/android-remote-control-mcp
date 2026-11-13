package com.danielealbano.androidremotecontrolmcp.services.shell

import javax.inject.Inject
import javax.inject.Singleton

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
        private val runner: ShizukuCommandRunner,
    ) : ShizukuInputInjector {
        override suspend fun isAvailable(): Boolean = runner.isAvailable()

        override suspend fun typeText(text: String): Boolean {
            val arg = inputTextArgument(text) ?: return false
            return isAvailable() && runner.run("input text $arg")
        }

        override suspend fun pressKey(keyCode: Int): Boolean = isAvailable() && runner.run("input keyevent $keyCode")
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

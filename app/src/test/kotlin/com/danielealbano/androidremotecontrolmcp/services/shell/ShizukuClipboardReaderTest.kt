package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ShizukuClipboardReaderTest {
    @Suppress("unused", "UnusedParameter")
    interface Android13Clipboard {
        fun setPrimaryClip(
            pkg: String,
            userId: Int,
        )

        fun getPrimaryClip(
            pkg: String,
            userId: Int,
        ): Any?
    }

    @Suppress("unused", "UnusedParameter")
    interface Android14Clipboard {
        fun getPrimaryClip(
            pkg: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
        ): Any?
    }

    @Suppress("unused", "UnusedParameter")
    interface UnknownClipboard {
        fun getPrimaryClip(
            pkg: String,
            attributionTag: String?,
            userId: Int,
        ): Any?

        fun clearPrimaryClip(
            pkg: String,
            userId: Int,
        )
    }

    @Nested
    @DisplayName("getPrimaryClipCall")
    inner class GetPrimaryClipCall {
        @Test
        fun `selects the two-argument signature`() {
            val (method, arguments) = getPrimaryClipCall(Android13Clipboard::class.java, userId = 10)

            assertEquals("getPrimaryClip", method.name)
            assertArrayEquals(arrayOf<Any?>("com.android.shell", 10), arguments)
        }

        @Test
        fun `selects the four-argument signature with no attribution tag and the default device`() {
            val (method, arguments) = getPrimaryClipCall(Android14Clipboard::class.java, userId = 0)

            assertEquals("getPrimaryClip", method.name)
            assertArrayEquals(arrayOf<Any?>("com.android.shell", null, 0, 0), arguments)
        }

        @Test
        fun `fails clearly when no supported signature exists`() {
            val error =
                assertThrows<IllegalStateException> { getPrimaryClipCall(UnknownClipboard::class.java, userId = 0) }

            assertTrue(error.message!!.contains("no supported IClipboard.getPrimaryClip signature"))
        }
    }

    @Nested
    @DisplayName("readText")
    inner class ReadText {
        @Test
        fun `is unavailable without touching the clipboard service when the runner is not available`() =
            runTest {
                val runner = mockk<ShizukuCommandRunner>()
                val api = mockk<ShizukuApi>()
                coEvery { runner.isAvailable() } returns false
                val reader = ShizukuClipboardReaderImpl(mockk<Context>(), runner, api)

                assertEquals(ShizukuClipboardRead.Unavailable, reader.readText())
                verify(exactly = 0) { api.systemServiceBinder(any()) }
            }
    }
}

package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.milliseconds

class ShizukuCommandRunnerTest {
    private val status = mockk<ShizukuStatusProvider>()
    private val backend = mockk<ShizukuShellBackend>()
    private val settings = mockk<SettingsRepository>()
    private val runner = ShizukuCommandRunnerImpl(status, backend, settings)

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { status.state() } returns ShizukuState.Ready(uid = 2000)
        coEvery { settings.getServerConfig() } returns
            ServerConfig(shellConfig = ShellConfig(elevationEnabled = true))
        coEvery { backend.release(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun spawned(
        exit: CompletableDeferred<ShellExit>,
        onKill: () -> Unit = {},
    ) = SpawnedShell(ByteArrayInputStream(ByteArray(0)), exit) { _ -> onKill() }

    @Nested
    @DisplayName("isAvailable")
    inner class IsAvailable {
        @Test
        fun `is true when Shizuku is ready and elevation is enabled`() =
            runTest {
                assertTrue(runner.isAvailable())
            }

        @Test
        fun `is false when Shizuku is not ready`() =
            runTest {
                every { status.state() } returns ShizukuState.NotRunning

                assertFalse(runner.isAvailable())
            }

        @Test
        fun `is false when elevation is turned off`() =
            runTest {
                coEvery { settings.getServerConfig() } returns ServerConfig()

                assertFalse(runner.isAvailable())
            }
    }

    // Real time: run() reads the output on Dispatchers.IO, and runTest's virtual clock would fire the timeout first
    @Nested
    @DisplayName("run")
    inner class Run {
        @Test
        fun `returns true on exit code 0 and releases the process`() =
            runBlocking {
                coEvery { backend.spawn(-1, "am force-stop com.test.app") } returns
                    spawned(CompletableDeferred(ShellExit(0)))

                assertTrue(runner.run("am force-stop com.test.app"))
                coVerify { backend.release(-1) }
            }

        @Test
        fun `returns false on a non-zero exit code`() =
            runBlocking {
                coEvery { backend.spawn(any(), any()) } returns spawned(CompletableDeferred(ShellExit(1)))

                assertFalse(runner.run("false"))
            }

        @Test
        fun `uses a new negative process id for each command`() =
            runBlocking {
                coEvery { backend.spawn(any(), any()) } answers { spawned(CompletableDeferred(ShellExit(0))) }

                runner.run("true")
                runner.run("true")

                coVerify { backend.spawn(-1, "true") }
                coVerify { backend.spawn(-2, "true") }
            }

        @Test
        fun `kills the command and returns false on timeout`() =
            runBlocking {
                var killed = false
                coEvery { backend.spawn(any(), any()) } returns spawned(CompletableDeferred()) { killed = true }

                assertFalse(runner.run("sleep 100", timeout = 10.milliseconds))
                assertTrue(killed)
                coVerify { backend.release(-1) }
            }

        @Test
        fun `returns false and releases the process when Shizuku cannot start the command`() =
            runBlocking {
                coEvery { backend.spawn(any(), any()) } throws IllegalStateException("The shell helper was stopped")

                assertFalse(runner.run("true"))
                coVerify { backend.release(-1) }
                verify { Log.w(any<String>(), any<String>(), any()) }
            }
    }
}

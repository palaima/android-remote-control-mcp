package com.danielealbano.androidremotecontrolmcp.services.shell

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShizukuInputInjectorTest {
    private val runner = mockk<ShizukuCommandRunner>()
    private val injector = ShizukuInputInjectorImpl(runner)

    @Test
    fun `types quoted text through the runner when available`() =
        runTest {
            coEvery { runner.isAvailable() } returns true
            coEvery { runner.run("input text 'a%sb'", any()) } returns true

            assertTrue(injector.typeText("a b"))
        }

    @Test
    fun `presses a key through the runner when available`() =
        runTest {
            coEvery { runner.isAvailable() } returns true
            coEvery { runner.run("input keyevent 67", any()) } returns true

            assertTrue(injector.pressKey(67))
        }

    @Test
    fun `runs nothing when the runner is not available`() =
        runTest {
            coEvery { runner.isAvailable() } returns false

            assertFalse(injector.typeText("abc"))
            assertFalse(injector.pressKey(67))
            coVerify(exactly = 0) { runner.run(any(), any()) }
        }

    @Test
    fun `rejects text input text cannot type without running a command`() =
        runTest {
            coEvery { runner.isAvailable() } returns true

            assertFalse(injector.typeText("caf\u00e9"))
            coVerify(exactly = 0) { runner.run(any(), any()) }
        }
}

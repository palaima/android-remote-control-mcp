package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "MCP:ShizukuCommandRunner"
private val DEFAULT_COMMAND_TIMEOUT = 10.seconds

/**
 * Runs short, app-built commands as the shell user through Shizuku, for features that need shell privileges
 * (typing with `input`, force-stopping apps, reading the clipboard in the background).
 *
 * It is available only when Shizuku is ready and elevated commands are turned on in the Shell settings. Callers
 * build the command themselves and must validate anything they put in it.
 */
interface ShizukuCommandRunner {
    /** Whether Shizuku is ready and elevated commands are enabled. */
    suspend fun isAvailable(): Boolean

    /**
     * Runs [script] and waits up to [timeout] for it to finish.
     *
     * @return true when the command exited with code 0; false when it failed, timed out (it is then killed), or
     *   Shizuku could not start it. It does not check [isAvailable].
     */
    suspend fun run(
        script: String,
        timeout: Duration = DEFAULT_COMMAND_TIMEOUT,
    ): Boolean
}

@Singleton
class ShizukuCommandRunnerImpl
    @Inject
    constructor(
        private val shizukuStatus: ShizukuStatusProvider,
        private val shizuku: ShizukuShellBackend,
        private val settingsRepository: SettingsRepository,
    ) : ShizukuCommandRunner {
        // Negative ids keep these one-off commands apart from the shell tools' processes, which count up from 1
        private val nextProcessId = AtomicInteger(-1)

        override suspend fun isAvailable(): Boolean =
            shizukuStatus.state() is ShizukuState.Ready &&
                settingsRepository.getServerConfig().shellConfig.elevationEnabled

        override suspend fun run(
            script: String,
            timeout: Duration,
        ): Boolean {
            val processId = nextProcessId.getAndDecrement()
            return try {
                val spawned = shizuku.spawn(processId, script)
                val exit =
                    withTimeoutOrNull(timeout) {
                        withContext(Dispatchers.IO) { spawned.output.use { it.readBytes() } }
                        spawned.exit.await()
                    }
                if (exit == null) spawned.kill(null)
                exit?.code == 0
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Shizuku command failed", e)
                false
            } finally {
                shizuku.release(processId)
            }
        }
    }

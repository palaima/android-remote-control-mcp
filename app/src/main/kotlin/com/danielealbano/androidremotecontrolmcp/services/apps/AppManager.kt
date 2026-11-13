package com.danielealbano.androidremotecontrolmcp.services.apps

import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo

/**
 * Manages application lifecycle operations: listing, launching, and closing apps.
 */
interface AppManager {
    /**
     * Lists installed applications with optional filtering.
     *
     * @param filter Filter by app type (ALL, USER, SYSTEM).
     * @param nameQuery Optional case-insensitive substring to filter by app name.
     * @return List of matching [AppInfo] entries sorted by name.
     */
    suspend fun listInstalledApps(
        filter: AppFilter = AppFilter.ALL,
        nameQuery: String? = null,
    ): List<AppInfo>

    /**
     * Launches an application by its package ID.
     *
     * @param packageId The application package name (e.g., "com.example.app").
     * @return [Result.success] if the app was launched, [Result.failure] if not found
     *         or the app has no launchable activity.
     */
    suspend fun openApp(packageId: String): Result<Unit>

    /**
     * Closes an application.
     *
     * When Shizuku is ready and elevated commands are enabled, the app is force-stopped with `am force-stop` run
     * as the shell user, which stops all its processes, foreground ones included. Otherwise it falls back to
     * [android.app.ActivityManager.killBackgroundProcesses], which only kills cached background processes and
     * cannot report whether anything was killed.
     *
     * @param packageId The application package name; it must be a valid package name.
     * @return [Result.success] with the [AppCloseMethod] used, or [Result.failure] when [packageId] is not a valid
     *         package name or `am force-stop` failed.
     */
    suspend fun closeApp(packageId: String): Result<AppCloseMethod>
}

/** How [AppManager.closeApp] closed an app. */
enum class AppCloseMethod {
    /** All processes of the app were stopped with `am force-stop`. */
    FORCE_STOPPED,

    /** Only a request to kill cached background processes was made; the result cannot be verified. */
    BACKGROUND_KILL_REQUESTED,
}

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** Whether [packageId] is a valid Android package name: two or more dot-separated Java-style segments. */
fun isValidPackageName(packageId: String): Boolean = PACKAGE_NAME_REGEX.matches(packageId)

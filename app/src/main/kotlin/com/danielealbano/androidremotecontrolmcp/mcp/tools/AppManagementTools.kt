package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.apps.AppCloseMethod
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import com.danielealbano.androidremotecontrolmcp.services.apps.isValidPackageName
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// open_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_app`.
 *
 * Opens (launches) an application by its package ID.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Application '<package_id>' launched successfully." }] }`
 */
class OpenAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")
            if (packageId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'package_id' must not be empty")
            }

            Log.d(TAG, "Executing open_app for package: $packageId")
            val result = appManager.openApp(packageId)
            result.onFailure { e ->
                throw McpToolException.ActionFailed("Failed to open application '$packageId': ${e.message}")
            }
            return McpToolUtils.textResult("Application '$packageId' launched successfully.")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Opens (launches) an application by its package ID. " +
                        "The app must be installed and have a launchable activity. " +
                        "Returns after the launch intent is sent.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("package_id") {
                                    put("type", "string")
                                    put("description", "The application package name (e.g., 'com.example.app')")
                                }
                            },
                        required = listOf("package_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "open_app"
            private const val TAG = "MCP:OpenAppHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// list_apps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_apps`.
 *
 * Lists installed applications on the device with optional filtering.
 *
 * **Input**: `{ "filter": "all"|"user"|"system" (optional), "name_query": "<string>" (optional) }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "[{\"package_id\":\"...\",\"name\":\"...\", ...}]" }] }`
 */
class ListAppsHandler
    @Inject
    constructor(
        private val appManager: AppManager,
        private val privacyToolGate: PrivacyToolGate,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val filterStr = McpToolUtils.optionalString(arguments, "filter", "all")
            val filter =
                when (filterStr.lowercase()) {
                    "all" -> AppFilter.ALL

                    "user" -> AppFilter.USER

                    "system" -> AppFilter.SYSTEM

                    else -> throw McpToolException.InvalidParams(
                        "Parameter 'filter' must be one of: 'all', 'user', 'system'. Got: '$filterStr'",
                    )
                }

            val nameQuery = McpToolUtils.optionalString(arguments, "name_query", "").ifEmpty { null }

            Log.d(TAG, "Executing list_apps with filter=$filter, nameQuery=$nameQuery")
            val apps = appManager.listInstalledApps(filter, nameQuery)
            val redactedNames = privacyToolGate.texts(apps.map { it.name to "app name" })

            val jsonResult =
                buildJsonArray {
                    apps.forEachIndexed { index, app ->
                        add(
                            buildJsonObject {
                                put("package_id", app.packageId)
                                put("name", redactedNames[index])
                                if (app.versionName != null) {
                                    put("version_name", app.versionName)
                                }
                                put("version_code", app.versionCode)
                                put("is_system", app.isSystemApp)
                            },
                        )
                    }
                }

            return McpToolUtils.untrustedTextResult(Json.encodeToString(jsonResult))
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Lists installed applications on the device. " +
                        "Can filter by type (user, system, all) and by name substring.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("filter") {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("all"))
                                            add(JsonPrimitive("user"))
                                            add(JsonPrimitive("system"))
                                        },
                                    )
                                    put("description", "Filter by app type: 'all', 'user', or 'system'")
                                    put("default", "all")
                                }
                                putJsonObject("name_query") {
                                    put("type", "string")
                                    put("description", "Case-insensitive substring to filter by app name")
                                }
                            },
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_apps"
            private const val TAG = "MCP:ListAppsHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// close_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `close_app`.
 *
 * Closes an application: force-stops it through Shizuku when available, otherwise asks Android to kill its cached
 * background processes.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "Application '<package_id>' was force-stopped..." }] }`
 */
class CloseAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")
            if (!isValidPackageName(packageId)) {
                throw McpToolException.InvalidParams(
                    if (packageId.isEmpty()) {
                        "Parameter 'package_id' must not be empty"
                    } else {
                        "Parameter 'package_id' is not a valid package name: '$packageId'"
                    },
                )
            }

            Log.d(TAG, "Executing close_app for package: $packageId")
            val result = appManager.closeApp(packageId)
            val method =
                result.getOrElse { e ->
                    throw McpToolException.ActionFailed("Failed to close application '$packageId': ${e.message}")
                }
            return McpToolUtils.textResult(
                when (method) {
                    AppCloseMethod.FORCE_STOPPED -> {
                        "Application '$packageId' was force-stopped: all its processes were stopped."
                    }

                    AppCloseMethod.BACKGROUND_KILL_REQUESTED -> {
                        "Requested Android to kill the cached background processes of '$packageId'. This cannot " +
                            "be verified, and processes that are in the foreground or running a foreground " +
                            "service keep running. Enable Shizuku and elevated commands in the app's Shell " +
                            "settings to force-stop the app fully."
                    }
                },
            )
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Closes an application. When Shizuku is ready and elevated commands are enabled, the app is " +
                        "force-stopped ('am force-stop'), which stops all its processes, foreground ones " +
                        "included. Without Shizuku, Android only allows killing cached background processes: " +
                        "first use the '${toolNamePrefix}press_home' tool to send the app to the background; " +
                        "the result cannot be verified and apps with a foreground service keep running. The " +
                        "result text says which method was used. Note: some system processes may restart " +
                        "automatically after being stopped.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("package_id") {
                                    put("type", "string")
                                    put("description", "The application package name (e.g., 'com.example.app')")
                                }
                            },
                        required = listOf("package_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "close_app"
            private const val TAG = "MCP:CloseAppHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all app management tools with the given [Server].
 */
fun registerAppManagementTools(
    registrar: LoggedToolRegistrar,
    appManager: AppManager,
    privacyToolGate: PrivacyToolGate,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(OpenAppHandler.TOOL_NAME)) OpenAppHandler(appManager).register(registrar, toolNamePrefix)
    if (perms.isToolEnabled(ListAppsHandler.TOOL_NAME)) {
        ListAppsHandler(appManager, privacyToolGate).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(CloseAppHandler.TOOL_NAME)) CloseAppHandler(appManager).register(registrar, toolNamePrefix)
}

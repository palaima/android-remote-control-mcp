package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo
import com.danielealbano.androidremotecontrolmcp.services.apps.AppCloseMethod
import io.mockk.coEvery
import io.mockk.coVerify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("App Management Tools Integration Tests")
class AppManagementToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `open_app with valid package_id launches app successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.appManager.openApp("com.test.app") } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("launched successfully"))
            }
        }

    @Test
    fun `open_app with unknown package_id returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.appManager.openApp("com.unknown")
            } returns Result.failure(IllegalArgumentException("No launchable activity"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = mapOf("package_id" to "com.unknown"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Failed to open"))
            }
        }

    @Test
    fun `open_app with missing package_id returns invalid params error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_open_app",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }

    @Test
    fun `list_apps with no filter returns all apps`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val app1 =
                AppInfo(
                    packageId = "com.test.app1",
                    name = "Test App 1",
                    versionName = "1.0.0",
                    versionCode = 1L,
                    isSystemApp = false,
                )
            val app2 =
                AppInfo(
                    packageId = "com.test.app2",
                    name = "Test App 2",
                    versionName = "2.0.0",
                    versionCode = 2L,
                    isSystemApp = false,
                )
            coEvery {
                deps.appManager.listInstalledApps(any(), any())
            } returns listOf(app1, app2)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_apps",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Test App 1"))
                assertTrue(text.contains("Test App 2"))
            }
        }

    @Test
    fun `list_apps with user filter returns only user apps`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val userApp =
                AppInfo(
                    packageId = "com.user.app",
                    name = "User App",
                    versionName = "1.0.0",
                    versionCode = 1L,
                    isSystemApp = false,
                )
            coEvery {
                deps.appManager.listInstalledApps(AppFilter.USER, null)
            } returns listOf(userApp)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_apps",
                        arguments = mapOf("filter" to "user"),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    @Test
    fun `list_apps with system filter returns only system apps`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val systemApp =
                AppInfo(
                    packageId = "com.system.app",
                    name = "System App",
                    versionName = "1.0.0",
                    versionCode = 1L,
                    isSystemApp = true,
                )
            coEvery {
                deps.appManager.listInstalledApps(AppFilter.SYSTEM, null)
            } returns listOf(systemApp)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_apps",
                        arguments = mapOf("filter" to "system"),
                    )
                assertNotEquals(true, result.isError)
            }
        }

    @Test
    fun `list_apps with name_query filters by name`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val calculatorApp =
                AppInfo(
                    packageId = "com.android.calculator",
                    name = "Calculator",
                    versionName = "1.0.0",
                    versionCode = 1L,
                    isSystemApp = false,
                )
            coEvery {
                deps.appManager.listInstalledApps(AppFilter.ALL, "calc")
            } returns listOf(calculatorApp)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_apps",
                        arguments = mapOf("name_query" to "calc"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Calculator"))
            }
        }

    @Test
    fun `list_apps with invalid filter returns error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_apps",
                        arguments = mapOf("filter" to "invalid"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("must be one of"))
            }
        }

    @Test
    fun `close_app reports a force-stop`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.appManager.closeApp("com.test.app") } returns Result.success(AppCloseMethod.FORCE_STOPPED)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("was force-stopped"))
            }
        }

    @Test
    fun `close_app reports an unverified background kill without Shizuku`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.appManager.closeApp("com.test.app")
            } returns Result.success(AppCloseMethod.BACKGROUND_KILL_REQUESTED)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("cannot be verified"))
                assertTrue(text.contains("Shizuku"))
            }
        }

    @Test
    fun `close_app reports a failed force-stop as an error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.appManager.closeApp("com.test.app")
            } returns Result.failure(IllegalStateException("'am force-stop' failed for com.test.app"))

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = mapOf("package_id" to "com.test.app"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("am force-stop"))
            }
        }

    @Test
    fun `close_app rejects an invalid package name`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = mapOf("package_id" to "com.test.app; reboot"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("not a valid package name"))
                coVerify(exactly = 0) { deps.appManager.closeApp(any()) }
            }
        }

    @Test
    fun `close_app with missing package_id returns invalid params error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_close_app",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Missing required parameter"))
            }
        }
}

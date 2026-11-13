package com.danielealbano.androidremotecontrolmcp.testutil

import android.os.Build
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import sun.misc.Unsafe

/** The API level [SdkIntExtension] sets by default. */
const val TEST_SDK_INT = 36

/**
 * Sets Build.VERSION.SDK_INT, which is 0 on the JVM, to [sdkInt] for a test class, so code gated on the API
 * level takes the current-Android path, then puts it back to 0. Use it with `@ExtendWith(SdkIntExtension::class)`.
 * It is not applied to all tests because libraries such as OkHttp switch to their Android code when SDK_INT is set.
 */
open class SdkIntExtension(
    private val sdkInt: Int = TEST_SDK_INT,
) : BeforeAllCallback,
    AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) = setSdkInt(sdkInt)

    override fun afterAll(context: ExtensionContext) = setSdkInt(0)
}

/** Sets Build.VERSION.SDK_INT to [value]. */
@Suppress("DiscouragedPrivateApi")
fun setSdkInt(value: Int) {
    val unsafe =
        Unsafe::class.java
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as Unsafe
    val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
    unsafe.putInt(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), value)
}

/** [SdkIntExtension] at Android 13 (API 33), for code with paths for Android 14 and later the tests do not cover. */
class Sdk33Extension : SdkIntExtension(33)

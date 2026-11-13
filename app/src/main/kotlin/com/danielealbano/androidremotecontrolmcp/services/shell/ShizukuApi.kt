package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import javax.inject.Inject

interface ShizukuApi {
    fun pingBinder(): Boolean

    fun version(): Int

    fun uid(): Int

    fun isPermissionGranted(): Boolean

    fun requestPermission(requestCode: Int)

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)

    suspend fun bindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
    )

    fun unbindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
    )

    /**
     * The binder of system service [name] (as passed to `ServiceManager.getService`), wrapped so that calls on it
     * run as the Shizuku server's user (shell or root). Null when the service does not exist.
     */
    fun systemServiceBinder(name: String): IBinder?
}

class ShizukuApiImpl
    @Inject
    constructor() : ShizukuApi {
        override fun pingBinder(): Boolean = Shizuku.pingBinder()

        override fun version(): Int = Shizuku.getVersion()

        override fun uid(): Int = Shizuku.getUid()

        override fun isPermissionGranted(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

        override fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) =
            Shizuku.addRequestPermissionResultListener(listener)

        override fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            Shizuku.removeRequestPermissionResultListener(listener)
        }

        override suspend fun bindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) = Shizuku.unbindUserService(args, connection, true)

        override fun systemServiceBinder(name: String): IBinder? =
            SystemServiceHelper.getSystemService(name)?.let { ShizukuBinderWrapper(it) }
    }

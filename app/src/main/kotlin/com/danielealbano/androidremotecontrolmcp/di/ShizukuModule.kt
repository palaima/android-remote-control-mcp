package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuClipboardReader
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuClipboardReaderImpl
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuCommandRunner
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuCommandRunnerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Shizuku helpers non-shell tools use: one-off shell-user commands and the background clipboard read. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShizukuModule {
    @Binds
    @Singleton
    abstract fun bindShizukuCommandRunner(impl: ShizukuCommandRunnerImpl): ShizukuCommandRunner

    @Binds
    @Singleton
    abstract fun bindShizukuClipboardReader(impl: ShizukuClipboardReaderImpl): ShizukuClipboardReader
}

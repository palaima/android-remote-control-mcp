package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuInputInjector
import com.danielealbano.androidremotecontrolmcp.services.shell.ShizukuInputInjectorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Shizuku `input` injector the Android 11 and 12 typing fallback uses. */
@Module
@InstallIn(SingletonComponent::class)
abstract class InputModule {
    @Binds
    @Singleton
    abstract fun bindShizukuInputInjector(impl: ShizukuInputInjectorImpl): ShizukuInputInjector
}

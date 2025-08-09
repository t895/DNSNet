/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.blocklogger.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.clombardo.dnsnet.blocklogger.BlockLogger
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BlockLoggerModule {
    @Provides
    @Singleton
    fun provideBlockLogger(@ApplicationContext context: Context): BlockLogger {
        return BlockLogger.load(context)
    }
}

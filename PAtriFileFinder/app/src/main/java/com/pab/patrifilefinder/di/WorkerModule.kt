package com.pab.patrifilefinder.di

import com.pab.patrifilefinder.worker.FileScanWorker
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Hilt's @HiltWorker + @AssistedInject handles worker injection automatically.
// No explicit @Provides needed — this module exists as the install anchor if
// we later need to bind additional worker-scoped dependencies.
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule

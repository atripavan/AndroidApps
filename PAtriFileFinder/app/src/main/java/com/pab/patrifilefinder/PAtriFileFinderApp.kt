package com.pab.patrifilefinder

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pab.patrifilefinder.worker.FileScanWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PAtriFileFinderApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // PdfBox needs its font/resource assets loaded once before any text extraction.
        PDFBoxResourceLoader.init(this)
        FileScanWorker.schedule(this)
    }
}

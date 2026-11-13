package com.foxegg.papergrade

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class PaperGradeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenCV init failed", error)
            false
        }
        Log.i(TAG, "OpenCV ready=$openCvReady")
    }

    companion object {
        @Volatile
        var openCvReady: Boolean = false

        private const val TAG = "PaperGrade"
    }
}

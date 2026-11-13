package com.foxegg.papergrade.camera

import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 只负责预览、取帧和对焦锁定。这里不调用批改接口。
 */
class CameraController(
    private val activity: ComponentActivity,
    private val onError: (String) -> Unit,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "paper-camera").apply { isDaemon = true }
    }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var generation = 0
    private var lockCallback: Runnable? = null

    var isLocked: Boolean = false
        private set

    fun bind(previewView: PreviewView, onFrame: (Bitmap) -> Unit) {
        val nextGeneration = ++generation
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            if (nextGeneration != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                bindUseCases(cameraProvider, previewView, onFrame, nextGeneration)
            } catch (error: Exception) {
                Log.e(TAG, "bind", error)
                onError("打不开相机")
            }
        }, mainExecutor)
    }

    fun unbind() {
        generation++
        lockCallback?.let { mainHandler.removeCallbacks(it) }
        lockCallback = null
        try {
            provider?.unbindAll()
        } catch (error: Exception) {
            Log.w(TAG, "unbind", error)
        }
        camera = null
        isLocked = false
    }

    fun shutdown() {
        unbind()
        analyzerExecutor.shutdown()
    }

    fun focusAndLock(previewView: PreviewView, x: Float, y: Float): Boolean {
        val active = camera ?: return false
        val width = previewView.width.toFloat().coerceAtLeast(1f)
        val height = previewView.height.toFloat().coerceAtLeast(1f)
        val point = previewView.meteringPointFactory.createPoint(
            x.coerceIn(0f, width),
            y.coerceIn(0f, height),
        )
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).build()
        isLocked = true
        val future = active.cameraControl.startFocusAndMetering(action)
        future.addListener({ applyLock() }, mainExecutor)
        lockCallback?.let { mainHandler.removeCallbacks(it) }
        val pending = Runnable { applyLock() }
        lockCallback = pending
        mainHandler.postDelayed(pending, 700)
        return true
    }

    fun unlock() {
        isLocked = false
        lockCallback?.let { mainHandler.removeCallbacks(it) }
        lockCallback = null
        val active = camera ?: return
        try {
            Camera2CameraControl.from(active.cameraControl).clearCaptureRequestOptions()
            active.cameraControl.cancelFocusAndMetering()
        } catch (error: Exception) {
            Log.w(TAG, "unlock", error)
        }
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView,
        onFrame: (Bitmap) -> Unit,
        boundGeneration: Int,
    ) {
        if (boundGeneration != generation) return
        if (previewView.width == 0 || previewView.height == 0) {
            previewView.post { bindUseCases(cameraProvider, previewView, onFrame, boundGeneration) }
            return
        }
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()
        preview.surfaceProvider = previewView.surfaceProvider
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analyzerExecutor) { image ->
            try {
                val bitmap = FrameImages.toUprightCropped(image)
                onFrame(bitmap)
            } catch (error: Exception) {
                Log.e(TAG, "analyze", error)
            } finally {
                image.close()
            }
        }
        val viewPort = ViewPort.Builder(
            Rational(previewView.width, previewView.height),
            rotation,
        ).setScaleType(ViewPort.FILL_CENTER).build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build()
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, group)
        } catch (error: Exception) {
            Log.e(TAG, "bindToLifecycle", error)
            onError("打不开相机")
        }
    }

    private fun applyLock() {
        if (!isLocked) return
        val active = camera ?: return
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF,
                )
                .build()
            Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(options)
        } catch (error: Exception) {
            Log.w(TAG, "lock", error)
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}

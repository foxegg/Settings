package com.foxegg.papergrade

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.foxegg.papergrade.camera.CameraController
import com.foxegg.papergrade.camera.FrameStore
import com.foxegg.papergrade.databinding.ActivityMainBinding
import com.foxegg.papergrade.geometry.PaperMesh
import com.foxegg.papergrade.grading.GradingClient
import com.foxegg.papergrade.grading.GradingDefaults
import com.foxegg.papergrade.grading.SampleMarks
import com.foxegg.papergrade.model.Mark
import com.foxegg.papergrade.model.TrackStatus
import com.foxegg.papergrade.tracking.PaperTracker
import com.foxegg.papergrade.tracking.TrackResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private val tracker = PaperTracker()
    private val frameStore = FrameStore()
    private val gradingClient = GradingClient()
    private val gradeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "paper-grade").apply { isDaemon = true }
    }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var cameraStarted = false
    private var grading = false
    private var finished = false
    private var autoLockRequested = false
    private var currentMarks: List<Mark> = emptyList()
    private var lastViewMesh: PaperMesh? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCamera()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraController = CameraController(this) { message ->
            if (!finished) binding.topBanner.text = message
        }
        applyInsets()
        binding.demoSwitch.isChecked = apiKey().isBlank()
        binding.gradeStatus.setText(R.string.grade_idle)
        binding.permissionButton.setOnClickListener { onPermissionButton() }
        binding.lockButton.setOnClickListener { onLockButton() }
        binding.gradeButton.setOnClickListener { onGrade() }
        refreshLockButton()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) showCamera() else showPermissionDenied()
    }

    override fun onDestroy() {
        finished = true
        cameraController.shutdown()
        tracker.release()
        frameStore.clear()
        gradeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun showCamera() {
        binding.permissionPanel.visibility = View.GONE
        binding.bottomPanel.visibility = View.VISIBLE
        binding.topBanner.visibility = View.VISIBLE
        if (!PaperGradeApp.openCvReady) {
            binding.topBanner.setText(R.string.opencv_failed)
        }
        if (!cameraStarted) {
            cameraStarted = true
            cameraController.bind(binding.previewView, ::onFrame)
        }
    }

    private fun showPermissionDenied() {
        cameraStarted = false
        cameraController.unbind()
        binding.permissionPanel.visibility = View.VISIBLE
        binding.bottomPanel.visibility = View.GONE
        binding.topBanner.visibility = View.GONE
        val asked = prefs.getBoolean(KEY_ASKED, false)
        val permanent = asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        binding.permissionButton.setText(
            if (permanent) R.string.permission_settings else R.string.permission_grant,
        )
    }

    private fun onPermissionButton() {
        val asked = prefs.getBoolean(KEY_ASKED, false)
        val permanent = asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (permanent) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } else {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onFrame(bitmap: Bitmap) {
        if (finished) {
            bitmap.recycle()
            return
        }
        val result = tracker.process(bitmap)
        frameStore.set(bitmap, result.quad)
        runOnUiThread {
            if (finished) return@runOnUiThread
            val viewMesh = viewMeshOf(result)
            lastViewMesh = viewMesh
            binding.overlayView.setPose(viewMesh, result.status, result.needsFlattening)
            updateBanner(result)
            maybeAutoLock(result, viewMesh)
        }
    }

    private fun viewMeshOf(result: TrackResult): PaperMesh? {
        val mesh = result.mesh ?: return null
        val overlayWidth = binding.overlayView.width
        val overlayHeight = binding.overlayView.height
        if (overlayWidth <= 0 || overlayHeight <= 0 || result.imageWidth <= 0 || result.imageHeight <= 0) {
            return null
        }
        return mesh.scaled(
            overlayWidth.toFloat() / result.imageWidth,
            overlayHeight.toFloat() / result.imageHeight,
        )
    }

    private fun onGrade() {
        if (grading) return
        val snapshot = frameStore.copy()
        if (snapshot == null) {
            binding.gradeStatus.setText(R.string.need_paper)
            return
        }
        val (bitmap, quad) = snapshot
        if (binding.demoSwitch.isChecked) {
            currentMarks = SampleMarks.load()
            binding.overlayView.setMarks(currentMarks)
            binding.gradeStatus.setText(R.string.demo_applied)
            bitmap.recycle()
            return
        }
        if (apiKey().isBlank()) {
            binding.gradeStatus.setText(R.string.need_key)
            bitmap.recycle()
            return
        }
        val rubric = binding.rubricInput.text?.toString()?.trim().orEmpty()
        if (rubric.isBlank()) {
            binding.gradeStatus.setText(R.string.need_rubric)
            bitmap.recycle()
            return
        }
        grading = true
        binding.gradeButton.isEnabled = false
        binding.gradeStatus.setText(R.string.grading)
        currentMarks = emptyList()
        binding.overlayView.setMarks(currentMarks)
        val rectifyFailed = getString(R.string.rectify_failed)
        val base = apiBase()
        val model = apiModel()
        val key = apiKey()
        gradeExecutor.execute {
            var rectified: Bitmap? = null
            try {
                rectified = tracker.rectify(bitmap, quad) ?: throw IllegalStateException(rectifyFailed)
                val jpeg = ByteArrayOutputStream()
                if (!rectified.compress(Bitmap.CompressFormat.JPEG, 82, jpeg)) {
                    throw IllegalStateException(rectifyFailed)
                }
                val marks = gradingClient.grade(jpeg.toByteArray(), rubric, key, base, model)
                runOnUiThread {
                    if (finished) return@runOnUiThread
                    currentMarks = marks
                    binding.overlayView.setMarks(marks)
                    binding.gradeStatus.text = if (marks.isEmpty()) {
                        getString(R.string.marks_empty)
                    } else {
                        getString(R.string.marks_applied, marks.size)
                    }
                }
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.grade_failed)
                runOnUiThread {
                    if (!finished) binding.gradeStatus.text = message
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                rectified?.let { if (!it.isRecycled) it.recycle() }
                runOnUiThread {
                    grading = false
                    if (!finished) binding.gradeButton.isEnabled = true
                }
            }
        }
    }

    private fun onLockButton() {
        if (cameraController.isLocked) {
            cameraController.unlock()
            autoLockRequested = true
        } else {
            val center = currentViewCenter()
            if (cameraController.focusAndLock(binding.previewView, center.first, center.second)) {
                autoLockRequested = true
            }
        }
        refreshLockButton()
    }

    private fun maybeAutoLock(result: TrackResult, viewMesh: PaperMesh?) {
        if (!result.stable || viewMesh == null || autoLockRequested || cameraController.isLocked) return
        val center = viewMesh.map(0.5f, 0.5f)
        if (cameraController.focusAndLock(binding.previewView, center.x, center.y)) {
            autoLockRequested = true
            refreshLockButton()
        }
    }

    private fun currentViewCenter(): Pair<Float, Float> {
        val center = lastViewMesh?.map(0.5f, 0.5f)
        if (center != null) return center.x to center.y
        val width = binding.previewView.width.toFloat().coerceAtLeast(1f)
        val height = binding.previewView.height.toFloat().coerceAtLeast(1f)
        return width / 2f to height / 2f
    }

    private fun updateBanner(result: TrackResult) {
        if (!PaperGradeApp.openCvReady) {
            binding.topBanner.setText(R.string.opencv_failed)
            return
        }
        val flatten = result.needsFlattening && result.status != TrackStatus.SEARCHING
        val base = when (result.status) {
            TrackStatus.SEARCHING -> getString(R.string.track_searching)
            TrackStatus.TRACKING -> getString(R.string.track_ok)
            TrackStatus.OCCLUDED -> getString(R.string.track_occluded)
            TrackStatus.LOST -> getString(R.string.track_lost)
        }
        binding.topBanner.text = if (flatten) base + getString(R.string.track_flatten_suffix) else base
        if (flatten) {
            binding.topBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.warn_bg))
            binding.topBanner.setTextColor(ContextCompat.getColor(this, R.color.warn_ink))
        } else {
            binding.topBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.banner))
            binding.topBanner.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
        }
    }

    private fun refreshLockButton() {
        binding.lockButton.setText(if (cameraController.isLocked) R.string.unlock_focus else R.string.lock_focus)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBanner) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
            params.topMargin = bars.top + dp(8)
            params.leftMargin = bars.left + dp(12)
            params.rightMargin = bars.right + dp(12)
            view.layoutParams = params
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val base = dp(16)
            view.updatePadding(
                left = base + bars.left,
                top = base,
                right = base + bars.right,
                bottom = base + max(bars.bottom, ime.bottom),
            )
            insets
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun apiBase(): String = BuildConfig.GRADING_API_BASE.ifBlank { GradingDefaults.API_BASE }

    private fun apiModel(): String = BuildConfig.GRADING_API_MODEL.ifBlank { GradingDefaults.MODEL }

    private fun apiKey(): String = BuildConfig.GRADING_API_KEY.trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREFS = "paper_grade"
        const val KEY_ASKED = "asked_camera"
    }
}

package dev.apksim.composeapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * apksim://camera: the front camera through CameraX, a Preview in a PreviewView and an ImageAnalysis counting frames
 * ("camera: 42 frames, 1280x720 YUV, rotation 270"). Asks for the CAMERA permission first. `?mode=compatible` makes the
 * PreviewView use a TextureView (PERFORMANCE, the default, a SurfaceView). On apksim it needs `--camera fake`.
 */
class CameraActivity : ComponentActivity() {
  private lateinit var status: TextView
  private lateinit var preview: PreviewView
  private val analysis = Executors.newSingleThreadExecutor()

  private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) bind() else status.text = "camera: permission denied"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)
    preview = PreviewView(this)
    if (intent?.data?.getQueryParameter("mode") == "compatible") {
      preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    root.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    status = TextView(this).apply {
      text = "camera: starting"
      setTextColor(Color.WHITE)
      setBackgroundColor(0x80000000.toInt())
      textSize = 16f
      setPadding(32, 24, 32, 24)
    }
    root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    setContentView(root)
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
      status.text = "camera: this device has none"
    } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      bind()
    } else {
      ask.launch(Manifest.permission.CAMERA)
    }
  }

  private fun bind() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      val provider = try {
        future.get()
      } catch (e: Exception) {
        status.text = "camera: ${e.cause ?: e}"
        return@addListener
      }
      val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
      val a = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
      a.setAnalyzer(analysis) { image ->
        val format = if (image.format == android.graphics.ImageFormat.YUV_420_888) "YUV" else "format ${image.format}"
        last = "${image.width}x${image.height} $format, rotation ${image.imageInfo.rotationDegrees}"
        frames.incrementAndGet()
        image.close()
      }
      try {
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p, a)
      } catch (e: Exception) {
        status.text = "camera: $e"
        return@addListener
      }
      tick()
    }, ContextCompat.getMainExecutor(this))
  }

  private fun tick() {
    if (isDestroyed) return
    status.text = "camera: ${frames.get()} frames" + (if (last.isEmpty()) "" else ", $last")
    status.postDelayed(::tick, 250)
  }

  /** What the preview shows (CameraTest). */
  fun previewBitmap(): android.graphics.Bitmap? = preview.bitmap

  companion object {
    /** Frames the analysis got, and the last one's size, format and rotation (CameraTest reads them). */
    val frames = AtomicLong()
    @Volatile var last = ""
  }

  override fun onDestroy() {
    analysis.shutdown()
    super.onDestroy()
  }
}

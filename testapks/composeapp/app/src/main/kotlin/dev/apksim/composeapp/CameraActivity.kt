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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * apksim://camera: the front camera through CameraX, a Preview in a PreviewView and an ImageAnalysis counting frames
 * ("camera: 42 frames, 640x480 YUV, rotation 270"). Asks for the CAMERA permission first. `?mode=compatible` makes the
 * PreviewView use a TextureView (PERFORMANCE, the default, a SurfaceView). `?size=1440x1080` binds the Preview alone, to
 * exactly that size (a 4:3 aspect ratio strategy and `ResolutionStrategy(size, FALLBACK_RULE_NONE)`, so a camera that
 * does not list it fails the bind: "No available output size is found"): "camera: preview 1440x1080".
 * `?analysis=1440x1080` does the same for the ImageAnalysis, beside a default Preview ("camera: 42 frames, 1440x1080
 * YUV, rotation 270"). Not both: on a display smaller than the size CameraX counts it as a RECORD stream, and no
 * LIMITED camera guarantees two of those. On apksim it needs `--camera fake`.
 */
class CameraActivity : ComponentActivity() {
  private lateinit var status: TextView
  private lateinit var preview: PreviewView
  private val analysis = Executors.newSingleThreadExecutor()
  private var previewUseCase: Preview? = null

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
      val exactPreview = exact("size")
      val exactAnalysis = exact("analysis")
      val p = Preview.Builder().apply { if (exactPreview != null) setResolutionSelector(exactPreview) }.build()
        .also { it.surfaceProvider = preview.surfaceProvider }
      val a = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .apply { if (exactAnalysis != null) setResolutionSelector(exactAnalysis) }.build()
      previewUseCase = if (exactPreview != null) p else null
      a.setAnalyzer(analysis) { image ->
        val format = if (image.format == android.graphics.ImageFormat.YUV_420_888) "YUV" else "format ${image.format}"
        last = "${image.width}x${image.height} $format, rotation ${image.imageInfo.rotationDegrees}"
        frames.incrementAndGet()
        image.close()
      }
      try {
        provider.unbindAll()
        if (exactPreview != null) {
          provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p)
        } else {
          provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p, a)
        }
      } catch (e: Exception) {
        status.text = "camera: $e"
        return@addListener
      }
      tick()
    }, ContextCompat.getMainExecutor(this))
  }

  /** A selector for exactly the size the `name` query parameter gives ("1440x1080"), or null without one. */
  private fun exact(name: String): ResolutionSelector? {
    val wh = intent?.data?.getQueryParameter(name)?.split("x")?.mapNotNull { it.toIntOrNull() } ?: return null
    if (wh.size != 2) return null
    return ResolutionSelector.Builder()
      .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
      .setResolutionStrategy(ResolutionStrategy(android.util.Size(wh[0], wh[1]), ResolutionStrategy.FALLBACK_RULE_NONE))
      .build()
  }

  private fun tick() {
    if (isDestroyed) return
    val got = previewUseCase?.resolutionInfo?.resolution
    previewSize = if (got == null) "" else "${got.width}x${got.height}"
    status.text = when {
      previewUseCase != null -> "camera: preview " + previewSize.ifEmpty { "starting" }
      else -> "camera: ${frames.get()} frames" + (if (last.isEmpty()) "" else ", $last")
    }
    status.postDelayed(::tick, 250)
  }

  /** What the preview shows (CameraTest). */
  fun previewBitmap(): android.graphics.Bitmap? = preview.bitmap

  companion object {
    /** Frames the analysis got, and the last one's size, format and rotation (CameraTest reads them). */
    val frames = AtomicLong()
    @Volatile var last = ""
    /** With `?size=`, the resolution the Preview was bound at ("1440x1080"). */
    @Volatile var previewSize = ""
  }

  override fun onDestroy() {
    analysis.shutdown()
    super.onDestroy()
  }
}

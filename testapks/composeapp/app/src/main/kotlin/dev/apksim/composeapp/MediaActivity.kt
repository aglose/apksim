package dev.apksim.composeapp

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * apksim://media: the media samples in assets/media (testapks/composeapp/media/make-samples.sh) decoded, and what came
 * out checked: video frame by frame, audio by its pitch. scripts/media-protocols-check.py opens it and compares the lines with
 * what each platform is expected to give.
 *
 * Every video sample is the same 30 frames (320x240 or 640x360, 30 fps): the top half seven vertical stripes, black,
 * the frame's index in binary (five stripes, most significant first, white for 1) and white; the bottom half mid grey.
 * Each picture is read back from the TextureView it was drawn to (getBitmap: what was shown), so a frame decoded wrong,
 * out of order, mirrored, upside down or not shown at all is seen. Audio samples are a 1 kHz sine for 1 s; their PCM is
 * counted and its pitch measured from zero crossings.
 *
 * Checks, in order, one line each ("play h264-high-bframes.mp4: ok 30 frames in order, ..."):
 * - `play NAME`: Media3 (ExoPlayer) from the container, at half speed for video; video: at least 25 of the 30 frames
 *   shown, in order, no unreadable picture, the last one 29; audio: its PCM through a TeeAudioProcessor.
 * - `codec NAME`: MediaCodec alone, fed an elementary stream (Annex B, IVF, ADTS) parsed here: every frame 0 to 29
 *   shown, each awaited before the next is released, in presentation order (the H.264 and HEVC samples have B-frames,
 *   so they come out in another order than they go in).
 * - `encode TYPE`: an encoder MediaCodecList lists for the type, fed the pattern (video) or a sine (audio), its output
 *   decoded back and checked the same way.
 * - `hls`: with `?hls=URL&cert=B64`, the fixture server's HLS stream (H.264 and AAC in MPEG-TS) played over the network.
 * A sample nothing can decode is `unsupported` (no decoder or encoder listed for its type, or the player left its track
 * unselected), not a failure: which ones are is the platform's, and the check script holds the expected list.
 * `?only=play|codec|encode|hls` runs one kind, `?sample=NAME` one sample. Lines also go to logcat under `apksim-media`.
 */
@OptIn(UnstableApi::class)
class MediaActivity : androidx.activity.ComponentActivity() {
  private val lines = mutableStateListOf<String>()
  private var summary by mutableStateOf("media: waiting for the view")
  private lateinit var view: TextureView
  private val picture = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
  /** Where each picture the view is updated with goes (its frame index, -1 unreadable), while a check listens. */
  @Volatile private var frames: Channel<Int>? = null
  private var started = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    view = TextureView(this)
    view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (started) return
        started = true
        lifecycleScope.launch { runAll() }
      }

      override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true

      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val sink = frames ?: return
        view.getBitmap(picture)
        sink.trySend(readIndex(picture))
      }
    }
    setContent {
      MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
          Text(summary)
          AndroidView({ view }, Modifier.padding(top = 8.dp).size(280.dp, 80.dp))
          Column(Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState())) {
            for (l in lines) Text(l, fontSize = 11.sp)
          }
        }
      }
    }
  }

  private suspend fun runAll() {
    val data = intent?.data
    val only = data?.getQueryParameter("only")
    val sample = data?.getQueryParameter("sample")
    val results = mutableListOf<CheckResult>()
    summary = summarize("media", results, false)
    suspend fun check(kind: String, name: String, body: suspend () -> CheckResult) {
      if (only != null && only != kind || sample != null && sample != name) return
      val label = "$kind $name"
      // Picture updates the last check's producer still had queued on the main thread run now, with no check listening.
      frames = null
      kotlinx.coroutines.delay(250)
      val r = try {
        withTimeoutOrNull(30_000) { body() } ?: CheckResult.fail(label, "timed out after 30 s")
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e  // the screen went away: the run stops, nothing is recorded as failed
      } catch (e: Exception) {
        CheckResult.fail(label, "${e.javaClass.simpleName}: ${e.message}")
      } finally {
        frames = null
      }.copy(name = label)
      results += r
      lines += r.line
      Log.i(TAG, r.line)
      summary = summarize("media", results, false)
    }
    for (v in PLAY_VIDEO) check("play", v) { playVideo(Uri.parse("asset:///media/$v"), null) }
    for (a in PLAY_AUDIO) check("play", a) { playAudio(Uri.parse("asset:///media/$a"), if (a.startsWith("opus")) 48_000 else 44_100) }
    for ((file, mime) in CODEC_VIDEO) check("codec", file) { codecVideo(file, mime) }
    check("codec", "aac-lc.adts") { codecAdts("aac-lc.adts") }
    for (mime in ENCODE_VIDEO) check("encode", mime) { encodeVideo(mime) }
    check("encode", MediaFormat.MIMETYPE_AUDIO_AAC) { encodeAac() }
    val hls = data?.getQueryParameter("hls")
    if (hls != null) check("hls", "stream") { playVideo(Uri.parse(hls), FixtureTls.from(data.getQueryParameter("cert"))) }
    summary = summarize("media", results, true)
    Log.i(TAG, summary)
  }

  // ---------------------------------------------------------------------------------------------------- the player

  private class PlayerRun {
    val ended = CompletableDeferred<Unit>()
    var error: PlaybackException? = null
    var videoTrack: String? = null
    var audioTrack: String? = null
    var decoder: String? = null
  }

  private fun listen(player: ExoPlayer, run: PlayerRun) {
    player.addListener(object : Player.Listener {
      override fun onTracksChanged(tracks: Tracks) {
        fun state(type: Int): String? {
          val g = tracks.groups.firstOrNull { it.type == type } ?: return null
          val mime = g.mediaTrackGroup.getFormat(0).sampleMimeType
          return if (g.isSelected) "selected $mime" else "unsupported $mime"
        }
        run.videoTrack = state(C.TRACK_TYPE_VIDEO)
        run.audioTrack = state(C.TRACK_TYPE_AUDIO)
      }

      override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) run.ended.complete(kotlin.Unit)
      }

      override fun onPlayerError(error: PlaybackException) {
        run.error = error
        run.ended.complete(kotlin.Unit)
      }
    })
    player.addAnalyticsListener(object : AnalyticsListener {
      override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        run.decoder = decoderName
      }

      override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        if (run.decoder == null) run.decoder = decoderName
      }
    })
  }

  /** A video (with or without sound) through ExoPlayer onto the view; `tls` for an HTTPS stream from the fixture. */
  private suspend fun playVideo(uri: Uri, tls: FixtureTls?): CheckResult {
    val seen = mutableListOf<Int>()
    val channel = Channel<Int>(Channel.UNLIMITED)
    val run = PlayerRun()
    val pcm = PcmSink()
    var surface: Surface? = null
    var player: ExoPlayer? = null
    val client = tls?.let { OkHttpClient.Builder().sslSocketFactory(it.socketFactory, it.trustManager).build() }
    try {
      surface = Surface(view.surfaceTexture)
      val b = ExoPlayer.Builder(this, renderers(pcm))
      if (client != null) b.setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
      player = b.build()
      frames = channel
      listen(player, run)
      player.setVideoSurface(surface)
      player.volume = 0f
      player.setPlaybackSpeed(0.5f)
      player.setMediaItem(MediaItem.fromUri(uri))
      player.prepare()
      player.play()
      withTimeoutOrNull(20_000) { run.ended.await() }
      kotlinx.coroutines.delay(300)  // the last picture's update
      while (true) seen += channel.tryReceive().getOrNull() ?: break
      // The same picture told twice (the view redrawn) is one frame, not a frame out of order.
      val distinct = seen.filterIndexed { i, v -> i == 0 || v != seen[i - 1] }
      seen.clear()
      seen += distinct
      val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
      val stats = counters?.let { ", rendered ${it.renderedOutputBufferCount}, dropped ${it.droppedBufferCount}" } ?: ""
      val decoder = run.decoder?.let { ", $it" } ?: ""
      run.error?.let {
        // A decoder for the type that lacks the stream's profile or size (Main10 on a Main-only decoder): the
        // platform's limit, as when no decoder is listed.
        if (it is androidx.media3.exoplayer.ExoPlaybackException && it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER
            && it.rendererFormatSupport != C.FORMAT_HANDLED) {
          return CheckResult.unsupported("", "the decoder lacks this stream's profile (${it.rendererFormat?.codecs}, format support ${it.rendererFormatSupport}$decoder)")
        }
        return CheckResult.fail("", "${it.errorCodeName}: ${it.message}${it.cause?.let { c -> " (${c.message})" } ?: ""}")
      }
      if (!run.ended.isCompleted) return CheckResult.fail("", "did not end in 20 s; frames $seen")
      val track = run.videoTrack ?: return CheckResult.fail("", "no video track")
      if (track.startsWith("unsupported")) return CheckResult.unsupported("", "no decoder: player left its track unselected ($track)")
      val pictures = frameVerdict(seen, minimum = 25, exact = false, extra = "$stats$decoder" + (if (pcm.frames > 0) ", ${pcm.describe()}" else ""))
      // A stream with sound (the HLS one) is checked for it too, once its pictures are right.
      val audio = run.audioTrack
      if (pictures.status != "ok" || audio == null) return pictures
      if (audio.startsWith("unsupported")) return CheckResult.unsupported("", "pictures right, but no decoder for its sound ($audio)")
      val sound = pcm.verdict(null, 44_100)
      return if (sound.status == "ok") pictures else CheckResult.fail("", "pictures right, sound wrong: ${sound.detail}")
    } finally {
      frames = null
      player?.release()
      surface?.release()
      // Closing its connections writes to them (TLS close_notify): never on the main thread, which Android refuses.
      client?.let { c -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { c.dispatcher.executorService.shutdown(); c.connectionPool.evictAll() } }
    }
  }

  /** `rate`: the stream's own sample rate, which the decoder's PCM must have (HE-AAC's is twice its core's: SBR). */
  private suspend fun playAudio(uri: Uri, rate: Int): CheckResult {
    val run = PlayerRun()
    val pcm = PcmSink()
    val player = ExoPlayer.Builder(this, renderers(pcm)).build()
    try {
      listen(player, run)
      player.volume = 0f
      player.setMediaItem(MediaItem.fromUri(uri))
      player.prepare()
      player.play()
      withTimeoutOrNull(20_000) { run.ended.await() }
      run.error?.let { return CheckResult.fail("", "${it.errorCodeName}: ${it.message}") }
      if (!run.ended.isCompleted) return CheckResult.fail("", "did not end in 20 s; ${pcm.describe()}")
      val track = run.audioTrack ?: return CheckResult.fail("", "no audio track")
      if (track.startsWith("unsupported")) return CheckResult.unsupported("", "no decoder: player left its track unselected ($track)")
      return pcm.verdict(run.decoder, rate)
    } finally {
      player.release()
    }
  }

  /** The player's renderers, with every PCM buffer the audio renderer outputs also handed to `pcm`. */
  private fun renderers(pcm: PcmSink) = object : DefaultRenderersFactory(this) {
    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
      DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(pcm))).build()
  }

  // ---------------------------------------------------------------------------------------------------- MediaCodec

  /** An elementary stream decoded by MediaCodec onto the view, each picture awaited before the next is released. */
  private suspend fun codecVideo(file: String, mime: String): CheckResult {
    val bytes = assets.open("media/$file").use { it.readBytes() }
    val units = when {
      file.endsWith(".ivf") -> ivf(bytes)
      mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> annexB(bytes) { (it.toInt() shr 1 and 0x3f) == 35 }
      else -> annexB(bytes) { (it.toInt() and 0x1f) == 9 }
    }
    if (units.size != 30) return CheckResult.fail("", "the sample parsed into ${units.size} access units, not 30")
    // Annex B has no timestamps: NAME.pts beside it has each access unit's presentation time, in decode order (with
    // B-frames not rising). IVF frames come in presentation order.
    val pts = if (file.endsWith(".ivf")) List(30) { it * 33_333L }
      else assets.open("media/$file.pts").use { it.readBytes() }.toString(Charsets.US_ASCII).lines().filter { it.isNotBlank() }.map { it.trim().toLong() }
    if (pts.size != 30) return CheckResult.fail("", "$file.pts has ${pts.size} times, not 30")
    val format = MediaFormat.createVideoFormat(mime, 320, 240)
    if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
      // VPS, SPS and PPS from the first access unit into csd-0, as a player hands them over from the container: some
      // decoders (the emulator's) decode nothing from parameter sets that arrive in the stream. H.264's stay in-band.
      val (csd, rest) = splitParameterSets(units[0])
      format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
      return decodeToView(format, units.mapIndexed { i, u -> AccessUnit(if (i == 0) rest else splitParameterSets(u).second, pts[i]) }, null)
    }
    return decodeToView(format, units.mapIndexed { i, u -> AccessUnit(u, pts[i]) }, null)
  }

  private class AccessUnit(val data: ByteArray, val pts: Long, val config: Boolean = false)

  /**
   * Decodes `units` with a decoder MediaCodecList lists for `format` (unsupported when none), each output released to
   * the view and its picture awaited; the frame indices read must be 0 to 29 in order.
   */
  private suspend fun decodeToView(format: MediaFormat, units: List<AccessUnit>, from: String?): CheckResult {
    val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
      ?: return CheckResult.unsupported("", "no decoder for ${format.getString(MediaFormat.KEY_MIME)}" + (from?.let { " ($it)" } ?: ""))
    val channel = Channel<Int>(Channel.UNLIMITED)
    var surface: Surface? = null
    var codec: MediaCodec? = null
    val seen = mutableListOf<Int>()
    var sawEos = false
    try {
      surface = Surface(view.surfaceTexture)
      codec = MediaCodec.createByCodecName(name)
      frames = channel
      withContext(Dispatchers.IO) {
        codec.configure(format, surface, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var next = 0
        var inputDone = false
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
          if (!inputDone) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
              if (next == units.size) {
                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                val u = units[next++]
                codec.getInputBuffer(i)!!.apply { clear(); put(u.data) }
                codec.queueInputBuffer(i, 0, u.data.size, u.pts, if (u.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
              }
            }
          }
          val o = codec.dequeueOutputBuffer(info, 5_000)
          if (o >= 0) {
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val picture = info.size > 0 || !eos
            codec.releaseOutputBuffer(o, picture)
            if (picture) seen += withTimeoutOrNull(2_000) { channel.receive() } ?: -2
            if (eos) {
              sawEos = true
              break
            }
          }
        }
      }
    } finally {
      frames = null
      codec?.let { runCatching { it.stop() }; it.release() }
      surface?.release()
    }
    if (!sawEos) return CheckResult.fail("", "no end of stream in 20 s (frames ${seen.take(40)}), $name")
    return frameVerdict(seen, minimum = 30, exact = true, extra = ", $name" + (from?.let { ", from $it" } ?: ""))
  }

  /** ADTS AAC decoded by MediaCodec into byte buffers, its PCM counted and its pitch measured. */
  private suspend fun codecAdts(file: String): CheckResult {
    val bytes = assets.open("media/$file").use { it.readBytes() }
    val units = mutableListOf<AccessUnit>()
    var at = 0
    var n = 0
    var config: ByteArray? = null
    while (at + 7 <= bytes.size && (bytes[at].toInt() and 0xff) == 0xff && (bytes[at + 1].toInt() and 0xf0) == 0xf0) {
      val len = (bytes[at + 3].toInt() and 3 shl 11) or ((bytes[at + 4].toInt() and 0xff) shl 3) or ((bytes[at + 5].toInt() and 0xff) shr 5)
      if (len < 7 || at + len > bytes.size) break
      val header = if (bytes[at + 1].toInt() and 1 == 1) 7 else 9  // protection_absent: no CRC
      if (config == null) {
        // The AudioSpecificConfig the header stands for: object type (profile + 1), sampling frequency index, channels.
        val objectType = (bytes[at + 2].toInt() and 0xff shr 6) + 1
        val rateIndex = bytes[at + 2].toInt() shr 2 and 0xf
        val channels = (bytes[at + 2].toInt() and 1 shl 2) or (bytes[at + 3].toInt() and 0xff shr 6)
        config = byteArrayOf((objectType shl 3 or (rateIndex shr 1)).toByte(), (rateIndex and 1 shl 7 or (channels shl 3)).toByte())
      }
      // Raw frames and the config in csd-0, as a player hands them over: not every decoder takes ADTS.
      units += AccessUnit(bytes.copyOfRange(at + header, at + len), n++ * 1024L * 1_000_000 / 44_100)
      at += len
    }
    if (config == null) return CheckResult.fail("", "the sample has no ADTS frame")
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply { setByteBuffer("csd-0", ByteBuffer.wrap(config)) }
    return decodeAudio(format, units, null, 44_100)
  }

  private suspend fun decodeAudio(format: MediaFormat, units: List<AccessUnit>, from: String?, rate: Int): CheckResult {
    val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
      ?: return CheckResult.unsupported("", "no decoder for ${format.getString(MediaFormat.KEY_MIME)}" + (from?.let { " ($it)" } ?: ""))
    val pcm = PcmSink()
    var codec: MediaCodec? = null
    var sawEos = false
    try {
      codec = MediaCodec.createByCodecName(name)
      withContext(Dispatchers.IO) {
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var next = 0
        var inputDone = false
        var started = false
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
          if (!inputDone) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
              if (next == units.size) {
                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                val u = units[next++]
                codec.getInputBuffer(i)!!.apply { clear(); put(u.data) }
                codec.queueInputBuffer(i, 0, u.data.size, u.pts, if (u.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
              }
            }
          }
          val o = codec.dequeueOutputBuffer(info, 5_000)
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o >= 0 && !started) {
            val f = codec.outputFormat
            pcm.flush(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
              if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT)
            started = true
          }
          if (o >= 0) {
            if (info.size > 0) pcm.handleBuffer(codec.getOutputBuffer(o)!!.duplicate().apply { position(info.offset); limit(info.offset + info.size) })
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              sawEos = true
              break
            }
          }
        }
      }
    } finally {
      codec?.let { runCatching { it.stop() }; it.release() }
    }
    if (!sawEos) return CheckResult.fail("", "no end of stream in 20 s (${pcm.describe()}), $name")
    return pcm.verdict(name + (from?.let { ", from $it" } ?: ""), rate)
  }

  // ---------------------------------------------------------------------------------------------------- encoders

  /** The pattern's 30 frames through an encoder for `mime`, then back through a decoder onto the view. */
  private suspend fun encodeVideo(mime: String): CheckResult {
    val format = MediaFormat.createVideoFormat(mime, 320, 240).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, 30)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
      ?: return CheckResult.unsupported("", "no encoder for $mime")
    val out = mutableListOf<AccessUnit>()
    var outFormat: MediaFormat? = null
    var codec: MediaCodec? = null
    var sawEos = false
    try {
      codec = MediaCodec.createByCodecName(name)
      withContext(Dispatchers.IO) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var next = 0
        var inputDone = false
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
          if (!inputDone) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
              if (next == 30) {
                codec.queueInputBuffer(i, 0, 0, next * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                val image = codec.getInputImage(i)!!
                fillPattern(image, next)
                codec.queueInputBuffer(i, 0, 320 * 240 * 3 / 2, next * 33_333L, 0)
                next++
              }
            }
          }
          val o = codec.dequeueOutputBuffer(info, 5_000)
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
          if (o >= 0) {
            if (info.size > 0) {
              val b = codec.getOutputBuffer(o)!!
              val data = ByteArray(info.size)
              b.position(info.offset)
              b.get(data)
              out += AccessUnit(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              sawEos = true
              break
            }
          }
        }
      }
    } finally {
      codec?.let { runCatching { it.stop() }; it.release() }
    }
    if (!sawEos) return CheckResult.fail("", "$name gave no end of stream in 20 s")
    val frames = out.count { !it.config }
    if (frames != 30) return CheckResult.fail("", "$name gave $frames frames, not 30")
    val decode = MediaFormat.createVideoFormat(mime, 320, 240)
    // The parameter sets the encoder gave in its output format (csd-0, csd-1), or as a codec-config buffer: both go to the decoder.
    outFormat?.let { f -> for (k in listOf("csd-0", "csd-1", "csd-2")) f.getByteBuffer(k)?.let { decode.setByteBuffer(k, it) } }
    val units = if (decode.containsKey("csd-0")) out.filter { !it.config } else out
    return decodeToView(decode, units, name)
  }

  /** 1 s of a 1 kHz sine through an AAC encoder, then back through a decoder. */
  private suspend fun encodeAac(): CheckResult {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
    }
    val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
      ?: return CheckResult.unsupported("", "no encoder for ${MediaFormat.MIMETYPE_AUDIO_AAC}")
    val pcm = ByteBuffer.allocate(44_100 * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until 44_100) {
      val s = (Math.sin(2 * Math.PI * 1000 * i / 44_100.0) * 12_000).toInt().toShort()
      pcm.putShort(s).putShort(s)
    }
    pcm.flip()
    val out = mutableListOf<AccessUnit>()
    var outFormat: MediaFormat? = null
    var codec: MediaCodec? = null
    var sawEos = false
    try {
      codec = MediaCodec.createByCodecName(name)
      withContext(Dispatchers.IO) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var pts = 0L
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
          if (!inputDone) {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
              val b = codec.getInputBuffer(i)!!
              b.clear()
              val n = minOf(b.remaining(), pcm.remaining(), 4096) and 3.inv()
              if (n == 0) {
                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
              } else {
                val chunk = pcm.duplicate().apply { limit(position() + n) }
                b.put(chunk)
                pcm.position(pcm.position() + n)
                codec.queueInputBuffer(i, 0, n, pts, 0)
                pts += n / 4 * 1_000_000L / 44_100
              }
            }
          }
          val o = codec.dequeueOutputBuffer(info, 5_000)
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
          if (o >= 0) {
            if (info.size > 0) {
              val b = codec.getOutputBuffer(o)!!
              val data = ByteArray(info.size)
              b.position(info.offset)
              b.get(data)
              out += AccessUnit(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              sawEos = true
              break
            }
          }
        }
      }
    } finally {
      codec?.let { runCatching { it.stop() }; it.release() }
    }
    if (!sawEos) return CheckResult.fail("", "$name gave no end of stream in 20 s")
    if (out.none { !it.config }) return CheckResult.fail("", "$name gave no frames")
    val decode = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2)
    outFormat?.getByteBuffer("csd-0")?.let { decode.setByteBuffer("csd-0", it) }
    return decodeAudio(decode, if (decode.containsKey("csd-0")) out.filter { !it.config } else out, name, 44_100)
  }

  companion object {
    const val TAG = "apksim-media"
    const val W = 140
    const val H = 40

    val PLAY_VIDEO = listOf("h264-baseline.mp4", "h264-high-bframes.mp4", "hevc-main.mp4", "hevc-main10.mp4", "vp8.webm", "vp9.webm", "av1.mp4")
    val PLAY_AUDIO = listOf("aac-lc.m4a", "aac-he.m4a", "mp3.mp3", "opus.ogg", "flac.flac")
    val CODEC_VIDEO = listOf(
      "h264-high-bframes.h264" to MediaFormat.MIMETYPE_VIDEO_AVC, "hevc-main.h265" to MediaFormat.MIMETYPE_VIDEO_HEVC,
      "vp8.ivf" to MediaFormat.MIMETYPE_VIDEO_VP8, "vp9.ivf" to MediaFormat.MIMETYPE_VIDEO_VP9, "av1.ivf" to MediaFormat.MIMETYPE_VIDEO_AV1,
    )
    val ENCODE_VIDEO = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)

    /**
     * The frame index a picture shows (a W x H copy of the view): the stripes' centres in the top half against the black
     * and white stripes at its ends, and mid grey below; -1 for a picture that is not one of the pattern's frames.
     */
    fun readIndex(b: Bitmap): Int {
      fun lum(x: Int, y: Int): Int {
        val p = b.getPixel(x, y)
        return ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
      }
      val black = lum(10, 10)
      val white = lum(130, 10)
      if (white - black < 100 || abs(lum(70, 30) - (black + white) / 2) > 40) return -1
      var n = 0
      for (s in 1..5) n = n * 2 + if (lum(s * 20 + 10, 10) > (black + white) / 2) 1 else 0
      return n
    }

    /** Fills an encoder's input image with frame `index` of the pattern, as make-samples.sh draws it. */
    fun fillPattern(image: android.media.Image, index: Int) {
      val w = image.width
      val h = image.height
      val planes = image.planes
      val y = planes[0]
      for (row in 0 until h) {
        for (col in 0 until w) {
          val s = col * 7 / w
          val v = if (row >= h / 2) 128 else if (s == 0) 16 else if (s == 6) 235 else if ((index shr (5 - s)) and 1 == 1) 235 else 16
          y.buffer.put(row * y.rowStride + col * y.pixelStride, v.toByte())
        }
      }
      for (p in 1..2) {
        val plane = planes[p]
        for (row in 0 until h / 2) for (col in 0 until w / 2) plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, 128.toByte())
      }
    }

    /**
     * The verdict on the frame indices a check read: `exact`, every frame 0 to 29 in order; else at least `minimum` of
     * them, rising, the last 29. An unreadable picture (-1) or one that never came (-2) fails either way.
     */
    fun frameVerdict(seen: List<Int>, minimum: Int, exact: Boolean, extra: String): CheckResult {
      val shown = "${seen.size} pictures"
      if (seen.any { it == -1 }) return CheckResult.fail("", "an unreadable picture among $shown: ${seen.take(40)}$extra")
      if (seen.any { it == -2 }) return CheckResult.fail("", "a released picture never reached the view: ${seen.take(40)}$extra")
      if (exact && seen != (0 until 30).toList()) return CheckResult.fail("", "frames ${seen.take(40)}, not 0 to 29 in order$extra")
      val rising = seen.zipWithNext().all { (a, b) -> b > a }
      if (!rising) return CheckResult.fail("", "frames out of order: ${seen.take(40)}$extra")
      if (seen.size < minimum || seen.lastOrNull() != 29) return CheckResult.fail("", "only ${seen.size} frames (${seen.take(40)}), the last ${seen.lastOrNull()}$extra")
      return CheckResult.ok("", "${seen.size} frames in order$extra")
    }

    private fun annexB(b: ByteArray, isDelimiter: (Byte) -> Boolean): List<ByteArray> {
      val starts = mutableListOf<Int>()
      var i = 0
      while (i + 3 < b.size) {
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
          val at = if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
          if (isDelimiter(b[i + 3])) starts += at
          i += 3
        } else {
          i++
        }
      }
      return starts.mapIndexed { k, s -> b.copyOfRange(s, if (k + 1 < starts.size) starts[k + 1] else b.size) }
    }

    /**
     * An HEVC access unit's VPS, SPS and PPS (NAL types 32 to 34), with their start codes, and the rest of it without its
     * access unit delimiter (35): players never send one, and the emulator's HEVC decoder outputs nothing at all for a
     * stream that has them.
     */
    private fun splitParameterSets(au: ByteArray): Pair<ByteArray, ByteArray> {
      val csd = java.io.ByteArrayOutputStream()
      val rest = java.io.ByteArrayOutputStream()
      val starts = mutableListOf<Int>()
      var i = 0
      while (i + 3 < au.size) {
        if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
          starts += if (i > 0 && au[i - 1].toInt() == 0) i - 1 else i
          i += 3
        } else {
          i++
        }
      }
      for ((k, s) in starts.withIndex()) {
        val end = if (k + 1 < starts.size) starts[k + 1] else au.size
        val header = s + if (au[s + 2].toInt() == 1) 3 else 4
        val type = au[header].toInt() shr 1 and 0x3f
        if (type != 35) (if (type in 32..34) csd else rest).write(au, s, end - s)
      }
      return csd.toByteArray() to rest.toByteArray()
    }

    private fun ivf(b: ByteArray): List<ByteArray> {
      val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
      var at = bb.getShort(6).toInt()
      val out = mutableListOf<ByteArray>()
      while (at + 12 <= b.size) {
        val n = bb.getInt(at)
        if (n <= 0 || at + 12 + n > b.size) break
        out += b.copyOfRange(at + 12, at + 12 + n)
        at += 12 + n
      }
      return out
    }
  }
}

/** PCM as it comes out of a decoder: counted, and its pitch measured from the first channel's zero crossings. */
@OptIn(UnstableApi::class)
private class PcmSink : TeeAudioProcessor.AudioBufferSink {
  @Volatile var rate = 0
  @Volatile var channels = 0
  @Volatile var encoding = 0
  @Volatile var frames = 0L
  private var crossings = 0L
  private var firstCrossing = -1L
  private var lastCrossing = -1L
  private var last = 0
  private var peak = 0

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    rate = sampleRateHz
    channels = channelCount
    this.encoding = encoding
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val bytes = when (encoding) {
      C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
      C.ENCODING_PCM_24BIT -> 3
      else -> 2
    }
    val frameBytes = bytes * maxOf(1, channels)
    while (b.remaining() >= frameBytes) {
      val at = b.position()
      val v = when (encoding) {
        C.ENCODING_PCM_FLOAT -> (b.getFloat(at) * 32767).toInt()
        C.ENCODING_PCM_32BIT -> b.getInt(at) shr 16
        C.ENCODING_PCM_24BIT -> ((b.get(at + 2).toInt() shl 16) or ((b.get(at + 1).toInt() and 0xff) shl 8)) shr 8
        else -> b.getShort(at).toInt()
      }
      if (abs(v) > peak) peak = abs(v)
      if (v >= 0 != last >= 0 && abs(v - last) > 64) {
        crossings++
        if (firstCrossing < 0) firstCrossing = frames
        lastCrossing = frames
      }
      last = v
      b.position(at + frameBytes)
      frames++
    }
  }

  /** The tone's pitch, between its first and last zero crossing: the codec's priming and padding silence do not count. */
  private fun hz(): Double {
    if (crossings < 3 || rate == 0) return 0.0
    return (crossings - 1) / 2.0 / ((lastCrossing - firstCrossing).toDouble() / rate)
  }

  fun describe(): String {
    val seconds = if (rate > 0) frames.toDouble() / rate else 0.0
    return "%.2f s of PCM at %d Hz x%d, %.0f Hz".format(seconds, rate, channels, hz())
  }

  /** At the stream's `expectedRate`, 0.85 to 1.2 s (encoders add priming and padding), a 1 kHz tone within 3%, not silence. */
  fun verdict(decoder: String?, expectedRate: Int): CheckResult {
    val extra = describe() + (decoder?.let { ", $it" } ?: "")
    if (frames == 0L || rate == 0) return CheckResult.fail("", "no PCM came out ($extra)")
    if (rate != expectedRate) return CheckResult.fail("", "PCM at $rate Hz, the stream's rate is $expectedRate ($extra)")
    val seconds = frames.toDouble() / rate
    val hz = hz()
    if (peak < 1000) return CheckResult.fail("", "silence ($extra, peak $peak)")
    if (seconds < 0.85 || seconds > 1.2) return CheckResult.fail("", "a 1 s sample gave $extra")
    if (abs(hz - 1000) > 30) return CheckResult.fail("", "a 1 kHz tone came out at $extra")
    return CheckResult.ok("", extra)
  }
}

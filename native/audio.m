// Sound for Android apps (dev.apksim.video.SimAudio): compressed audio decoded on AudioToolbox for MediaCodec, and
// AudioTrack's PCM played on the Mac's default output through CoreAudio. The playback head only moves as CoreAudio
// actually plays frames, as on a device, which is what keeps media players' audio clock (and so their video) in time.
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#include <jni.h>
#include <os/os_sync_wait_on_address.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------- decoding (MediaCodec)

enum { CODEC_AAC = 0, CODEC_MP3 = 1 };

typedef struct {
  AudioConverterRef converter;
  int channels;
  const uint8_t *packet;  // the access unit being decoded, fed to the converter once
  UInt32 packetBytes;
  int fed;
  AudioStreamPacketDescription description;
} AudioDecoder;

// The converter asks for input: hand it this call's one packet, then report that there is no more for now.
static OSStatus feed(AudioConverterRef c, UInt32 *packets, AudioBufferList *data, AudioStreamPacketDescription **descriptions, void *ref) {
  AudioDecoder *d = ref;
  if (d->fed || !d->packetBytes) {
    *packets = 0;
    return 1;  // not an error: "no input right now", which ends this FillComplexBuffer call
  }
  data->mNumberBuffers = 1;
  data->mBuffers[0].mData = (void *)d->packet;
  data->mBuffers[0].mDataByteSize = d->packetBytes;
  data->mBuffers[0].mNumberChannels = (UInt32)d->channels;
  if (descriptions) {
    d->description = (AudioStreamPacketDescription){0, 0, d->packetBytes};
    *descriptions = &d->description;
  }
  *packets = 1;
  d->fed = 1;
  return noErr;
}

// An MPEG-4 ES descriptor around the AudioSpecificConfig (MediaCodec's csd-0): the "magic cookie" AudioConverter
// takes for AAC. Short (one-byte) descriptor lengths, which every config here fits.
static NSData *esdsCookie(const uint8_t *asc, int ascLen) {
  NSMutableData *m = [NSMutableData data];
  uint8_t decSpecific[2] = {0x05, (uint8_t)ascLen};
  uint8_t decConfig[15] = {0x04, (uint8_t)(13 + 2 + ascLen), 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  uint8_t es[5] = {0x03, (uint8_t)(3 + 15 + 2 + ascLen + 3), 0, 0, 0};
  uint8_t sl[3] = {0x06, 0x01, 0x02};
  [m appendBytes:es length:5];
  [m appendBytes:decConfig length:15];
  [m appendBytes:decSpecific length:2];
  [m appendBytes:asc length:(NSUInteger)ascLen];
  [m appendBytes:sl length:3];
  return m;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeDecoderCreate(JNIEnv *env, jclass c, jint codec, jint rate, jint channels,
                                                                           jbyteArray config) {
  AudioStreamBasicDescription in = {0};
  in.mSampleRate = rate;
  in.mChannelsPerFrame = (UInt32)channels;
  in.mFormatID = codec == CODEC_MP3 ? kAudioFormatMPEGLayer3 : kAudioFormatMPEG4AAC;
  in.mFramesPerPacket = codec == CODEC_MP3 ? 1152 : 1024;
  AudioStreamBasicDescription out = {0};
  out.mSampleRate = rate;
  out.mChannelsPerFrame = (UInt32)channels;
  out.mFormatID = kAudioFormatLinearPCM;
  out.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
  out.mBitsPerChannel = 16;
  out.mBytesPerFrame = out.mBytesPerPacket = 2 * (UInt32)channels;
  out.mFramesPerPacket = 1;
  AudioConverterRef converter = NULL;
  if (AudioConverterNew(&in, &out, &converter) != noErr) return 0;
  if (config && codec == CODEC_AAC) {
    jsize n = (*env)->GetArrayLength(env, config);
    jbyte *asc = (*env)->GetByteArrayElements(env, config, NULL);
    NSData *cookie = esdsCookie((const uint8_t *)asc, n);
    (*env)->ReleaseByteArrayElements(env, config, asc, JNI_ABORT);
    AudioConverterSetProperty(converter, kAudioConverterDecompressionMagicCookie, (UInt32)cookie.length, cookie.bytes);
  }
  AudioDecoder *d = calloc(1, sizeof(AudioDecoder));
  d->converter = converter;
  d->channels = channels;
  return (jlong)(intptr_t)d;
}

// Decodes one access unit into 16-bit interleaved PCM. Returns the bytes written to out, or -1 on error.
JNIEXPORT jint JNICALL Java_dev_apksim_video_SimAudio_nativeDecode(JNIEnv *env, jclass c, jlong h, jobject in, jint offset, jint length,
                                                                   jobject out, jint capacity) {
  AudioDecoder *d = (AudioDecoder *)(intptr_t)h;
  d->packet = (const uint8_t *)(*env)->GetDirectBufferAddress(env, in) + offset;
  d->packetBytes = (UInt32)length;
  d->fed = 0;
  UInt32 bytesPerFrame = 2 * (UInt32)d->channels;
  UInt32 frames = (UInt32)capacity / bytesPerFrame;
  AudioBufferList list = {1, {{(UInt32)d->channels, frames * bytesPerFrame, (*env)->GetDirectBufferAddress(env, out)}}};
  OSStatus s = AudioConverterFillComplexBuffer(d->converter, feed, d, &frames, &list, NULL);
  d->packet = NULL;
  if (s != noErr && s != 1) return -1;
  return (jint)(frames * bytesPerFrame);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeDecoderReset(JNIEnv *env, jclass c, jlong h) {
  AudioConverterReset(((AudioDecoder *)(intptr_t)h)->converter);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeDecoderRelease(JNIEnv *env, jclass c, jlong h) {
  AudioDecoder *d = (AudioDecoder *)(intptr_t)h;
  AudioConverterDispose(d->converter);
  free(d);
}

// ---------------------------------------------------------------- playback (AudioTrack)

typedef struct {
  AudioUnit unit;
  uint8_t *ring;
  uint64_t capacity;
  _Atomic uint64_t written, read;  // byte counts since the last flush; the ring holds written - read
  _Atomic uint64_t played;         // frames handed to the hardware since the last flush
  _Atomic uint64_t underruns;
  _Atomic int muted;
  uint32_t frameBytes;
  _Atomic int running;  // read by a writer waiting for room while play/pause (or release) changes it
  // Wakes a writer waiting for room. The render callback runs on CoreAudio's real-time thread, so it takes no lock:
  // it bumps [wakes] and wakes the address only when a writer said it waits.
  _Atomic uint32_t wakes;
  _Atomic int waiting;
} AudioOut;

static void wake_writer(AudioOut *o) {
  atomic_fetch_add(&o->wakes, 1);
  if (atomic_load(&o->waiting)) os_sync_wake_by_address_all(&o->wakes, 4, OS_SYNC_WAKE_BY_ADDRESS_NONE);
}

static OSStatus render(void *ref, AudioUnitRenderActionFlags *flags, const AudioTimeStamp *time, UInt32 bus, UInt32 frames,
                       AudioBufferList *data) {
  AudioOut *o = ref;
  uint8_t *dst = data->mBuffers[0].mData;
  uint64_t want = (uint64_t)frames * o->frameBytes;
  uint64_t r = atomic_load(&o->read), available = atomic_load(&o->written) - r;
  uint64_t n = available < want ? available - available % o->frameBytes : want;
  for (uint64_t done = 0; done < n;) {
    uint64_t at = (r + done) % o->capacity, chunk = n - done < o->capacity - at ? n - done : o->capacity - at;
    memcpy(dst + done, o->ring + at, (size_t)chunk);
    done += chunk;
  }
  if (atomic_load(&o->muted)) memset(dst, 0, (size_t)n);
  if (n < want) {
    memset(dst + n, 0, (size_t)(want - n));
    if (n == 0) *flags |= kAudioUnitRenderAction_OutputIsSilence;
    if (available < want && atomic_load(&o->written) > 0) atomic_fetch_add(&o->underruns, 1);
  }
  atomic_store(&o->read, r + n);
  atomic_fetch_add(&o->played, n / o->frameBytes);
  wake_writer(o);
  return noErr;
}

// encoding: AudioFormat's ENCODING_PCM_16BIT (2), ENCODING_PCM_8BIT (3) or ENCODING_PCM_FLOAT (4).
JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeOutCreate(JNIEnv *env, jclass c, jint rate, jint channels, jint encoding,
                                                                       jint bufferBytes) {
  AudioComponentDescription desc = {kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput, kAudioUnitManufacturer_Apple, 0, 0};
  AudioComponent comp = AudioComponentFindNext(NULL, &desc);
  if (!comp) return 0;
  AudioOut *o = calloc(1, sizeof(AudioOut));
  if (AudioComponentInstanceNew(comp, &o->unit) != noErr) {
    free(o);
    return 0;
  }
  AudioStreamBasicDescription f = {0};
  f.mSampleRate = rate;
  f.mFormatID = kAudioFormatLinearPCM;
  f.mChannelsPerFrame = (UInt32)channels;
  f.mFramesPerPacket = 1;
  f.mBitsPerChannel = encoding == 4 ? 32 : encoding == 3 ? 8 : 16;
  f.mFormatFlags = (encoding == 4 ? kAudioFormatFlagIsFloat : encoding == 3 ? 0 : kAudioFormatFlagIsSignedInteger) | kAudioFormatFlagIsPacked;
  f.mBytesPerFrame = f.mBytesPerPacket = f.mBitsPerChannel / 8 * (UInt32)channels;
  o->frameBytes = f.mBytesPerFrame;
  AURenderCallbackStruct cb = {render, o};
  if (AudioUnitSetProperty(o->unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &f, sizeof f) != noErr ||
      AudioUnitSetProperty(o->unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &cb, sizeof cb) != noErr ||
      AudioUnitInitialize(o->unit) != noErr) {
    AudioComponentInstanceDispose(o->unit);
    free(o);
    return 0;
  }
  // The app's buffer, as AudioTrack would allocate it, but at least 100 ms so a busy main thread does not starve it.
  uint64_t minimum = (uint64_t)rate / 10 * o->frameBytes;
  o->capacity = bufferBytes > 0 && (uint64_t)bufferBytes > minimum ? (uint64_t)bufferBytes : minimum;
  o->capacity -= o->capacity % o->frameBytes;
  o->ring = malloc((size_t)o->capacity);
  return (jlong)(intptr_t)o;
}

// Copies PCM into the ring: all of it when blocking (waiting for CoreAudio to make room), otherwise what fits.
// Returns the bytes taken.
JNIEXPORT jint JNICALL Java_dev_apksim_video_SimAudio_nativeOutWrite(JNIEnv *env, jclass c, jlong h, jobject buf, jint offset, jint length,
                                                                     jboolean blocking) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  const uint8_t *src = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buf) + offset;
  uint64_t done = 0;
  while (done < (uint64_t)length) {
    uint64_t w = atomic_load(&o->written), room = o->capacity - (w - atomic_load(&o->read));
    if (room == 0) {
      if (!blocking || !o->running) break;
      // Say we wait before looking at [wakes] and the ring again: either the callback sees [waiting] and wakes us,
      // or we see the room it made. The 20 ms timeout is only a backstop.
      atomic_store(&o->waiting, 1);
      uint32_t gen = atomic_load(&o->wakes);
      if (o->capacity - (atomic_load(&o->written) - atomic_load(&o->read)) == 0 && o->running) {
        os_sync_wait_on_address_with_timeout(&o->wakes, gen, 4, OS_SYNC_WAIT_ON_ADDRESS_NONE, OS_CLOCK_MACH_ABSOLUTE_TIME,
                                             20000000);
      }
      atomic_store(&o->waiting, 0);
      continue;
    }
    uint64_t n = (uint64_t)length - done < room ? (uint64_t)length - done : room;
    uint64_t at = w % o->capacity, chunk = n < o->capacity - at ? n : o->capacity - at;
    memcpy(o->ring + at, src + done, (size_t)chunk);
    if (chunk < n) memcpy(o->ring, src + done + chunk, (size_t)(n - chunk));
    atomic_store(&o->written, w + n);
    done += n;
  }
  return (jint)done;
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeOutPlay(JNIEnv *env, jclass c, jlong h, jboolean play) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  if (play && !o->running) AudioOutputUnitStart(o->unit);
  if (!play && o->running) AudioOutputUnitStop(o->unit);
  o->running = play;
  wake_writer(o);
}

// Drops what has not been played and starts the head again at 0, as AudioTrack.flush() does.
JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeOutFlush(JNIEnv *env, jclass c, jlong h) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  int was = o->running;
  if (was) AudioOutputUnitStop(o->unit);
  atomic_store(&o->read, 0);
  atomic_store(&o->written, 0);
  atomic_store(&o->played, 0);
  if (was) AudioOutputUnitStart(o->unit);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeOutPlayed(JNIEnv *env, jclass c, jlong h) {
  return (jlong)atomic_load(&((AudioOut *)(intptr_t)h)->played);
}

// Frames written but not yet played.
JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeOutPending(JNIEnv *env, jclass c, jlong h) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  return (jlong)((atomic_load(&o->written) - atomic_load(&o->read)) / o->frameBytes);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeOutUnderruns(JNIEnv *env, jclass c, jlong h) {
  return (jlong)atomic_load(&((AudioOut *)(intptr_t)h)->underruns);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeOutVolume(JNIEnv *env, jclass c, jlong h, jfloat volume, jboolean muted) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  atomic_store(&o->muted, muted ? 1 : 0);
  AudioUnitSetParameter(o->unit, kHALOutputParam_Volume, kAudioUnitScope_Global, 0, volume, 0);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeOutRelease(JNIEnv *env, jclass c, jlong h) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  AudioOutputUnitStop(o->unit);
  AudioUnitUninitialize(o->unit);
  AudioComponentInstanceDispose(o->unit);
  free(o->ring);
  free(o);
}

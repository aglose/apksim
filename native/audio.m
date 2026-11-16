// Sound for Android apps (dev.apksim.video.SimAudio): compressed audio decoded on AudioToolbox for MediaCodec, and
// AudioTrack's PCM played on the Mac's default output through CoreAudio. The playback head only moves as CoreAudio
// actually plays frames, as on a device, which is what keeps media players' audio clock (and so their video) in time.
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#include <jni.h>
#include <os/os_sync_wait_on_address.h>
#include <pthread.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------- decoding (MediaCodec)

enum { CODEC_AAC = 0, CODEC_MP3 = 1 };

typedef struct {
  AudioConverterRef converter;
  int channels;  // of the PCM it gives (HE-AAC v2: two, from parametric stereo on a mono core)
  int rate;      // of the PCM it gives (HE-AAC: twice its core's, the SBR layer's)
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
  NSData *cookie = nil;
  if (config && codec == CODEC_AAC) {
    jsize n = (*env)->GetArrayLength(env, config);
    jbyte *asc = (*env)->GetByteArrayElements(env, config, NULL);
    cookie = esdsCookie((const uint8_t *)asc, n);
    (*env)->ReleaseByteArrayElements(env, config, asc, JNI_ABORT);
    // The layers the config describes, richest first (HE-AAC v2, HE-AAC, AAC-LC), and the first of them AudioToolbox
    // plays: the input format then has the stream's real rate and channels. Taken as given (the app's rate and plain
    // AAC), an HE-AAC stream was decoded as its AAC-LC core alone: half the rate, no SBR band, mono for v2's PS.
    AudioFormatInfo info = {0};
    info.mASBD.mFormatID = kAudioFormatMPEG4AAC;
    info.mMagicCookie = cookie.bytes;
    info.mMagicCookieSize = (UInt32)cookie.length;
    UInt32 size = 0;
    if (AudioFormatGetPropertyInfo(kAudioFormatProperty_FormatList, sizeof(info), &info, &size) == noErr && size >= sizeof(AudioFormatListItem)) {
      AudioFormatListItem *items = malloc(size);
      UInt32 index = 0, indexSize = sizeof(index);
      // An item without a rate or channels (a config with a program config element) keeps the app's format.
      if (items && AudioFormatGetProperty(kAudioFormatProperty_FormatList, sizeof(info), &info, &size, items) == noErr
          && AudioFormatGetProperty(kAudioFormatProperty_FirstPlayableFormatFromList, size, items, &indexSize, &index) == noErr
          && index < size / sizeof(AudioFormatListItem) && items[index].mASBD.mSampleRate > 0 && items[index].mASBD.mChannelsPerFrame > 0) {
        in = items[index].mASBD;
      }
      free(items);
    }
  }
  out.mSampleRate = in.mSampleRate;
  out.mChannelsPerFrame = in.mChannelsPerFrame;
  out.mBytesPerFrame = out.mBytesPerPacket = 2 * in.mChannelsPerFrame;
  AudioConverterRef converter = NULL;
  if (AudioConverterNew(&in, &out, &converter) != noErr) return 0;
  if (cookie) AudioConverterSetProperty(converter, kAudioConverterDecompressionMagicCookie, (UInt32)cookie.length, cookie.bytes);
  AudioDecoder *d = calloc(1, sizeof(AudioDecoder));
  d->converter = converter;
  d->channels = (int)out.mChannelsPerFrame;
  d->rate = (int)out.mSampleRate;
  return (jlong)(intptr_t)d;
}

static void sniffProperty(void *ref, AudioFileStreamID stream, AudioFileStreamPropertyID id, AudioFileStreamPropertyFlags *flags) {}

static void sniffPackets(void *ref, UInt32 bytes, UInt32 packets, const void *data, AudioStreamPacketDescription *descriptions) {}

// Whether the AAC frame (raw, an AudioSpecificConfig of AAC-LC describing it) carries SBR in it, and parametric stereo:
// implicit signalling, as every ADTS and MPEG-TS stream signals HE-AAC (their headers hold only the core). AudioToolbox's
// own ADTS parser (the one afinfo uses) looks into the frame, given behind an ADTS header the config stands for, and
// lists the layers it finds. 0: AAC-LC alone (or a frame it could not read), 1: SBR, 2: SBR and parametric stereo. Some
// frames say nothing (the first of a low-rate stream can be too short): the caller tries several. A mono core with SBR is
// always listed with a PS layer too, so mono HE-AAC v1 cannot be told from v2 here and is taken as v2 (stereo out).
JNIEXPORT jint JNICALL Java_dev_apksim_video_SimAudio_nativeSniffSbr(JNIEnv *env, jclass c, jbyteArray config, jobject frame, jint offset,
                                                                     jint length) {
  if ((*env)->GetArrayLength(env, config) < 2 || length <= 0 || length > 8184) return 0;
  jbyte *asc = (*env)->GetByteArrayElements(env, config, NULL);
  int objectType = ((uint8_t)asc[0]) >> 3;
  int rateIndex = ((asc[0] & 7) << 1) | (((uint8_t)asc[1]) >> 7);
  int channels = (((uint8_t)asc[1]) >> 3) & 0xf;
  (*env)->ReleaseByteArrayElements(env, config, asc, JNI_ABORT);
  if (objectType < 1 || objectType > 4) return 0;
  const uint8_t *raw = (const uint8_t *)(*env)->GetDirectBufferAddress(env, frame) + offset;
  // The frame twice behind its header: the parser wants a second sync word to trust the first.
  int frameLength = 7 + length;
  uint8_t *adts = malloc((size_t)frameLength * 2);
  if (!adts) return 0;
  for (int k = 0; k < 2; k++) {
    uint8_t *h = adts + k * frameLength;
    h[0] = 0xff;
    h[1] = 0xf1;  // MPEG-4, layer 0, no CRC
    h[2] = (uint8_t)(((objectType - 1) << 6) | (rateIndex << 2) | ((channels >> 2) & 1));
    h[3] = (uint8_t)(((channels & 3) << 6) | ((frameLength >> 11) & 3));
    h[4] = (uint8_t)((frameLength >> 3) & 0xff);
    h[5] = (uint8_t)(((frameLength & 7) << 5) | 0x1f);
    h[6] = 0xfc;
    memcpy(h + 7, raw, (size_t)length);
  }
  AudioFileStreamID stream = NULL;
  int found = 0;
  if (AudioFileStreamOpen(NULL, sniffProperty, sniffPackets, kAudioFileAAC_ADTSType, &stream) == noErr) {
    UInt32 size = 0;
    Boolean writable = false;
    if (AudioFileStreamParseBytes(stream, (UInt32)frameLength * 2, adts, 0) == noErr
        && AudioFileStreamGetPropertyInfo(stream, kAudioFileStreamProperty_FormatList, &size, &writable) == noErr && size > 0) {
      AudioFormatListItem *items = malloc(size);
      if (items && AudioFileStreamGetProperty(stream, kAudioFileStreamProperty_FormatList, &size, items) == noErr) {
        for (UInt32 i = 0; i < size / sizeof(AudioFormatListItem); i++) {
          if (items[i].mASBD.mFormatID == kAudioFormatMPEG4AAC_HE_V2) found = 2;
          else if (items[i].mASBD.mFormatID == kAudioFormatMPEG4AAC_HE && found < 1) found = 1;
        }
      }
      free(items);
    }
    AudioFileStreamClose(stream);
  }
  free(adts);
  return found;
}

// The PCM a decoder gives: its sample rate and channel count, which an HE-AAC stream's config sets, not the app's format.
JNIEXPORT jint JNICALL Java_dev_apksim_video_SimAudio_nativeDecoderRate(JNIEnv *env, jclass c, jlong h) {
  return ((AudioDecoder *)(intptr_t)h)->rate;
}

JNIEXPORT jint JNICALL Java_dev_apksim_video_SimAudio_nativeDecoderChannels(JNIEnv *env, jclass c, jlong h) {
  return ((AudioDecoder *)(intptr_t)h)->channels;
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
  _Atomic int writers;  // threads inside nativeOutWrite: release must find none
} AudioOut;

// Streams released while a writer was still inside nativeOutWrite (a use after free for that writer): the audio
// selftest checks this stays 0.
static _Atomic long releasedUnderWrite;

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
  atomic_fetch_add(&o->writers, 1);
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
  atomic_fetch_sub(&o->writers, 1);
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

JNIEXPORT jlong JNICALL Java_dev_apksim_video_SimAudio_nativeReleasedUnderWrite(JNIEnv *env, jclass c) {
  return (jlong)atomic_load(&releasedUnderWrite);
}

JNIEXPORT void JNICALL Java_dev_apksim_video_SimAudio_nativeOutRelease(JNIEnv *env, jclass c, jlong h) {
  AudioOut *o = (AudioOut *)(intptr_t)h;
  if (atomic_load(&o->writers) > 0) {
    atomic_fetch_add(&releasedUnderWrite, 1);
    fprintf(stderr, "[apksim] AudioTrack released with a write still in progress\n");
  }
  AudioOutputUnitStop(o->unit);
  AudioUnitUninitialize(o->unit);
  AudioComponentInstanceDispose(o->unit);
  free(o->ring);
  free(o);
}

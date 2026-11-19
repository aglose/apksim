// Checks avaudio_linux.c's decoding without a JVM (built and run in the image's build stage, not shipped):
//   avaudio_check FILE aac|mp3 RATE CHANNELS   decodes a 440 Hz tone the way MediaCodec feeds it (AAC: raw frames out of
//                                              ADTS with the AudioSpecificConfig; MP3: libavcodec's parser's frames) and checks
//                                              the PCM's length, rate, channels and pitch
//   avaudio_check --absent aac|mp3             exit 0 when the build has no such decoder, 1 when it has one
#define AUDEC_NO_JNI
#include "avaudio_linux.c"

#include <stdio.h>

static int fail(const char *what) {
  fprintf(stderr, "avaudio_check: %s\n", what);
  return 1;
}

static uint8_t *slurp(const char *path, long *n) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  *n = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t *b = malloc(*n + 1);
  if (b && fread(b, 1, *n, f) != (size_t)*n) { free(b); b = NULL; }
  fclose(f);
  return b;
}

// PCM is checked as a whole: its frames' count, its zero crossings (440 Hz: 880 a second per channel) and its level.
static int check_pcm(const int16_t *pcm, long frames, int channels, int rate, double seconds) {
  if (frames < (long)(seconds * rate) - 4096 || frames > (long)(seconds * rate) + 4096) {
    fprintf(stderr, "avaudio_check: %ld frames, expected about %ld\n", frames, (long)(seconds * rate));
    return 1;
  }
  long crossings = 0, peak = 0;
  for (long i = 1; i < frames; i++) {
    int a = pcm[(i - 1) * channels], b = pcm[i * channels];
    if ((a < 0) != (b < 0)) crossings++;
    if (abs(b) > peak) peak = abs(b);
  }
  double hz = crossings / 2.0 / (frames / (double)rate);
  fprintf(stderr, "avaudio_check: %ld frames, %d ch, %d Hz, pitch %.1f Hz, peak %ld\n", frames, channels, rate, hz, peak);
  if (hz < 425 || hz > 455) return fail("not 440 Hz");
  if (peak < 1000) return fail("silent or far too quiet");
  return 0;
}

int main(int argc, char **argv) {
  if (argc == 3 && strcmp(argv[1], "--absent") == 0)
    return avcodec_find_decoder(strcmp(argv[2], "mp3") == 0 ? AV_CODEC_ID_MP3 : AV_CODEC_ID_AAC) != NULL;
  if (argc != 5) return fail("usage");
  int mp3 = strcmp(argv[2], "mp3") == 0, rate = atoi(argv[3]), channels = atoi(argv[4]);
  long n;
  uint8_t *file = slurp(argv[1], &n);
  if (!file) return fail("cannot read the clip");
  const double seconds = 1.0;
  int cap = 1 << 16;
  uint8_t *pcm = malloc((size_t)cap);
  long total = 0, cap_all = (long)(seconds * rate + 16384) * channels * 2;
  int16_t *all = malloc((size_t)cap_all);
  AuDec *d = NULL;
  int frames = 0;
  if (!mp3) {
    // ADTS: sync, profile, rate index and channel configuration in the header give the AudioSpecificConfig MediaCodec has.
    if (n < 7 || file[0] != 0xff || (file[1] & 0xf6) != 0xf0) return fail("not ADTS");
    int object = (file[2] >> 6) + 1, ri = file[2] >> 2 & 15, ch = (file[2] & 1) << 2 | file[3] >> 6;
    uint8_t asc[2] = {(uint8_t)(object << 3 | ri >> 1), (uint8_t)(ri << 7 | ch << 3)};
    d = aud_create(0, rate, channels, asc, 2);
    if (!d) return fail("no AAC decoder");
    for (long p = 0; p + 7 <= n;) {
      int len = (file[p + 3] & 3) << 11 | file[p + 4] << 3 | file[p + 5] >> 5, hdr = (file[p + 1] & 1) ? 7 : 9;
      if (len < hdr || p + len > n) break;
      int b = aud_decode(d, file + p + hdr, len - hdr, pcm, cap);
      if (b < 0) return fail("a frame was refused");
      if ((total + b / 2) * 2 > cap_all) return fail("too much PCM");
      memcpy((uint8_t *)all + total * 2, pcm, (size_t)b);
      total += b / 2;
      frames++;
      p += len;
    }
  } else {
    d = aud_create(1, rate, channels, NULL, 0);
    if (!d) return fail("no MP3 decoder");
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_MP3);
    AVCodecParserContext *parser = av_parser_init(codec->id);
    AVCodecContext *pc = avcodec_alloc_context3(codec);
    if (!parser || !pc) return fail("no MP3 parser");
    for (long p = 0; p < n;) {
      uint8_t *out = NULL;
      int osize = 0;
      int used = av_parser_parse2(parser, pc, &out, &osize, file + p, (int)(n - p), AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
      p += used > 0 ? used : n;
      if (osize <= 0) continue;
      int b = aud_decode(d, out, osize, pcm, cap);
      if (b < 0) return fail("a frame was refused");
      if ((total + b / 2) * 2 > cap_all) return fail("too much PCM");
      memcpy((uint8_t *)all + total * 2, pcm, (size_t)b);
      total += b / 2;
      frames++;
    }
  }
  if (frames < 10) return fail("too few access units");
  if (d->rate != rate || d->channels != channels) {
    fprintf(stderr, "avaudio_check: decoder says %d Hz, %d ch\n", d->rate, d->channels);
    return 1;
  }
  int r = check_pcm(all, total / channels, channels, rate, seconds);
  // a reset (a seek) must leave a decoder that still decodes
  aud_reset(d);
  if (!mp3 && aud_decode(d, file + 7, ((file[3] & 3) << 11 | file[4] << 3 | file[5] >> 5) - 7, pcm, cap) < 0) r |= fail("no decoding after a reset");
  aud_release(d);
  return r;
}

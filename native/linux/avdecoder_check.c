// Checks avdecoder_linux.c's decoding without a JVM (built and run in the image's build stage, not shipped):
//   avdecoder_check FILE h264|h265 WIDTH HEIGHT
// FILE is an Annex B clip of FFmpeg's testsrc2 with access unit delimiters. It is split at them as
// dev.apksim.video.SelfTest.codec() does and decoded twice through the functions the JNI methods call:
//  1. pts in decode order. Every unit must give a picture by the end of the drain, each pts exactly once; for H.264 the
//     pictures must come out in another order than they went in (the clip has B-frames: the reordering is what this
//     checks).
//  2. After avd_reset, pts in presentation order (as the first pass found it): pictures must come out with strictly
//     increasing pts, the size must be right, and after avd_picture_to_rgba (RGBA and BGRA, at full size and halved,
//     from another thread) the pixel at (w/16, h/2), testsrc2's leftmost bar, must be red.
// Exits 0 when all hold, 1 on a failed check, 2 on bad arguments or input.
#define AVDEC_NO_JNI 1
#include "avdecoder_linux.c"

#include <stdio.h>

#define MAX_UNITS 4096
#define STEP 33333

static int failures;
#define CHECK(cond, ...)                                  \
  do {                                                    \
    if (!(cond)) {                                        \
      fprintf(stderr, "FAIL: " __VA_ARGS__);              \
      fputc('\n', stderr);                                \
      failures++;                                         \
    }                                                     \
  } while (0)

typedef struct {
  const uint8_t *data;
  int w, h;
  int bgra;
  uint8_t *out;
  AVFrame *pic;
  int ok;
} Convert;

static void *convert_thread(void *arg) {
  Convert *c = arg;
  c->ok = avd_picture_to_rgba(c->pic, c->w, c->h, c->out, c->bgra);
  return NULL;
}

// Converts on a thread of its own and reports the pixel at (x, y).
static int pixel(AVFrame *pic, int w, int h, int bgra, int x, int y, int rgb[3]) {
  Convert c = {.w = w, .h = h, .bgra = bgra, .pic = pic, .out = malloc((size_t)w * h * 4)};
  pthread_t t;
  pthread_create(&t, NULL, convert_thread, &c);
  pthread_join(t, NULL);
  int ok = c.ok;
  if (ok) {
    const uint8_t *p = c.out + ((size_t)y * w + x) * 4;
    rgb[0] = bgra ? p[2] : p[0];
    rgb[1] = p[1];
    rgb[2] = bgra ? p[0] : p[2];
    CHECK(p[3] == 255, "alpha %d at (%d,%d) in %dx%d", p[3], x, y, w, h);
  }
  free(c.out);
  return ok;
}

static int isRed(const int rgb[3]) { return rgb[0] > 180 && rgb[1] < 70 && rgb[2] < 70; }

// Decodes the units with pts[i] each; the output pts in order of arrival go to outPts. Returns how many pictures came.
static int decode(AvDec *d, const uint8_t *all, int (*units)[2], int n, const int64_t *pts, int64_t *outPts, int w,
                  int h, int checkPixels) {
  int got = 0;
  AVFrame *first = NULL;
  for (int i = 0; i <= n; i++) {
    if (i < n) {
      int r = avd_send(d, all + units[i][0], units[i][1] - units[i][0], pts[i]);
      CHECK(r >= 0, "send of unit %d: %d", i, r);
    } else {
      CHECK(avd_send(d, NULL, 0, 0) >= 0, "drain");
    }
    int64_t t;
    AVFrame *f;
    while ((f = avd_receive(d, &t))) {
      if (got < MAX_UNITS) outPts[got] = t;
      got++;
      CHECK(f->width == w && f->height == h, "picture %d is %dx%d, expected %dx%d", got, f->width, f->height, w, h);
      if (checkPixels && !first) {
        first = f;
        continue;
      }
      avd_release_picture(f);
    }
  }
  if (first) {
    for (int bgra = 0; bgra < 2; bgra++) {
      int rgb[3] = {0};
      CHECK(pixel(first, w, h, bgra, w / 16, h / 2, rgb), "conversion to %s failed", bgra ? "BGRA" : "RGBA");
      CHECK(isRed(rgb), "%s pixel (%d,%d) is %d,%d,%d, not red", bgra ? "BGRA" : "RGBA", w / 16, h / 2, rgb[0], rgb[1],
            rgb[2]);
      int rgb2[3] = {0};
      CHECK(pixel(first, w / 2, h / 2, bgra, w / 32, h / 4, rgb2), "scaled conversion failed");
      CHECK(isRed(rgb2), "scaled %s pixel is %d,%d,%d, not red", bgra ? "BGRA" : "RGBA", rgb2[0], rgb2[1], rgb2[2]);
    }
    avd_release_picture(first);
  }
  return got;
}

int main(int argc, char **argv) {
  if (argc != 5) {
    fprintf(stderr, "usage: %s FILE h264|h265 WIDTH HEIGHT\n", argv[0]);
    return 2;
  }
  int hevc = strcmp(argv[2], "h265") == 0;
  int w = atoi(argv[3]), h = atoi(argv[4]);
  FILE *f = fopen(argv[1], "rb");
  if (!f) {
    perror(argv[1]);
    return 2;
  }
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t *all = malloc(len + 8);
  if (fread(all, 1, len, f) != (size_t)len) return 2;
  fclose(f);

  // Access units split at AUD NAL units (H.264 type 9, HEVC type 35), like SelfTest.codec().
  static int units[MAX_UNITS][2];
  int n = 0, start = -1;
  for (long i = 0; i + 4 < len; i++) {
    int aud = hevc ? (all[i + 4] >> 1 & 0x3f) == 35 : (all[i + 4] & 0x1f) == 9;
    if (all[i] == 0 && all[i + 1] == 0 && all[i + 2] == 0 && all[i + 3] == 1 && aud) {
      if (start >= 0 && n < MAX_UNITS) { units[n][0] = start; units[n][1] = (int)i; n++; }
      start = (int)i;
    }
  }
  if (start >= 0 && n < MAX_UNITS) { units[n][0] = start; units[n][1] = (int)len; n++; }
  if (n == 0) {
    fprintf(stderr, "no access units in %s\n", argv[1]);
    return 2;
  }
  printf("%s: %d access units, %s\n", argv[1], n, hevc ? "HEVC" : "H.264");

  AvDec *d = avd_create(hevc);
  if (!d) {
    fprintf(stderr, "cannot create the decoder\n");
    return 2;
  }
  static int64_t pts[MAX_UNITS], out1[MAX_UNITS], out2[MAX_UNITS];
  for (int i = 0; i < n; i++) pts[i] = (int64_t)i * 66666;  // decode order, so out of order on arrival with B-frames

  int got = decode(d, all, units, n, pts, out1, w, h, 0);
  printf("pass 1: %d pictures\n", got);
  CHECK(got == n, "%d pictures for %d units", got, n);
  int moved = 0;
  if (got == n) {
    for (int i = 0; i < n; i++) {
      int found = 0;
      for (int j = 0; j < n; j++) found += out1[j] == pts[i];
      CHECK(found == 1, "pts %lld came %d times", (long long)pts[i], found);
      moved += out1[i] != pts[i];
    }
    printf("pass 1: %d of %d pictures out of decode order\n", moved, n);
    if (!hevc) CHECK(moved > 0, "no reordering: the clip has no B-frames?");
  }

  // Presentation order is the order pictures came out; give each unit the pts of its place in it.
  avd_reset(d);
  if (got == n) {
    static int64_t place[MAX_UNITS];
    for (int i = 0; i < n; i++)
      for (int k = 0; k < n; k++)
        if (out1[k] == pts[i]) place[i] = (int64_t)k * STEP;
    memcpy(pts, place, n * sizeof *pts);
  }
  int got2 = decode(d, all, units, n, pts, out2, w, h, 1);
  printf("pass 2 (after reset): %d pictures\n", got2);
  CHECK(got2 == n, "%d pictures for %d units after reset", got2, n);
  for (int i = 1; i < got2 && i < MAX_UNITS; i++)
    CHECK(out2[i] > out2[i - 1], "pts %lld after %lld", (long long)out2[i], (long long)out2[i - 1]);

  // A seek in the middle: units from the middle, after a reset, must still decode (the first picture needs a
  // keyframe, so this only checks that reset leaves the decoder usable and holding nothing from before).
  for (int i = 0; i < n / 2; i++) avd_send(d, all + units[i][0], units[i][1] - units[i][0], pts[i]);
  avd_reset(d);
  int64_t t;
  CHECK(avd_receive(d, &t) == NULL, "a picture came out of a reset decoder");
  avd_release(d);
  if (failures) {
    fprintf(stderr, "%d checks failed\n", failures);
    return 1;
  }
  printf("OK\n");
  return 0;
}

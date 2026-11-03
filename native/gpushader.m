// Runs translated AGSL shaders on the GPU. HWUI here is CPU-only, and interpreting a shader per pixel
// on one core is what makes shader-heavy screens slow; this produces the pixels instead.
#import <Metal/Metal.h>
#include <jni.h>

static id<MTLDevice> device;
static id<MTLCommandQueue> queue;

#define TEX_CACHE 8
static id<MTLTexture> textures[TEX_CACHE];

static NSString *jstr(JNIEnv *env, jstring s) {
  const char *c = (*env)->GetStringUTFChars(env, s, NULL);
  NSString *r = [NSString stringWithUTF8String:c];
  (*env)->ReleaseStringUTFChars(env, s, c);
  return r;
}

JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_compile(JNIEnv *env, jclass c, jstring source, jobjectArray errorOut) {
  @autoreleasepool {
    if (!device) {
      device = MTLCreateSystemDefaultDevice();
      queue = [device newCommandQueue];
    }
    NSError *error = nil;
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.mathMode = MTLMathModeFast;
    id<MTLLibrary> lib = [device newLibraryWithSource:jstr(env, source) options:options error:&error];
    if (!lib) {
      (*env)->SetObjectArrayElement(env, errorOut, 0, (*env)->NewStringUTF(env, error.localizedDescription.UTF8String));
      return 0;
    }
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.vertexFunction = [lib newFunctionWithName:@"apksim_vertex"];
    d.fragmentFunction = [lib newFunctionWithName:@"apksim_fragment"];
    d.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    id<MTLRenderPipelineState> p = [device newRenderPipelineStateWithDescriptor:d error:&error];
    if (!p) {
      (*env)->SetObjectArrayElement(env, errorOut, 0, (*env)->NewStringUTF(env, error.localizedDescription.UTF8String));
      return 0;
    }
    return (jlong)CFBridgingRetain(p);
  }
}

static id<MTLTexture> target(int w, int h) {
  for (int i = 0; i < TEX_CACHE; i++) {
    if (textures[i] && textures[i].width == (NSUInteger)w && textures[i].height == (NSUInteger)h) return textures[i];
  }
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageRenderTarget;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> t = [device newTextureWithDescriptor:d];
  static int next;
  textures[next] = t;
  next = (next + 1) % TEX_CACHE;
  return t;
}

// transform: the 2x3 matrix from target pixels to the shader's coordinates. Child textures are unused
// slots until shaders with inputs are supported.
JNIEXPORT jboolean JNICALL Java_dev_apksim_gpu_GpuNative_render(JNIEnv *env, jclass c, jlong pipeline, jobject uniforms, jint uniformBytes,
                                                                jfloatArray transform, jint w, jint h, jobject out) {
  @autoreleasepool {
    id<MTLRenderPipelineState> p = (__bridge id<MTLRenderPipelineState>)(void *)pipeline;
    id<MTLTexture> t = target(w, h);
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture = t;
    pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLCommandBuffer> cb = [queue commandBuffer];
    id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:pass];
    [e setRenderPipelineState:p];
    jfloat m[6];
    (*env)->GetFloatArrayRegion(env, transform, 0, 6, m);
    // float2x3-style: two float3 rows, 16 bytes each in Metal.
    float rows[8] = {m[0], m[1], m[2], 0, m[3], m[4], m[5], 0};
    [e setFragmentBytes:rows length:sizeof rows atIndex:1];
    char empty[16] = {0};
    if (uniformBytes > 0) {
      [e setFragmentBytes:(*env)->GetDirectBufferAddress(env, uniforms) length:uniformBytes atIndex:0];
    } else {
      [e setFragmentBytes:empty length:sizeof empty atIndex:0];
    }
    uint32_t noClamp = 0;
    [e setFragmentBytes:&noClamp length:sizeof noClamp atIndex:2];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [e endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    if (cb.status != MTLCommandBufferStatusCompleted) return JNI_FALSE;
    [t getBytes:(*env)->GetDirectBufferAddress(env, out) bytesPerRow:w * 4 fromRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0];
    return JNI_TRUE;
  }
}

// A render target in linear memory: a texture over a shared buffer, so reading it back is a row copy rather than
// the CPU untiling an ordinary (tiled) texture, which cost ~3 ms for a full phone screen. Kept in a small pool
// rather than made per frame.
@interface ApksimTarget : NSObject
@property(nonatomic, strong) id<MTLBuffer> buffer;
@property(nonatomic, strong) id<MTLTexture> texture;
@property(nonatomic) int w, h;
@property(nonatomic) NSUInteger stride;
@end
@implementation ApksimTarget
@end

#define POOL_MAX 6
static NSMutableArray<ApksimTarget *> *freeTargets;

static ApksimTarget *takeTarget(int w, int h) {
  @synchronized([ApksimTarget class]) {
    if (!freeTargets) freeTargets = [NSMutableArray new];
    for (NSUInteger i = 0; i < freeTargets.count; i++) {
      ApksimTarget *t = freeTargets[i];
      if (t.w == w && t.h == h) {
        [freeTargets removeObjectAtIndex:i];
        return t;
      }
    }
  }
  NSUInteger align = [device minimumLinearTextureAlignmentForPixelFormat:MTLPixelFormatBGRA8Unorm];
  ApksimTarget *t = [ApksimTarget new];
  t.w = w;
  t.h = h;
  t.stride = ((NSUInteger)w * 4 + align - 1) / align * align;
  t.buffer = [device newBufferWithLength:t.stride * h options:MTLResourceStorageModeShared];
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageRenderTarget;
  d.storageMode = MTLStorageModeShared;
  t.texture = [t.buffer newTextureWithDescriptor:d offset:0 bytesPerRow:t.stride];
  return t.texture ? t : nil;
}

static void giveTarget(ApksimTarget *t) {
  @synchronized([ApksimTarget class]) {
    [freeTargets addObject:t];
    if (freeTargets.count > POOL_MAX) [freeTargets removeObjectAtIndex:0];
  }
}

// Pipelined form of render: submit returns at once with a handle; collect waits for that work (normally
// long finished by then), copies the pixels out and frees the handle.
typedef struct { id<MTLCommandBuffer> cb; ApksimTarget *target; } Pending;

// Encodes one full-target pass and commits it; input (may be nil) is bound as texture 0, the shader's
// `uniform shader` input when it is a RenderEffect over layer content.
static jlong encode(JNIEnv *env, jlong pipeline, jobject uniforms, jint uniformBytes, jfloatArray transform, jint w, jint h,
                    const jlong *inputs, int inputCount, uint32_t clampMask) {
  id<MTLRenderPipelineState> p = (__bridge id<MTLRenderPipelineState>)(void *)pipeline;
  ApksimTarget *target = takeTarget(w, h);
  if (!target) return 0;
  id<MTLTexture> t = target.texture;
  MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
  pass.colorAttachments[0].texture = t;
  pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
  pass.colorAttachments[0].storeAction = MTLStoreActionStore;
  id<MTLCommandBuffer> cb = [queue commandBuffer];
  id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:pass];
  [e setRenderPipelineState:p];
  jfloat m[6];
  (*env)->GetFloatArrayRegion(env, transform, 0, 6, m);
  float rows[8] = {m[0], m[1], m[2], 0, m[3], m[4], m[5], 0};
  [e setFragmentBytes:rows length:sizeof rows atIndex:1];
  char empty[16] = {0};
  if (uniformBytes > 0) {
    [e setFragmentBytes:(*env)->GetDirectBufferAddress(env, uniforms) length:uniformBytes atIndex:0];
  } else {
    [e setFragmentBytes:empty length:sizeof empty atIndex:0];
  }
  for (int i = 0; i < inputCount; i++) {
    if (inputs[i]) [e setFragmentTexture:(__bridge id<MTLTexture>)(void *)inputs[i] atIndex:i];
  }
  // Which inputs repeat their edges (an image BitmapShader in CLAMP mode) rather than read transparent outside.
  [e setFragmentBytes:&clampMask length:sizeof clampMask atIndex:2];
  [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
  [e endEncoding];
  [cb commit];
  Pending *pending = calloc(1, sizeof(Pending)); // Zeroed: ARC releases a strong field's old value on store.
  pending->cb = cb;
  pending->target = target;
  return (jlong)(void *)CFBridgingRetain((__bridge id)(__bridge void *)[NSValue valueWithPointer:pending]);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_submit(JNIEnv *env, jclass c, jlong pipeline, jobject uniforms, jint uniformBytes,
                                                             jfloatArray transform, jint w, jint h) {
  @autoreleasepool {
    return encode(env, pipeline, uniforms, uniformBytes, transform, w, h, NULL, 0, 0);
  }
}

// An input image for an effect (a layer's content), kept between frames: BGRA, premultiplied, as Skia
// rasterizes here.
JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_newInput(JNIEnv *env, jclass c, jint w, jint h) {
  @autoreleasepool {
    if (!device) {
      device = MTLCreateSystemDefaultDevice();
      queue = [device newCommandQueue];
    }
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
    d.usage = MTLTextureUsageShaderRead;
    d.storageMode = MTLStorageModeShared;
    id<MTLTexture> t = [device newTextureWithDescriptor:d];
    return t ? (jlong)CFBridgingRetain(t) : 0;
  }
}

JNIEXPORT void JNICALL Java_dev_apksim_gpu_GpuNative_upload(JNIEnv *env, jclass c, jlong input, jobject pixels, jint stride) {
  @autoreleasepool {
    id<MTLTexture> t = (__bridge id<MTLTexture>)(void *)input;
    [t replaceRegion:MTLRegionMake2D(0, 0, t.width, t.height) mipmapLevel:0 withBytes:(*env)->GetDirectBufferAddress(env, pixels)
         bytesPerRow:stride];
  }
}

JNIEXPORT void JNICALL Java_dev_apksim_gpu_GpuNative_releaseInput(JNIEnv *env, jclass c, jlong input) {
  if (input) CFBridgingRelease((void *)input);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_submitEffect(JNIEnv *env, jclass c, jlong pipeline, jobject uniforms, jint uniformBytes,
                                                                   jfloatArray transform, jint w, jint h, jlong input) {
  @autoreleasepool {
    return encode(env, pipeline, uniforms, uniformBytes, transform, w, h, &input, 1, 0);
  }
}

// As submit, with [inputs] bound to the shader's `uniform shader` inputs in order; bit i of clampMask makes input i
// repeat its edges.
JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_submitInputs(JNIEnv *env, jclass c, jlong pipeline, jobject uniforms,
                                                                   jint uniformBytes, jfloatArray transform, jint w, jint h,
                                                                   jlongArray inputs, jint clampMask) {
  @autoreleasepool {
    jsize n = (*env)->GetArrayLength(env, inputs);
    if (n > 8) return 0;
    jlong handles[8];
    (*env)->GetLongArrayRegion(env, inputs, 0, n, handles);
    return encode(env, pipeline, uniforms, uniformBytes, transform, w, h, handles, n, (uint32_t)clampMask);
  }
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_gpu_GpuNative_collect(JNIEnv *env, jclass c, jlong handle, jobject out) {
  if (!handle) return JNI_FALSE;
  @autoreleasepool {
    NSValue *v = CFBridgingRelease((void *)handle);
    Pending *p = v.pointerValue;
    [p->cb waitUntilCompleted];
    jboolean ok = p->cb.status == MTLCommandBufferStatusCompleted;
    ApksimTarget *t = p->target;
    if (ok && out) {
      char *dst = (*env)->GetDirectBufferAddress(env, out);
      const char *src = t.buffer.contents;
      size_t row = (size_t)t.w * 4;
      if (t.stride == row) {
        memcpy(dst, src, row * t.h);
      } else {
        for (int y = 0; y < t.h; y++) memcpy(dst + row * y, src + t.stride * y, row);
      }
    }
    giveTarget(t);
    p->cb = nil;
    p->target = nil;
    free(p);
    return ok;
  }
}

// Scaled bitmap draws: a source texture resampled to a w x h target with bilinear filtering, as Skia samples a
// scaled drawImageRect (pixel centres at +0.5, clamped at the image's edges). Target pixel (x, y) reads texel
// coordinate origin + (x + 0.5, y + 0.5) * step.
static id<MTLRenderPipelineState> resamplePipeline;

static const char *RESAMPLE_MSL =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct V { float4 position [[position]]; };\n"
    "vertex V rs_vertex(uint id [[vertex_id]]) {\n"
    "  float2 p = float2(float((id << 1) & 2), float(id & 2));\n"
    "  V o; o.position = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
    "}\n"
    // Bilinear by hand in float, as Skia's raster pipeline does: the texture unit's own filtering uses fixed-point
    // weights and was up to 19 levels off at hard edges.
    "fragment float4 rs_fragment(V in [[stage_in]], constant float4& map [[buffer(0)]], texture2d<float> t [[texture(0)]]) {\n"
    "  float2 p = map.xy + in.position.xy * map.zw - 0.5;\n"
    "  float2 f = floor(p);\n"
    "  float2 w = p - f;\n"
    "  int2 hi = int2(t.get_width(), t.get_height()) - 1;\n"
    "  int2 a = clamp(int2(f), int2(0), hi);\n"
    "  int2 b = clamp(int2(f) + 1, int2(0), hi);\n"
    "  float4 top = mix(t.read(uint2(a.x, a.y)), t.read(uint2(b.x, a.y)), w.x);\n"
    "  float4 bottom = mix(t.read(uint2(a.x, b.y)), t.read(uint2(b.x, b.y)), w.x);\n"
    "  return mix(top, bottom, w.y);\n"
    "}\n";

static BOOL ensureResample(void) {
  if (resamplePipeline) return YES;
  NSError *error = nil;
  id<MTLLibrary> lib = [device newLibraryWithSource:@(RESAMPLE_MSL) options:nil error:&error];
  if (!lib) {
    NSLog(@"[apksim] resample shader: %@", error);
    return NO;
  }
  MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
  d.vertexFunction = [lib newFunctionWithName:@"rs_vertex"];
  d.fragmentFunction = [lib newFunctionWithName:@"rs_fragment"];
  d.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  resamplePipeline = [device newRenderPipelineStateWithDescriptor:d error:&error];
  return resamplePipeline != nil;
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_gpu_GpuNative_resample(JNIEnv *env, jclass c, jlong input, jfloat u0, jfloat v0, jfloat du,
                                                                 jfloat dv, jint w, jint h, jobject out) {
  @autoreleasepool {
    if (!input || !device || !ensureResample()) return JNI_FALSE;
    ApksimTarget *target = takeTarget(w, h);
    if (!target) return JNI_FALSE;
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture = target.texture;
    pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLCommandBuffer> cb = [queue commandBuffer];
    id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:pass];
    [e setRenderPipelineState:resamplePipeline];
    float map[4] = {u0, v0, du, dv};
    [e setFragmentBytes:map length:sizeof map atIndex:0];
    [e setFragmentTexture:(__bridge id<MTLTexture>)(void *)input atIndex:0];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [e endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    jboolean ok = cb.status == MTLCommandBufferStatusCompleted;
    if (ok) {
      char *dst = (*env)->GetDirectBufferAddress(env, out);
      const char *src = target.buffer.contents;
      size_t row = (size_t)w * 4;
      for (int y = 0; y < h; y++) memcpy(dst + row * y, src + target.stride * y, row);
    }
    giveTarget(target);
    return ok;
  }
}

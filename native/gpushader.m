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
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [e endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    if (cb.status != MTLCommandBufferStatusCompleted) return JNI_FALSE;
    [t getBytes:(*env)->GetDirectBufferAddress(env, out) bytesPerRow:w * 4 fromRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0];
    return JNI_TRUE;
  }
}

// Pipelined form of render: submit returns at once with a handle; collect waits for that work (normally
// long finished by then), copies the pixels out and frees the handle.
typedef struct { id<MTLCommandBuffer> cb; id<MTLTexture> tex; int w, h; } Pending;

// Encodes one full-target pass and commits it; input (may be nil) is bound as texture 0, the shader's
// `uniform shader` input when it is a RenderEffect over layer content.
static jlong encode(JNIEnv *env, jlong pipeline, jobject uniforms, jint uniformBytes, jfloatArray transform, jint w, jint h,
                    id<MTLTexture> input) {
  id<MTLRenderPipelineState> p = (__bridge id<MTLRenderPipelineState>)(void *)pipeline;
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageRenderTarget;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> t = [device newTextureWithDescriptor:d];
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
  if (input) [e setFragmentTexture:input atIndex:0];
  [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
  [e endEncoding];
  [cb commit];
  Pending *pending = calloc(1, sizeof(Pending)); // Zeroed: ARC releases a strong field's old value on store.
  pending->cb = cb;
  pending->tex = t;
  pending->w = w;
  pending->h = h;
  return (jlong)(void *)CFBridgingRetain((__bridge id)(__bridge void *)[NSValue valueWithPointer:pending]);
}

JNIEXPORT jlong JNICALL Java_dev_apksim_gpu_GpuNative_submit(JNIEnv *env, jclass c, jlong pipeline, jobject uniforms, jint uniformBytes,
                                                             jfloatArray transform, jint w, jint h) {
  @autoreleasepool {
    return encode(env, pipeline, uniforms, uniformBytes, transform, w, h, nil);
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
    return encode(env, pipeline, uniforms, uniformBytes, transform, w, h, (__bridge id<MTLTexture>)(void *)input);
  }
}

JNIEXPORT jboolean JNICALL Java_dev_apksim_gpu_GpuNative_collect(JNIEnv *env, jclass c, jlong handle, jobject out) {
  @autoreleasepool {
    NSValue *v = CFBridgingRelease((void *)handle);
    Pending *p = v.pointerValue;
    [p->cb waitUntilCompleted];
    jboolean ok = p->cb.status == MTLCommandBufferStatusCompleted;
    if (ok && out) {
      [p->tex getBytes:(*env)->GetDirectBufferAddress(env, out) bytesPerRow:p->w * 4 fromRegion:MTLRegionMake2D(0, 0, p->w, p->h) mipmapLevel:0];
    }
    p->cb = nil;
    p->tex = nil;
    free(p);
    return ok;
  }
}

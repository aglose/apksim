package dev.apksim.gpu;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** App calls to RenderEffect factories come through here (the DEX translator rewrites them). */
public final class GpuEffects {
  public static long runtimeEffects;
  public static long otherEffects;

  /** Effects that can run on the GPU, with the program and uniforms they were made with. */
  private static final Map<RenderEffect, TrackedRuntimeShader.EffectProgram> GPU = Collections.synchronizedMap(new WeakHashMap<>());

  /** -Dapksim.cpuLayerEffects=true leaves layer-content effects to HWUI on the CPU (for A/B runs). */
  private static final boolean CPU_LAYER_EFFECTS = Boolean.getBoolean("apksim.cpuLayerEffects");

  private GpuEffects() {}

  public static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String input) {
    runtimeEffects++;
    RenderEffect effect = RenderEffect.createRuntimeShaderEffect(shader, input);
    if (shader instanceof TrackedRuntimeShader t) {
      t.countEffect();
      TrackedRuntimeShader.EffectProgram p = CPU_LAYER_EFFECTS ? null : t.effectProgram(input);
      if (p != null) GPU.put(effect, p);
    }
    return effect;
  }

  /** The GPU form of an effect set on a layer, or null if HWUI has to apply it on the CPU. */
  static TrackedRuntimeShader.EffectProgram gpuProgram(RenderEffect effect) {
    return effect == null ? null : GPU.get(effect);
  }

  public static RenderEffect createBlurEffect(float x, float y, android.graphics.Shader.TileMode mode) {
    otherEffects++;
    return RenderEffect.createBlurEffect(x, y, mode);
  }

  public static RenderEffect createBlurEffect(float x, float y, RenderEffect input, android.graphics.Shader.TileMode mode) {
    otherEffects++;
    return RenderEffect.createBlurEffect(x, y, input, mode);
  }
}

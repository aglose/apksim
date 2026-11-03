package dev.apksim.gpu;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;

/** App calls to RenderEffect factories come through here (the DEX translator rewrites them). */
public final class GpuEffects {
  public static long runtimeEffects;
  public static long otherEffects;

  private GpuEffects() {}

  public static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String input) {
    runtimeEffects++;
    if (shader instanceof TrackedRuntimeShader t) t.countEffect();
    return RenderEffect.createRuntimeShaderEffect(shader, input);
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

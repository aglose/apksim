#!/usr/bin/env python3
"""Writes runtime/src/main/java/dev/apksim/gl/ShadowSimGLES20.java and ShadowSimGLES30.java from the framework's
android.opengl.GLES20/GLES30 signatures (javap on work/lib/android-all-*.jar), forwarding each call to the real GLES
through dev.apksim.gl.Gl, the way Android's own bindings (android_opengl_GLES20.cpp) map Java to C:

- an array followed by an int offset is one pointer, to the array from that offset (copied in and back out);
- a java.nio.Buffer is a pointer to the buffer from its position;
- where an overload takes an int in the place another takes a Buffer, the int is a buffer-object offset (a pointer
  value), as in glVertexAttribPointer and glDrawElements;
- GLsizeiptr/GLintptr arguments are 64-bit in C though Java passes ints (WIDEN);
- GLboolean is a byte.

Methods it cannot map (Buffer results, a scalar byte, a missing count) are left to Robolectric. Run after changing
the framework jar:  scripts/gen-gles-shadows.py
"""
import glob, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
JAR = sorted(glob.glob(ROOT + "/work/lib/android-all-*.jar"))[-1]
OUT = ROOT + "/runtime/src/main/java/dev/apksim/gl"

# Java parameter positions whose C type is GLsizeiptr or GLintptr.
WIDEN = {
    "glBufferData": {1}, "glBufferSubData": {1, 2}, "glBindBufferRange": {3, 4},
    "glCopyBufferSubData": {2, 3, 4}, "glFlushMappedBufferRange": {1, 2},
}
# Robolectric's ShadowGLES20 stand-ins, kept for when there is no real GLES.
FALLBACK = {
    "glGenFramebuffers(int,int[],int)", "glGenTextures(int,int[],int)", "glCreateShader(int)", "glCreateProgram()",
    "glGetShaderiv(int,int,int[],int)", "glGetProgramiv(int,int,int[],int)", "glCheckFramebufferStatus(int)",
}
SKIP = {"glGetUniformIndices"}  # C takes a count Java leaves out
KEEP = {"glVertexAttribPointer", "glVertexAttribIPointer"}  # GL keeps the pointer past the call
BUFFERS = {"java.nio.Buffer", "java.nio.ByteBuffer", "java.nio.ShortBuffer", "java.nio.CharBuffer", "java.nio.IntBuffer",
           "java.nio.FloatBuffer", "java.nio.LongBuffer", "java.nio.DoubleBuffer"}
ARRAYS = {"int[]", "float[]", "long[]", "byte[]", "boolean[]"}


def methods(cls):
    out = subprocess.run(["javap", "-cp", JAR, "android.opengl." + cls], capture_output=True, text=True, check=True).stdout
    found = []
    for line in out.splitlines():
        m = re.match(r"\s*public static (?:native )?(\S+) (gl\w+)\((.*)\);", line)
        if m:
            ps = [p.strip() for p in m[3].split(",")] if m[3].strip() else []
            found.append((m[2], m[1], ps))
    return found


def offset_pointer_positions(all_methods):
    """(name, arity) -> positions where one overload has a Buffer and another an int: the int is a pointer."""
    by = {}
    for name, ret, ps in all_methods:
        by.setdefault((name, len(ps)), []).append(ps)
    out = {}
    for key, overloads in by.items():
        for i in range(key[1]):
            kinds = {ps[i] for ps in overloads}
            if "int" in kinds and kinds & BUFFERS:
                out.setdefault(key, set()).add(i)
    return out


def default(ret):
    return {"void": "", "int": " 0", "float": " 0f", "long": " 0L", "boolean": " false"}.get(ret, " null")


def gen_method(name, ret, ps, pointer_ints):
    sig_java = name + "(" + ",".join(ps) + ")"
    params = ", ".join(f"{t} p{i}" for i, t in enumerate(ps))
    head = f"  @Implementation\n  protected static {ret} {name}({params}) {{\n"
    args = ", ".join(f"p{i}" for i in range(len(ps)))
    if sig_java in FALLBACK:
        fallback = f"    if (!Gl.on()) {'return ' if ret != 'void' else ''}ShadowGLES20.{name}({args});\n"
        if ret == "void":
            fallback = f"    if (!Gl.on()) {{\n      ShadowGLES20.{name}({args});\n      return;\n    }}\n"
    else:
        fallback = f"    if (!Gl.on()) return{default(ret)};\n"

    # Calls whose Java form differs from C.
    special = {
        "glShaderSource(int,java.lang.String)": "Gl.glShaderSource(p0, p1);",
        "glGetShaderInfoLog(int)": "return Gl.infoLog(false, p0);",
        "glGetProgramInfoLog(int)": "return Gl.infoLog(true, p0);",
        "glGetShaderSource(int)": "return Gl.shaderSource(p0);",
        "glGetString(int)": 'return Gl.string(Gl.call("glGetString", "PI", p0));',
        "glGetStringi(int,int)": 'return Gl.string(Gl.call("glGetStringi", "PII", p0, p1));',
        "glGetActiveAttrib(int,int,int[],int,int[],int)": "return Gl.active(false, p0, p1, p2, p3, p4, p5);",
        "glGetActiveUniform(int,int,int[],int,int[],int)": "return Gl.active(true, p0, p1, p2, p3, p4, p5);",
        "glGetActiveAttrib(int,int,java.nio.IntBuffer,java.nio.IntBuffer)": "return Gl.active(false, p0, p1, p2, 0, p3, 0);",
        "glGetActiveUniform(int,int,java.nio.IntBuffer,java.nio.IntBuffer)": "return Gl.active(true, p0, p1, p2, 0, p3, 0);",
    }
    if sig_java in special:
        return head + fallback + "    " + special[sig_java] + "\n  }\n"

    if name in SKIP or ret not in ("void", "int", "float", "long", "boolean") or "byte" in ps:
        return None
    if any(t not in ("int", "float", "long", "boolean", "java.lang.String", "java.lang.String[]") and t not in BUFFERS
           and t not in ARRAYS for t in ps):
        return None

    letters = {"void": "V", "int": "I", "float": "F", "long": "J", "boolean": "B"}[ret]
    pre, call_args, post = [], [], []
    i = 0
    while i < len(ps):
        t = ps[i]
        if t in ARRAYS:
            if i + 1 >= len(ps) or ps[i + 1] != "int":
                return None
            pre.append(f"      Gl.Copy c{i} = Gl.array(a, p{i}, p{i + 1});")
            post.append(f"      c{i}.back();")
            call_args.append(f"c{i}.seg()")
            letters += "P"
            i += 2
            continue
        if t in BUFFERS:
            keep = "true" if name in KEEP else "false"
            pre.append(f"      Gl.Copy c{i} = Gl.buffer(a, p{i}, {keep});")
            post.append(f"      c{i}.back();")
            call_args.append(f"c{i}.seg()")
            letters += "P"
        elif t == "java.lang.String":
            call_args.append(f"Gl.cstr(a, p{i})")
            letters += "P"
        elif t == "java.lang.String[]":
            call_args.append(f"Gl.cstrs(a, p{i})")
            letters += "P"
        elif t == "boolean":
            call_args.append(f"Gl.bool(p{i})")
            letters += "B"
        elif t == "int" and i in pointer_ints.get((name, len(ps)), set()):
            call_args.append(f"Gl.address(p{i})")
            letters += "P"
        elif t == "int" and i in WIDEN.get(name, set()):
            call_args.append(f"(long) p{i}")
            letters += "J"
        else:
            call_args.append(f"p{i}")
            letters += {"int": "I", "float": "F", "long": "J"}[t]
        i += 1
    call = f'Gl.call("{name}", "{letters}"' + "".join(", " + x for x in call_args) + ")"
    body = ["    try (Arena a = Arena.ofConfined()) {"] + pre
    if ret == "void":
        body.append(f"      {call};")
        body += post
    else:
        body.append(f"      Object r = {call};")
        body += post
        conv = {"int": "(int) r", "float": "(float) r", "long": "(long) r", "boolean": "((byte) r) != 0"}[ret]
        body.append(f"      return {conv};")
    body.append("    }")
    return head + fallback + "\n".join(body) + "\n  }\n"


def write(cls, parent, own, pointer_ints):
    lines = [
        "package dev.apksim.gl;",
        "",
        "import java.lang.foreign.Arena;",
        "import org.robolectric.annotation.Implementation;",
        "import org.robolectric.annotation.Implements;",
        "import org.robolectric.shadows.ShadowGLES20;",
        "",
        f"/** Generated by scripts/gen-gles-shadows.py: android.opengl.{cls} on a real GLES (see {{@link Gl}}). Do not edit. */",
        f"@Implements(android.opengl.{cls}.class)",
        f"public class ShadowSim{cls} extends {parent} {{",
    ]
    n = skipped = 0
    for name, ret, ps in own:
        m = gen_method(name, ret, ps, pointer_ints)
        if m is None:
            skipped += 1
            continue
        lines.append(m)
        n += 1
    lines.append("}")
    with open(f"{OUT}/ShadowSim{cls}.java", "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"ShadowSim{cls}: {n} methods, {skipped} left to Robolectric")


def main():
    g20, g30 = methods("GLES20"), methods("GLES30")
    pointer_ints = offset_pointer_positions(g20 + g30)
    write("GLES20", "ShadowGLES20", g20, pointer_ints)
    write("GLES30", "ShadowSimGLES20", g30, pointer_ints)


if __name__ == "__main__":
    sys.exit(main())

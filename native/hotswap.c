// Hot swap (HotSwap.java, spike S2): JVMTI RedefineClasses for translated app classes, from inside the running JVM.
// No agent: the capability is asked for in the live phase at the first hot swap, so a launch that never swaps keeps
// the JIT free of the dependencies HotSpot records once a class may be redefined.
#include <jni.h>
#include <jvmti.h>
#include <stdlib.h>
#include <string.h>

static jvmtiEnv *ti;

// Redefines classes[i] with bytes[i], all at once (one safepoint). Returns the jvmtiError (0 for none), or a negative
// value when JVMTI or the capability is not available (-1000 - the AddCapabilities error).
JNIEXPORT jint JNICALL Java_dev_apksim_HotSwap_nativeRedefine(JNIEnv *env, jclass self, jobjectArray classes, jobjectArray bytes) {
  if (ti == NULL) {
    JavaVM *vm;
    jvmtiEnv *t;
    if ((*env)->GetJavaVM(env, &vm) != 0 || (*vm)->GetEnv(vm, (void **)&t, JVMTI_VERSION_1_2) != JNI_OK) return -1;
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof caps);
    caps.can_redefine_classes = 1;
    jvmtiError e = (*t)->AddCapabilities(t, &caps);
    if (e != JVMTI_ERROR_NONE) {
      (*t)->DisposeEnvironment(t);
      return -1000 - (jint)e;
    }
    ti = t;
  }
  jsize n = (*env)->GetArrayLength(env, classes);
  jvmtiClassDefinition *defs = calloc((size_t)n, sizeof *defs);
  jbyteArray *arrays = calloc((size_t)n, sizeof *arrays);
  for (jsize i = 0; i < n; i++) {
    defs[i].klass = (jclass)(*env)->GetObjectArrayElement(env, classes, i);
    arrays[i] = (jbyteArray)(*env)->GetObjectArrayElement(env, bytes, i);
    defs[i].class_byte_count = (*env)->GetArrayLength(env, arrays[i]);
    defs[i].class_bytes = (const unsigned char *)(*env)->GetByteArrayElements(env, arrays[i], NULL);
  }
  jvmtiError e = (*ti)->RedefineClasses(ti, n, defs);
  for (jsize i = 0; i < n; i++) {
    (*env)->ReleaseByteArrayElements(env, arrays[i], (jbyte *)defs[i].class_bytes, JNI_ABORT);
    (*env)->DeleteLocalRef(env, arrays[i]);
    (*env)->DeleteLocalRef(env, defs[i].klass);
  }
  free(arrays);
  free(defs);
  return (jint)e;
}

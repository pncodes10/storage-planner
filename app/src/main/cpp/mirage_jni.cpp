// Java bridge to Mirage (Cactus Compute's vision model) through the Cactus engine's C API (cactus.h, libcactus.a,
// Apache 2.0). Built by tools/mirage/build_native.sh into lib/arm64-v8a/libmirage.so. Only Mirage's two calls are used:
// cactus_load (the model file, which the engine copies) and cactus_describe (one picture in, JSON out). Nothing here
// opens files or the network; the picture and the model come from Java as byte arrays.
#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include "cactus.h"

// The engine's only file-removing code is a tool-index cache for the Needle text model (ToolEmbedStore::write_file),
// which Mirage never reaches. Defining remove() here makes that call land in this function instead of the C library,
// so code in this library can never delete a file. tools/audit_apk.py checks the built library imports no
// remove / unlink / rename at all.
extern "C" int remove(const char*) { errno = EPERM; return -1; }

static jstring str(JNIEnv* env, const char* s) { return env->NewStringUTF(s ? s : ""); }

extern "C" JNIEXPORT jint JNICALL
Java_com_storageplanner_app_MirageEngine_load(JNIEnv* env, jclass, jbyteArray model) {
    jsize n = env->GetArrayLength(model);
    jbyte* b = env->GetByteArrayElements(model, nullptr);
    if (!b) return -1;
    int r = cactus_load(reinterpret_cast<const unsigned char*>(b), static_cast<unsigned long long>(n));
    env->ReleaseByteArrayElements(model, b, JNI_ABORT);
    if (r < 0) return r;
    return (cactus_models() & CACTUS_VISION) ? 0 : -2;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_storageplanner_app_MirageEngine_lastError(JNIEnv* env, jclass) { return str(env, cactus_last_error()); }

// rgb: width * height * 3 bytes. Returns the JSON as UTF-8 bytes (decoded in Java: NewStringUTF wants modified
// UTF-8), or null with lastError() set.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_storageplanner_app_MirageEngine_describe(JNIEnv* env, jclass, jbyteArray rgb, jint w, jint h, jstring options) {
    if (env->GetArrayLength(rgb) != static_cast<jsize>(w) * h * 3) return nullptr;
    const char* opt = env->GetStringUTFChars(options, nullptr);
    jbyte* px = env->GetByteArrayElements(rgb, nullptr);
    std::string out;
    int cap = 1 << 20, r = -1;
    for (int attempt = 0; attempt < 3; attempt++) {
        out.assign(static_cast<size_t>(cap), '\0');
        r = cactus_describe(reinterpret_cast<const unsigned char*>(px), w, h, opt, nullptr, nullptr, &out[0], cap);
        if (r >= 0) break;
        // "output buffer is too small: N bytes needed": try once more with room for it
        const char* e = cactus_last_error();
        const char* k = e ? strstr(e, "too small: ") : nullptr;
        if (!k) break;
        long need = strtol(k + 11, nullptr, 10);
        if (need <= cap || need > (64 << 20)) break;
        cap = static_cast<int>(need + 1024);
    }
    env->ReleaseByteArrayElements(rgb, px, JNI_ABORT);
    env->ReleaseStringUTFChars(options, opt);
    if (r < 0) return nullptr;
    jsize len = static_cast<jsize>(strlen(out.c_str()));
    jbyteArray a = env->NewByteArray(len);
    if (a) env->SetByteArrayRegion(a, 0, len, reinterpret_cast<const jbyte*>(out.data()));
    return a;
}

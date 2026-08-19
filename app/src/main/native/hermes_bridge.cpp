#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <functional>

#include <jsi/jsi.h>
#include <hermes/hermes.h>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "HermesJNI", __VA_ARGS__)

using namespace facebook::jsi;
using facebook::hermes::makeHermesRuntime;

// Holds a Hermes runtime. Skia drawing is exposed to JS via host functions
// that call the existing SkiaCanvas JNI natives.
struct JsCtx {
    std::unique_ptr<Runtime> runtime;
    JNIEnv* env = nullptr;      // thread-local env of the creating thread
    jlong canvasHandle = 0;     // current Skia canvas the JS draws to
};

// Cached JNI method IDs for SkiaCanvas natives.
static jclass sCanvasClass = nullptr;
static struct {
    jmethodID clear;
    jmethodID fillRect;
    jmethodID fillCircle;
    jmethodID drawRect;
    jmethodID drawCircle;
    jmethodID drawLine;
    jmethodID drawText;
    jmethodID measureText;
} sM;

static void initMethods(JNIEnv* env) {
    if (sCanvasClass) return;
    jclass cls = env->FindClass("com/example/skiajni/SkiaCanvas");
    sCanvasClass = (jclass)env->NewGlobalRef(cls);
    sM.clear       = env->GetStaticMethodID(cls, "nClear",       "(JI)V");
    sM.fillRect    = env->GetStaticMethodID(cls, "nFillRect",    "(JFFFFI)V");
    sM.fillCircle  = env->GetStaticMethodID(cls, "nFillCircle",  "(JFFFI)V");
    sM.drawRect    = env->GetStaticMethodID(cls, "nDrawRect",    "(JFFFFIFI)V");
    sM.drawCircle  = env->GetStaticMethodID(cls, "nDrawCircle",  "(JFFFIFI)V");
    sM.drawLine    = env->GetStaticMethodID(cls, "nDrawLine",    "(JFFFFIFI)V");
    sM.drawText    = env->GetStaticMethodID(cls, "nDrawText",    "(JLjava/lang/String;FFIF)V");
    sM.measureText = env->GetStaticMethodID(cls, "nMeasureText", "(Ljava/lang/String;F)F");
}

// Helper to build a host function bound to a ctx.
static inline Function makeHost(JsCtx* ctx, const char* name,
                                std::function<Value(JsCtx*, Runtime&, const Value*, size_t)> impl) {
    return Function::createFromHostFunction(
        *ctx->runtime, PropNameID::forAscii(*ctx->runtime, name), 0,
        [ctx, impl](Runtime& rt, const Value&, const Value* args, size_t n) -> Value {
            return impl(ctx, rt, args, n);
        });
}

static inline double num(const Value* args, size_t i) { return args[i].getNumber(); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_skiajni_JsCanvas_nCreate(JNIEnv* env, jclass, jint w, jint h) {
    initMethods(env);
    auto runtime = makeHermesRuntime();
    if (!runtime) { LOG("makeHermesRuntime failed"); return 0; }

    auto* ctx = new JsCtx();
    ctx->runtime = std::move(runtime);
    ctx->env = env;

    auto& rt = *ctx->runtime;

    // Inject Skia drawing host functions into the JS global scope.
    // JS signature: clear(handle,color); fillRect(handle,x,y,w,h,color); ...
    rt.global().setProperty(rt, "clear",
        makeHost(ctx, "clear", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)(long long)a[0].asNumber();
            jint color = (jint)a[1].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.clear, h, color);
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "fillRect",
        makeHost(ctx, "fillRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),(jint)num(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "fillCircle",
        makeHost(ctx, "fillCircle", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillCircle, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jint)num(a,4));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawRect",
        makeHost(ctx, "drawRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jint)num(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawCircle",
        makeHost(ctx, "drawCircle", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawCircle, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jint)num(a,4),(jfloat)num(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawLine",
        makeHost(ctx, "drawLine", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawLine, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jint)num(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawText",
        makeHost(ctx, "drawText", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            std::string text = a[1].getString(rt).utf8(rt);
            jstring jt = e->NewStringUTF(text.c_str());
            e->CallStaticVoidMethod(sCanvasClass, sM.drawText, h, jt,
                (jfloat)num(a,2),(jfloat)num(a,3),(jint)num(a,4),(jfloat)num(a,5));
            e->DeleteLocalRef(jt);
            return Value::undefined();
        }));

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_JsCanvas_nDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<JsCtx*>(h);
}

// Evaluate JS and return the result (or error) as a string.
JNIEXPORT jstring JNICALL
Java_com_example_skiajni_JsCanvas_nEval(JNIEnv* env, jclass, jlong h, jstring js) {
    auto* ctx = reinterpret_cast<JsCtx*>(h);
    if (!ctx || !ctx->runtime) return nullptr;
    ctx->env = env; // keep in sync (called on same thread)
    const char* src = env->GetStringUTFChars(js, nullptr);
    std::string result;
    try {
        auto& rt = *ctx->runtime;
        Value v = rt.evaluateJavaScript(
            std::make_shared<StringBuffer>(std::string(src)), "scene.js");
        if (v.isString()) result = v.getString(rt).utf8(rt);
        else if (v.isNumber()) result = std::to_string(v.getNumber());
        else if (v.isBool()) result = v.getBool() ? "true" : "false";
        else result = "ok";
    } catch (const JSError& e) {
        result = "JS Error: " + e.getMessage();
    } catch (const std::exception& e) {
        result = std::string("Error: ") + e.what();
    }
    env->ReleaseStringUTFChars(js, src);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"

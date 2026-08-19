#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <functional>
#include <unordered_map>
#include <vector>

#include <jsi/jsi.h>
#include <hermes/hermes.h>

#include <yoga/YGNode.h>
#include <yoga/YGNodeStyle.h>
#include <yoga/YGNodeLayout.h>
#include <yoga/YGConfig.h>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "HermesJNI", __VA_ARGS__)

using namespace facebook::jsi;
using facebook::hermes::makeHermesRuntime;

// Holds a Hermes runtime. Skia drawing is exposed to JS via host functions
// that call the existing SkiaCanvas JNI natives.
struct JsCtx {
    std::unique_ptr<Runtime> runtime;
    JNIEnv* env = nullptr;      // thread-local env of the creating thread
    jlong canvasHandle = 0;     // current Skia canvas the JS draws to
    // Pending async image loads: id -> JS callback(handle). The Java side
    // delivers the decoded Skia image handle via nDeliverImage.
    uint64_t nextLoadId = 1;
    std::unordered_map<uint64_t, Function> pendingLoads;
    // Timers: id -> {callback, dueMs (recurrence interval for intervals)}
    uint64_t nextTimerId = 1;
    std::unordered_map<uint64_t, std::pair<Function, double>> timers;
    // Monotonic clock (ms), updated by Java each frame via nPumpTimers.
    double clockMs = 0;
    // For repeating timers we store the interval; one-shot store -1.
};

// Cached JNI method IDs for SkiaCanvas natives.
static jclass sCanvasClass = nullptr;
static struct {
    jmethodID clear;
    jmethodID fillRect;
    jmethodID fillRoundRect;
    jmethodID fillCircle;
    jmethodID drawRect;
    jmethodID drawRoundRect;
    jmethodID drawCircle;
    jmethodID drawLine;
    jmethodID drawText;
    jmethodID measureText;
    jmethodID nImageCreateFromBytes;
    jmethodID nImageDestroy;
    jmethodID nImageGetWidth;
    jmethodID nImageGetHeight;
    jmethodID nDrawImage;
    jmethodID nDrawImageRounded;
    jmethodID nFetchImageAsync;
    jmethodID nSave;
    jmethodID nRestore;
    jmethodID nTranslate;
    jmethodID nScale;
    jmethodID nRotate;
    jmethodID nClipRect;
    jmethodID nClipPath;
    jmethodID nFillOval;
    jmethodID nDrawGradient;
    jmethodID nPathCreate;
    jmethodID nPathDestroy;
    jmethodID nPathReset;
    jmethodID nPathMoveTo;
    jmethodID nPathLineTo;
    jmethodID nPathQuadTo;
    jmethodID nPathCubicTo;
    jmethodID nPathClose;
    jmethodID nDrawPath;
} sM;

static void initMethods(JNIEnv* env) {
    if (sCanvasClass) return;
    jclass cls = env->FindClass("com/example/skiajni/SkiaCanvas");
    sCanvasClass = (jclass)env->NewGlobalRef(cls);
    sM.clear       = env->GetStaticMethodID(cls, "nClear",       "(JI)V");
    sM.fillRect     = env->GetStaticMethodID(cls, "nFillRect",     "(JFFFFI)V");
    sM.fillRoundRect= env->GetStaticMethodID(cls, "nFillRoundRect","(JFFFFFFI)V");
    sM.fillCircle   = env->GetStaticMethodID(cls, "nFillCircle",   "(JFFFI)V");
    sM.drawRect     = env->GetStaticMethodID(cls, "nDrawRect",     "(JFFFFIF)V");
    sM.drawRoundRect= env->GetStaticMethodID(cls, "nDrawRoundRect","(JFFFFFFIF)V");
    sM.drawCircle   = env->GetStaticMethodID(cls, "nDrawCircle",   "(JFFFIF)V");
    sM.drawLine     = env->GetStaticMethodID(cls, "nDrawLine",     "(JFFFFIF)V");
    sM.drawText     = env->GetStaticMethodID(cls, "nDrawText",     "(JLjava/lang/String;FFIF)V");
    sM.measureText  = env->GetStaticMethodID(cls, "nMeasureText",  "(Ljava/lang/String;F)F");
    sM.nImageCreateFromBytes = env->GetStaticMethodID(cls, "nImageCreateFromBytes", "([B)J");
    sM.nImageDestroy = env->GetStaticMethodID(cls, "nImageDestroy", "(J)V");
    sM.nImageGetWidth = env->GetStaticMethodID(cls, "nImageGetWidth", "(J)I");
    sM.nImageGetHeight = env->GetStaticMethodID(cls, "nImageGetHeight", "(J)I");
    sM.nDrawImage = env->GetStaticMethodID(cls, "nDrawImage", "(JJFFFFF)V");
    sM.nDrawImageRounded = env->GetStaticMethodID(cls, "nDrawImageRounded", "(JJFFFFF)V");
    sM.nFetchImageAsync = env->GetStaticMethodID(cls, "nFetchImageAsync", "(Ljava/lang/String;JJ)V");
    sM.nSave = env->GetStaticMethodID(cls, "nSave", "(J)V");
    sM.nRestore = env->GetStaticMethodID(cls, "nRestore", "(J)V");
    sM.nTranslate = env->GetStaticMethodID(cls, "nTranslate", "(JFF)V");
    sM.nScale = env->GetStaticMethodID(cls, "nScale", "(JFF)V");
    sM.nRotate = env->GetStaticMethodID(cls, "nRotate", "(JF)V");
    sM.nClipRect = env->GetStaticMethodID(cls, "nClipRect", "(JFFFF)V");
    sM.nClipPath = env->GetStaticMethodID(cls, "nClipPath", "(JJ)V");
    sM.nFillOval = env->GetStaticMethodID(cls, "nFillOval", "(JFFFFI)V");
    sM.nDrawGradient = env->GetStaticMethodID(cls, "nDrawGradient", "(JFFFFIII)V");
    sM.nPathCreate = env->GetStaticMethodID(cls, "nPathCreate", "()J");
    sM.nPathDestroy = env->GetStaticMethodID(cls, "nPathDestroy", "(J)V");
    sM.nPathReset = env->GetStaticMethodID(cls, "nPathReset", "(J)V");
    sM.nPathMoveTo = env->GetStaticMethodID(cls, "nPathMoveTo", "(JFF)V");
    sM.nPathLineTo = env->GetStaticMethodID(cls, "nPathLineTo", "(JFF)V");
    sM.nPathQuadTo = env->GetStaticMethodID(cls, "nPathQuadTo", "(JFFFF)V");
    sM.nPathCubicTo = env->GetStaticMethodID(cls, "nPathCubicTo", "(JFFFFFF)V");
    sM.nPathClose = env->GetStaticMethodID(cls, "nPathClose", "(J)V");
    sM.nDrawPath = env->GetStaticMethodID(cls, "nDrawPath", "(JJIFZ)V");
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

// Convert a JS number to a 32-bit color jint (0xAARRGGBB). A direct
// `(jint)double` is undefined behavior when the double exceeds INT32_MAX,
// so we route through uint32_t first for a well-defined bit reinterpretation.
static inline jint colorJint(const Value* args, size_t i) {
    return (jint)(uint32_t)(int64_t)num(args, i);
}

// Convenience: call a static void method with a single long arg.
static inline void e_call(JsCtx* c, jmethodID m, jlong v) {
    c->env->CallStaticVoidMethod(sCanvasClass, m, v);
}

// Convert a JS number-array into a jbyteArray.
static jbyteArray bytesFromJs(JsCtx* c, Runtime& rt, const Value& v) {
    JNIEnv* e = c->env;
    if (v.isObject() && v.getObject(rt).isArray(rt)) {
        auto ar = v.getObject(rt).getArray(rt);
        size_t len = ar.length(rt);
        jbyteArray arr = e->NewByteArray((jsize)len);
        jbyte* dst = e->GetByteArrayElements(arr, nullptr);
        for (size_t i = 0; i < len; i++) dst[i] = (jbyte)ar.getValueAtIndex(rt, (uint32_t)i).asNumber();
        e->ReleaseByteArrayElements(arr, dst, 0);
        return arr;
    }
    return nullptr;
}

// Pump due timers. `nowMs` is the current monotonic clock (from Java).
// For one-shot timers the stored value is the due ms; for intervals it is the
// interval (positive) and we re-schedule by adding the interval to the clock.
static void rnPumpTimers(JsCtx* c, double nowMs) {
    c->clockMs = nowMs;
    // Collect ids that are due first (callbacks may register/clear timers).
    std::vector<uint64_t> due;
    for (auto& kv : c->timers) {
        double v = kv.second.second;
        if (v > 0 && nowMs >= v) due.push_back(kv.first);       // one-shot due
        else if (v < 0) due.push_back(kv.first);                 // interval due
    }
    for (uint64_t id : due) {
        auto it = c->timers.find(id);
        if (it == c->timers.end()) continue;
        bool interval = it->second.second < 0;
        double intervalMs = interval ? -it->second.second : 0;
        Function cb = std::move(it->second.first);
        c->timers.erase(it);
        try {
            auto& rt = *c->runtime;
            cb.call(rt, nullptr, 0);
        } catch (const JSError&) {}
        if (interval) {
            c->timers.emplace(id, std::make_pair(std::move(cb), -(nowMs + intervalMs)));
        }
    }
}

// Register a timer. `repeat` selects setInterval (true) vs setTimeout (false).
// For one-shot we store the absolute due time; for intervals we store the
// negative interval so rnPumpTimers can reschedule.
static uint64_t rnTimer(JsCtx* c, Function cb, double ms, bool repeat) {
    uint64_t id = c->nextTimerId++;
    if (repeat) {
        c->timers.emplace(id, std::make_pair(std::move(cb), -ms));
    } else {
        c->timers.emplace(id, std::make_pair(std::move(cb), c->clockMs + ms));
    }
    return id;
}

static void rnClearTimer(JsCtx* c, uint64_t id) {
    c->timers.erase(id);
}

// ── Yoga enum mappers ────────────────────────────────────────────────
static YGFlexDirection ygFlexDir(const std::string& s) {
    if (s == "row") return YGFlexDirectionRow;
    if (s == "row-reverse") return YGFlexDirectionRowReverse;
    if (s == "column-reverse") return YGFlexDirectionColumnReverse;
    return YGFlexDirectionColumn;
}
static YGJustify ygJustify(const std::string& s) {
    if (s == "center") return YGJustifyCenter;
    if (s == "flex-end") return YGJustifyFlexEnd;
    if (s == "space-between") return YGJustifySpaceBetween;
    if (s == "space-around") return YGJustifySpaceAround;
    if (s == "space-evenly") return YGJustifySpaceEvenly;
    return YGJustifyFlexStart;
}
static YGAlign ygAlign(const std::string& s) {
    if (s == "center") return YGAlignCenter;
    if (s == "flex-end") return YGAlignFlexEnd;
    if (s == "baseline") return YGAlignBaseline;
    if (s == "auto") return YGAlignAuto;
    return YGAlignStretch;
}
static YGDisplay ygDisplay(const std::string& s) {
    if (s == "none") return YGDisplayNone;
    return YGDisplayFlex;
}
static YGEdge ygEdge(const std::string& s) {
    if (s == "left") return YGEdgeLeft;
    if (s == "top") return YGEdgeTop;
    if (s == "right") return YGEdgeRight;
    if (s == "bottom") return YGEdgeBottom;
    return YGEdgeAll;
}

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
            jint color = (jint)(uint32_t)(int64_t)a[1].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.clear, h, color);
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "fillRect",
        makeHost(ctx, "fillRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),colorJint(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "fillCircle",
        makeHost(ctx, "fillCircle", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillCircle, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),colorJint(a,4));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "fillRoundRect",
        makeHost(ctx, "fillRoundRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillRoundRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jfloat)num(a,5),(jfloat)num(a,6),colorJint(a,7));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawRect",
        makeHost(ctx, "drawRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                colorJint(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawRoundRect",
        makeHost(ctx, "drawRoundRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawRoundRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jfloat)num(a,5),(jfloat)num(a,6),colorJint(a,7),(jfloat)num(a,8));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawCircle",
        makeHost(ctx, "drawCircle", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawCircle, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),colorJint(a,4),(jfloat)num(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawLine",
        makeHost(ctx, "drawLine", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawLine, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                colorJint(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawText",
        makeHost(ctx, "drawText", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            std::string text = a[1].getString(rt).utf8(rt);
            jstring jt = e->NewStringUTF(text.c_str());
            e->CallStaticVoidMethod(sCanvasClass, sM.drawText, h, jt,
                (jfloat)num(a,2),(jfloat)num(a,3),colorJint(a,4),(jfloat)num(a,5));
            e->DeleteLocalRef(jt);
            return Value::undefined();
        }));

    // measureText(text, fontSize) -> width (pixels)
    rt.global().setProperty(rt, "measureText",
        makeHost(ctx, "measureText", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            JNIEnv* e = c->env;
            std::string text = a[0].getString(rt).utf8(rt);
            jstring jt = e->NewStringUTF(text.c_str());
            jfloat w = e->CallStaticFloatMethod(sCanvasClass, sM.measureText, jt,
                (jfloat)num(a, 1));
            e->DeleteLocalRef(jt);
            return Value(w);
        }));

    // ── Transforms (for scrolling / composition) ─────────────────────
    rt.global().setProperty(rt, "save",
        makeHost(ctx, "save", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            e_call(c, sM.nSave, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "restore",
        makeHost(ctx, "restore", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            e_call(c, sM.nRestore, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "translate",
        makeHost(ctx, "translate", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nTranslate,
                (jlong)a[0].asNumber(), (jfloat)num(a,1), (jfloat)num(a,2));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "scale",
        makeHost(ctx, "scale", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nScale,
                (jlong)a[0].asNumber(), (jfloat)num(a,1), (jfloat)num(a,2));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "rotate",
        makeHost(ctx, "rotate", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nRotate,
                (jlong)a[0].asNumber(), (jfloat)num(a,1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "clipRect",
        makeHost(ctx, "clipRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nClipRect,
                (jlong)a[0].asNumber(), (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "clipPath",
        makeHost(ctx, "clipPath", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nClipPath,
                (jlong)a[0].asNumber(), (jlong)a[1].asNumber());
            return Value::undefined();
        }));

    // ── Filled shapes & gradient ─────────────────────────────────────
    rt.global().setProperty(rt, "fillOval",
        makeHost(ctx, "fillOval", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nFillOval,
                (jlong)a[0].asNumber(), (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),colorJint(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "drawGradient",
        makeHost(ctx, "drawGradient", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nDrawGradient,
                (jlong)a[0].asNumber(), (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                colorJint(a,5), colorJint(a,6), (jint)num(a,7));
            return Value::undefined();
        }));

    // ── Paths (Compose-style) ────────────────────────────────────────
    rt.global().setProperty(rt, "createPath",
        makeHost(ctx, "createPath", [](JsCtx* c, Runtime&, const Value*, size_t) {
            return Value((double)(long long)c->env->CallStaticLongMethod(sCanvasClass, sM.nPathCreate));
        }));
    rt.global().setProperty(rt, "destroyPath",
        makeHost(ctx, "destroyPath", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathDestroy, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathReset",
        makeHost(ctx, "pathReset", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathReset, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathMoveTo",
        makeHost(ctx, "pathMoveTo", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathMoveTo, (jlong)a[0].asNumber(), (jfloat)num(a,1), (jfloat)num(a,2));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathLineTo",
        makeHost(ctx, "pathLineTo", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathLineTo, (jlong)a[0].asNumber(), (jfloat)num(a,1), (jfloat)num(a,2));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathQuadTo",
        makeHost(ctx, "pathQuadTo", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathQuadTo, (jlong)a[0].asNumber(), (jfloat)num(a,1), (jfloat)num(a,2), (jfloat)num(a,3));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathCubicTo",
        makeHost(ctx, "pathCubicTo", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathCubicTo, (jlong)a[0].asNumber(),
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),(jfloat)num(a,5));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "pathClose",
        makeHost(ctx, "pathClose", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nPathClose, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    // drawPath(handle, path, color, strokeWidth, fill)
    rt.global().setProperty(rt, "drawPath",
        makeHost(ctx, "drawPath", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            c->env->CallStaticVoidMethod(sCanvasClass, sM.nDrawPath,
                (jlong)a[0].asNumber(), (jlong)a[1].asNumber(), colorJint(a,2), (jfloat)num(a,3), a[4].getBool());
            return Value::undefined();
        }));

    // ── RN runtime conveniences ──────────────────────────────────────
    // console.log / console.error -> logcat
    auto makeConsole = [ctx](const char* method, const char* level) {
        return makeHost(ctx, method, [level](JsCtx* c, Runtime& rt, const Value* a, size_t n) {
            std::string out;
            for (size_t i = 0; i < n; i++) {
                if (i) out += " ";
                if (a[i].isString()) out += a[i].getString(rt).utf8(rt);
                else if (a[i].isNumber()) out += std::to_string(a[i].getNumber());
                else if (a[i].isBool()) out += a[i].getBool() ? "true" : "false";
                else out += "[object]";
            }
            __android_log_print(ANDROID_LOG_DEBUG, "RNLog", "%s %s", level, out.c_str());
            return Value::undefined();
        });
    };
    rt.global().setProperty(rt, "console", Object(*ctx->runtime));
    rt.global().getPropertyAsObject(rt, "console").setProperty(rt, "log", makeConsole("console.log", "LOG"));
    rt.global().getPropertyAsObject(rt, "console").setProperty(rt, "error", makeConsole("console.error", "ERROR"));
    rt.global().getPropertyAsObject(rt, "console").setProperty(rt, "warn", makeConsole("console.warn", "WARN"));

    // timers: setTimeout(cb, ms) / setInterval(cb, ms) / clearTimeout(id)
    rt.global().setProperty(rt, "setTimeout",
        makeHost(ctx, "setTimeout", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            Function cb = a[0].getObject(rt).asFunction(rt);
            double ms = num(a, 1);
            return Value((double)rnTimer(c, std::move(cb), ms, false));
        }));
    rt.global().setProperty(rt, "setInterval",
        makeHost(ctx, "setInterval", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            Function cb = a[0].getObject(rt).asFunction(rt);
            double ms = num(a, 1);
            return Value((double)rnTimer(c, std::move(cb), ms, true));
        }));
    rt.global().setProperty(rt, "clearTimeout",
        makeHost(ctx, "clearTimeout", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            rnClearTimer(c, (uint64_t)a[0].asNumber());
            return Value::undefined();
        }));

    // createImageFromBytes(bytesArray) -> imageHandle (decode local bytes)
    rt.global().setProperty(rt, "createImageFromBytes",
        makeHost(ctx, "createImageFromBytes", [](JsCtx* c, Runtime& rt, const Value* a, size_t) {
            jbyteArray arr = bytesFromJs(c, rt, a[0]);
            if (!arr) return Value(0.0);
            jlong h = c->env->CallStaticLongMethod(sCanvasClass, sM.nImageCreateFromBytes, arr);
            c->env->DeleteLocalRef(arr);
            return Value((double)(long long)h);
        }));

    // ── Images ───────────────────────────────────────────────────────
    // drawImage(handle, img, x, y, w, h, alpha)
    rt.global().setProperty(rt, "drawImage",
        makeHost(ctx, "drawImage", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nDrawImage,
                (jlong)a[0].asNumber(), (jlong)a[1].asNumber(),
                (jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),(jfloat)num(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    // drawImageRounded(handle, img, x, y, w, h, radius)
    rt.global().setProperty(rt, "drawImageRounded",
        makeHost(ctx, "drawImageRounded", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nDrawImageRounded,
                (jlong)a[0].asNumber(), (jlong)a[1].asNumber(),
                (jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),(jfloat)num(a,5),(jfloat)num(a,6));
            return Value::undefined();
        }));
    // imageWidth(img), imageHeight(img)
    rt.global().setProperty(rt, "imageWidth",
        makeHost(ctx, "imageWidth", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            return Value(e->CallStaticIntMethod(sCanvasClass, sM.nImageGetWidth, (jlong)a[0].asNumber()));
        }));
    rt.global().setProperty(rt, "imageHeight",
        makeHost(ctx, "imageHeight", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            return Value(e->CallStaticIntMethod(sCanvasClass, sM.nImageGetHeight, (jlong)a[0].asNumber()));
        }));
    // destroyImage(img)
    rt.global().setProperty(rt, "destroyImage",
        makeHost(ctx, "destroyImage", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            e->CallStaticVoidMethod(sCanvasClass, sM.nImageDestroy, (jlong)a[0].asNumber());
            return Value::undefined();
        }));
    // loadImage(url, callback) — async. callback(imageHandle) on success,
    // callback(0) on failure. Downloads + decodes on a background thread.
    rt.global().setProperty(rt, "loadImage",
        makeHost(ctx, "loadImage", [ctx](JsCtx* c, Runtime& rt, const Value* a, size_t n) {
            if (n < 2 || !a[1].isObject()) return Value(0.0);
            std::string url = a[0].getString(rt).utf8(rt);
            Function cb = a[1].getObject(rt).asFunction(rt);
            uint64_t id = c->nextLoadId++;
            c->pendingLoads.emplace(id, std::move(cb));
            JNIEnv* e = c->env;
            jstring ju = e->NewStringUTF(url.c_str());
            e->CallStaticVoidMethod(sCanvasClass, sM.nFetchImageAsync,
                ju, reinterpret_cast<jlong>(c), (jlong)id);
            e->DeleteLocalRef(ju);
            return Value(0.0);
        }));

    // ── Yoga flexbox layout host functions ──────────────────────────
    // JS side builds a Yoga node tree, calculates layout, and reads back
    // positions/sizes for Skia rendering. Text is pre-measured in JS.
    auto ygNode = [](const Value* a) { return reinterpret_cast<YGNodeRef>((long long)a[0].asNumber()); };
    auto ygStr = [](Runtime& rt, const Value* a) { return a[1].getString(rt).utf8(rt); };

    rt.global().setProperty(rt, "ygNewNode",
        makeHost(ctx, "ygNewNode", [](JsCtx*, Runtime&, const Value*, size_t) {
            return Value(static_cast<double>((long long)YGNodeNew()));
        }));
    rt.global().setProperty(rt, "ygFree",
        makeHost(ctx, "ygFree", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeFree(ygNode(a)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygFreeRecursive",
        makeHost(ctx, "ygFreeRecursive", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeFreeRecursive(ygNode(a)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygInsertChild",
        makeHost(ctx, "ygInsertChild", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeInsertChild(ygNode(a), ygNode(a + 1), (size_t)num(a, 2));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetWidth",
        makeHost(ctx, "ygSetWidth", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetWidth(ygNode(a), (float)num(a, 1)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetHeight",
        makeHost(ctx, "ygSetHeight", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetHeight(ygNode(a), (float)num(a, 1)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetWidthPercent",
        makeHost(ctx, "ygSetWidthPercent", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetWidthPercent(ygNode(a), (float)num(a, 1)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetFlexDirection",
        makeHost(ctx, "ygSetFlexDirection", [ygNode, ygStr](JsCtx*, Runtime& rt, const Value* a, size_t) {
            YGNodeStyleSetFlexDirection(ygNode(a), ygFlexDir(ygStr(rt, a)));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetJustifyContent",
        makeHost(ctx, "ygSetJustifyContent", [ygNode, ygStr](JsCtx*, Runtime& rt, const Value* a, size_t) {
            YGNodeStyleSetJustifyContent(ygNode(a), ygJustify(ygStr(rt, a)));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetAlignItems",
        makeHost(ctx, "ygSetAlignItems", [ygNode, ygStr](JsCtx*, Runtime& rt, const Value* a, size_t) {
            YGNodeStyleSetAlignItems(ygNode(a), ygAlign(ygStr(rt, a)));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetAlignSelf",
        makeHost(ctx, "ygSetAlignSelf", [ygNode, ygStr](JsCtx*, Runtime& rt, const Value* a, size_t) {
            YGNodeStyleSetAlignSelf(ygNode(a), ygAlign(ygStr(rt, a)));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetGap",
        makeHost(ctx, "ygSetGap", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetGap(ygNode(a), YGGutterAll, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetPadding",
        makeHost(ctx, "ygSetPadding", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetPadding(ygNode(a), YGEdgeAll, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetPaddingTop",
        makeHost(ctx, "ygSetPaddingTop", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetPadding(ygNode(a), YGEdgeTop, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetPaddingBottom",
        makeHost(ctx, "ygSetPaddingBottom", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetPadding(ygNode(a), YGEdgeBottom, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetPaddingLeft",
        makeHost(ctx, "ygSetPaddingLeft", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetPadding(ygNode(a), YGEdgeLeft, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetPaddingRight",
        makeHost(ctx, "ygSetPaddingRight", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetPadding(ygNode(a), YGEdgeRight, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetMargin",
        makeHost(ctx, "ygSetMargin", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetMargin(ygNode(a), YGEdgeAll, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetMarginTop",
        makeHost(ctx, "ygSetMarginTop", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetMargin(ygNode(a), YGEdgeTop, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetMarginBottom",
        makeHost(ctx, "ygSetMarginBottom", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetMargin(ygNode(a), YGEdgeBottom, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetMarginLeft",
        makeHost(ctx, "ygSetMarginLeft", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetMargin(ygNode(a), YGEdgeLeft, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetMarginRight",
        makeHost(ctx, "ygSetMarginRight", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetMargin(ygNode(a), YGEdgeRight, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetBorder",
        makeHost(ctx, "ygSetBorder", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetBorder(ygNode(a), YGEdgeAll, (float)num(a, 1));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetFlexGrow",
        makeHost(ctx, "ygSetFlexGrow", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetFlexGrow(ygNode(a), (float)num(a, 1)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetFlexShrink",
        makeHost(ctx, "ygSetFlexShrink", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeStyleSetFlexShrink(ygNode(a), (float)num(a, 1)); return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygSetDisplay",
        makeHost(ctx, "ygSetDisplay", [ygNode, ygStr](JsCtx*, Runtime& rt, const Value* a, size_t) {
            YGNodeStyleSetDisplay(ygNode(a), ygDisplay(ygStr(rt, a)));
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygCalculateLayout",
        makeHost(ctx, "ygCalculateLayout", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            YGNodeCalculateLayout(ygNode(a), (float)num(a, 1), (float)num(a, 2), YGDirectionLTR);
            return Value::undefined();
        }));
    rt.global().setProperty(rt, "ygGetLeft",
        makeHost(ctx, "ygGetLeft", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            return Value(YGNodeLayoutGetLeft(ygNode(a)));
        }));
    rt.global().setProperty(rt, "ygGetTop",
        makeHost(ctx, "ygGetTop", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            return Value(YGNodeLayoutGetTop(ygNode(a)));
        }));
    rt.global().setProperty(rt, "ygGetWidth",
        makeHost(ctx, "ygGetWidth", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            return Value(YGNodeLayoutGetWidth(ygNode(a)));
        }));
    rt.global().setProperty(rt, "ygGetHeight",
        makeHost(ctx, "ygGetHeight", [ygNode](JsCtx*, Runtime&, const Value* a, size_t) {
            return Value(YGNodeLayoutGetHeight(ygNode(a)));
        }));

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_skiajni_JsCanvas_nDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<JsCtx*>(h);
}

// Called by Java (on the Hermes thread) once a URL image has been decoded.
// Delivers the Skia image handle to the JS callback stored by loadImage().
// handle == 0 means the fetch/decode failed.
JNIEXPORT void JNICALL
Java_com_example_skiajni_JsCanvas_nDeliverImage(JNIEnv* env, jclass, jlong ctxHandle, jlong id, jlong imgHandle) {
    auto* ctx = reinterpret_cast<JsCtx*>(ctxHandle);
    if (!ctx || !ctx->runtime) { if (imgHandle) { /* leak-safe */ } return; }
    ctx->env = env;
    auto it = ctx->pendingLoads.find((uint64_t)id);
    if (it == ctx->pendingLoads.end()) return;
    Function cb = std::move(it->second);
    ctx->pendingLoads.erase(it);
    try {
        auto& rt = *ctx->runtime;
        cb.call(rt, { Value((double)(long long)imgHandle) });
    } catch (const JSError&) {
        // JS callback threw; ignore so the native call completes.
    }
}

// Called by Java each frame to advance the timer clock and fire due timers.
JNIEXPORT void JNICALL
Java_com_example_skiajni_JsCanvas_nPumpTimers(JNIEnv* env, jclass, jlong ctxHandle, jlong nowMs) {
    auto* ctx = reinterpret_cast<JsCtx*>(ctxHandle);
    if (!ctx || !ctx->runtime) return;
    ctx->env = env;
    rnPumpTimers(ctx, (double)nowMs);
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

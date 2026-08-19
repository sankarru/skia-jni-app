#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <functional>

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
            jint color = (jint)a[1].asNumber();
            LOG("clear: handle=%lld color=%d", (long long)h, color);
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
    rt.global().setProperty(rt, "fillRoundRect",
        makeHost(ctx, "fillRoundRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.fillRoundRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jfloat)num(a,5),(jfloat)num(a,6),(jint)num(a,7));
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
    rt.global().setProperty(rt, "drawRoundRect",
        makeHost(ctx, "drawRoundRect", [](JsCtx* c, Runtime&, const Value* a, size_t) {
            JNIEnv* e = c->env;
            jlong h = (jlong)a[0].asNumber();
            e->CallStaticVoidMethod(sCanvasClass, sM.drawRoundRect, h,
                (jfloat)num(a,1),(jfloat)num(a,2),(jfloat)num(a,3),(jfloat)num(a,4),
                (jfloat)num(a,5),(jfloat)num(a,6),(jint)num(a,7),(jfloat)num(a,8));
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

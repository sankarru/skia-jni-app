package com.example.skiajni;

/**
 * Runs JavaScript via the Hermes engine and lets it draw through Skia.
 *
 * Usage:
 *   JsCanvas js = new JsCanvas();
 *   js.eval("clear(h, 0xFFFAFAFA); fillCircle(h, 200, 300, 100, 0xFFFF0000);");
 *   js.destroy();
 *
 * In JS, `h` is the current Skia canvas handle. Global functions are
 * available: clear, fillRect, fillCircle, drawRect, drawCircle, drawLine, drawText.
 */
public class JsCanvas implements AutoCloseable {

    static { System.loadLibrary("skia_jni"); }

    private long handle;

    /** Return the native context handle (needed for nPumpTimers etc.). */
    public long getHandle() { return handle; }

    public JsCanvas(int width, int height) {
        this.handle = nCreate(width, height);
    }

    /** Evaluate JS source; returns the script result or error message. */
    public String eval(String js) {
        return nEval(handle, js);
    }

    /** Expose a SkiaCanvas handle as global `_handle` in the JS runtime. */
    public void setCanvas(SkiaCanvas canvas) {
        nEval(handle, "var _handle = " + canvas.getNativeHandle() + ";");
    }

    /** Draw a JS-scripted scene onto a SkiaCanvas. `js` defines global
     *  drawing calls using the canvas handle in global var `h`. */
    public String drawScript(String js, SkiaCanvas canvas) {
        // Expose the canvas handle as `h` and run the script.
        String wrapped = "var h = " + canvas.getNativeHandle() + ";\n" + js;
        return nEval(handle, wrapped);
    }

    @Override
    public void close() {
        if (handle != 0) { nDestroy(handle); handle = 0; }
    }

    private static native long nCreate(int w, int h);
    private static native void nDestroy(long h);
    private static native String nEval(long h, String js);

    /** Deliver a decoded image handle to a pending JS loadImage() callback.
     *  Called by SkiaCanvas.nFetchImageAsync on the main thread. */
    static native void nDeliverImage(long ctx, long id, long imgHandle);

    /** Advance the JS timer clock and fire any due setTimeout/setInterval callbacks.
     *  Should be called each frame from the Choreographer render loop. */
    static native void nPumpTimers(long ctx, long nowMs);
}

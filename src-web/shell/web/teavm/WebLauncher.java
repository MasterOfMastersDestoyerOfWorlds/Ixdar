package shell.web.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.webgl.WebGLRenderingContext;

public final class WebLauncher {

    private WebLauncher() {
    }

    public static void main(String[] args) {
        HTMLDocument document = Window.current().getDocument();
        HTMLCanvasElement canvas = (HTMLCanvasElement) document.createElement("canvas");
        canvas.setId("ixdar-canvas");
        canvas.setWidth(800);
        canvas.setHeight(600);
        HTMLElement body = document.getBody();
        body.appendChild(canvas);

        WebGLRenderingContext gl = (WebGLRenderingContext) canvas.getContext("webgl");
        if (gl == null) {
            gl = (WebGLRenderingContext) canvas.getContext("experimental-webgl");
        }
        if (gl == null) {
            log("WebGL is not available in this browser.");
            return;
        }

        final WebGLRenderingContext glCtx = gl;
        // Simple RAF loop clearing the screen; placeholder until full port
        Window.requestAnimationFrame(timestamp -> {
            updateAndRender(glCtx);
        });
    }

    private static void updateAndRender(WebGLRenderingContext gl) {
        gl.viewport(0, 0, gl.getDrawingBufferWidth(), gl.getDrawingBufferHeight());
        gl.clearColor(0.07f, 0.07f, 0.07f, 1.0f);
        gl.clear(WebGLRenderingContext.COLOR_BUFFER_BIT);
        // Schedule next frame
        Window.requestAnimationFrame(ts -> updateAndRender(gl));
    }

    @JSBody(params = { "msg" }, script = "console.log(msg);")
    private static native void log(String msg);
}


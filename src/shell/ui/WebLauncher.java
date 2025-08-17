package shell.ui;

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;

import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.web.WebGL;
import shell.platform.gl.web.WebPlatform;
import shell.render.Clock;
import shell.ui.main.Main;

public final class WebLauncher {

    private WebLauncher() {
    }

    public static float startTime;
    private static boolean initialized;
    private static HTMLDocument document = Window.current().getDocument();

    public static void main(String[] args) {
        startTime = Clock.time();
        HTMLCanvasElement canvas = (HTMLCanvasElement) document.getElementById("ixdar-canvas");
        if (canvas == null) {
            canvas = (HTMLCanvasElement) document.createElement("canvas");
            canvas.setId("ixdar-canvas");
            canvas.setWidth(800);
            canvas.setHeight(600);
            HTMLElement body = document.getBody();
            body.appendChild(canvas);
        }

        Platforms.init(new WebPlatform(), new WebGL(canvas));
        Window.requestAnimationFrame(ts -> tick());
    }

    private static void initAppIfNeeded() {
        if (initialized)
            return;
        try {
            // Initialize app with default dataset 'djbouti'
            Main.main = new Main("djbouti");
            // Ensure Canvas3D static states mirror desktop
            new Canvas3D().initGL();
            initialized = true;
        } catch (Exception e) {
            log("Init error: " + e.getMessage());
        }
    }

    private static void tick() {
        initAppIfNeeded();
        GL gl = Platforms.gl();
        HTMLCanvasElement canvas = (HTMLCanvasElement) Window.current().getDocument().getElementById("ixdar-canvas");
        int w = canvas.getClientWidth();
        int h = canvas.getClientHeight();
        if (w <= 0)
            w = 800;
        if (h <= 0)
            h = 600;
        if (canvas.getWidth() != w)
            canvas.setWidth(w);
        if (canvas.getHeight() != h)
            canvas.setHeight(h);
        gl.viewport(0, 0, w, h);
        gl.clearColor(0.02f, 0.02f, 0.02f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT());
        try {
            // Drive the existing rendering path
            Canvas3D.canvas.paintGL();
        } catch (Throwable t) {
            log("Render error: " + t.getMessage());
        }
        Window.requestAnimationFrame(ts -> tick());
    }

    @JSBody(params = { "msg" }, script = "console.log(msg);")
    private static native void log(String msg);

    public static void setTitle(String string) {
        document.setTitle(string);
    }
}

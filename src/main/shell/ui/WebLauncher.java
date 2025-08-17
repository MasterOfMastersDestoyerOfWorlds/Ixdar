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

    public static void main(String[] args) {
        startTime = Clock.time();
        HTMLDocument document = Window.current().getDocument();
        HTMLCanvasElement canvas = (HTMLCanvasElement) document.getElementById("ixdar-canvas");
        if (canvas == null) {
            canvas = (HTMLCanvasElement) document.createElement("canvas");
            canvas.setId("ixdar-canvas");
            canvas.setWidth(800);
            canvas.setHeight(600);
            HTMLElement body = document.getBody();
            body.appendChild(canvas);
        }
        log("WebLauncher is running");

        Platforms.init(new WebPlatform(), new WebGL(canvas));
        // Provide default buffers implementation for web
        shell.platform.Platforms.setBuffers(new shell.platform.buffers.DefaultBuffers());
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
            // Show fallback triangle if init fails
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
            drawFallbackTriangle(gl);
        }
        Window.requestAnimationFrame(ts -> tick());
    }

    @JSBody(params = { "msg" }, script = "console.log(msg);")
    private static native void log(String msg);

    public static void setTitle(String string) {
        Window.current().getDocument().setTitle(string);
    }

    private static void drawFallbackTriangle(GL gl) {
        String vs = "precision mediump float;\n"
                + "attribute vec2 a_pos;\n"
                + "void main(){\n"
                + "  gl_Position=vec4(a_pos,0.0,1.0);\n"
                + "}";
        String fs = "precision mediump float;\n"
                + "void main(){\n"
                + "  gl_FragColor=vec4(1.0,0.4,0.2,1.0);\n"
                + "}";

        int vsh = gl.createShader(gl.VERTEX_SHADER());
        gl.shaderSource(vsh, vs);
        gl.compileShader(vsh);
        int fsh = gl.createShader(gl.FRAGMENT_SHADER());
        gl.shaderSource(fsh, fs);
        gl.compileShader(fsh);
        int prog = gl.createProgram();
        gl.attachShader(prog, vsh);
        gl.attachShader(prog, fsh);
        gl.linkProgram(prog);
        gl.useProgram(prog);

        int buf = gl.genBuffer();
        gl.bindArrayBuffer(buf);
        float[] verts = new float[] { 0f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f };
        gl.bufferDataArray(verts, gl.STATIC_DRAW());
        int loc = gl.getAttribLocation(prog, "a_pos");
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, 2, gl.FLOAT(), false, 2 * 4, 0);
        gl.drawArrays(gl.TRIANGLES(), 0, 3);
    }
}

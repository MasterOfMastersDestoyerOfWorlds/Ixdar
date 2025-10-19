package shell.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;

import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.web.WebGL;
import shell.platform.gl.web.WebPlatform;
import shell.render.Clock;
import shell.ui.scenes.CanvasScene;

public final class WebLauncher {

    private WebLauncher() {
    }

    public static float startTime;
    public static boolean broken = false;

    private static String DEFAULT_CANVAS_NAME = "ixdar-canvas";
    private static Canvas3D[] canvas3dScenes;
    private static HTMLCanvasElement[] canvasElements;
    private static WebPlatform[] webPlatforms;
    private static WebGL[] webGLs;

    public static void main(String[] args)
            throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException,
            NoSuchMethodException, SecurityException, UnsupportedEncodingException, IOException {
        startTime = Clock.time();
        System.setProperty("joml.format", "false");
        HTMLDocument document = Window.current().getDocument();
        canvasElements = new HTMLCanvasElement[args.length];
        canvas3dScenes = new Canvas3D[args.length];
        webPlatforms = new WebPlatform[args.length];
        webGLs = new WebGL[args.length];
        for (int i = 0; i < canvasElements.length; i++) {
            String canvasId = args[i];
            HTMLCanvasElement canvas = (HTMLCanvasElement) document.getElementById(canvasId);
            webPlatforms[i] = new WebPlatform(canvas, canvasId);
            webGLs[i] = new WebGL(canvas);
            Platforms.init(webPlatforms[i], webGLs[i]);
            webPlatforms[i].log("WebLauncher is running for " + canvasId);

            int w = canvas.getClientWidth();
            int h = canvas.getClientHeight();
            if (w <= 0)
                w = 800;
            if (h <= 0)
                h = 600;
            canvas.setWidth(w);
            canvas.setHeight(h);
            Platforms.get().setFrameBufferSize(w, h);

            Supplier<? extends Canvas3D> cs = CanvasScene.MAP.get(canvasId);
            if (cs == null) {
                Platforms.get().log("Canvas3D not found for " + canvasId);
            }
            Canvas3D canvas3d = (Canvas3D) cs.get();

            canvas3d.initGL();
            canvasElements[i] = canvas;
            canvas3dScenes[i] = canvas3d;
            final int j = i;
            Window.requestAnimationFrame(ts -> tick(j));
        }
    }

    @org.teavm.jso.JSBody(params = {
            "el" }, script = "if(!el) return false; var style=getComputedStyle(el); if(style.display==='none'||style.visibility==='hidden'||parseFloat(style.opacity)===0) return false; var rect=el.getBoundingClientRect(); var vw=window.innerWidth||document.documentElement.clientWidth; var vh=window.innerHeight||document.documentElement.clientHeight; return rect.bottom>0 && rect.right>0 && rect.top<vh && rect.left<vw;")
    private static native boolean isElementVisible(HTMLCanvasElement el);

    private static void tick(int i) {
        Canvas3D canvas3d = canvas3dScenes[i];
        HTMLCanvasElement canvas = canvasElements[i];
        Platforms.init(webPlatforms[i], webGLs[i]);
        GL gl = Platforms.gl();
        if (!broken) {
            if (canvas == null)
                return;
            // Skip expensive updates and painting if the canvas is not visible
            if (!isElementVisible(canvas)) {
                Window.requestAnimationFrame(ts -> tick(i));
                return;
            }
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
            Platforms.get().setFrameBufferSize(w, h);
            canvas3d.paintGL();
        } else {
            if (gl != null) {
                // Ensure viewport per-canvas before drawing fallback
                gl.viewport(0, 0, Platforms.get().getWindowWidth(), Platforms.get().getWindowHeight());
                drawFallbackTriangle(gl, i);
            }
        }
        Window.requestAnimationFrame(ts -> tick(i));
    }

    public static void setTitle(String string) {
        Window.current().getDocument().setTitle(string);
    }

    private static void drawFallbackTriangle(GL gl, int i) {
        String vs = "precision mediump float;\n"
                + "attribute vec2 a_pos;\n"
                + "void main(){\n"
                + "  gl_Position=vec4(a_pos,0.0,1.0);\n"
                + "}";
        String fs = "precision mediump float;\n"
                + "void main(){\n"
                + "  gl_FragColor=vec4(1.0,0." + 2 * i + ",0.2,1.0);\n"
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
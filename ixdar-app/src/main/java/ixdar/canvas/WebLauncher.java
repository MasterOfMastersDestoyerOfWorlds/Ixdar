package ixdar.canvas;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import org.teavm.jso.browser.Window;

import org.teavm.jso.JSBody;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

import ixdar.annotations.scene.SceneDrawable;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.web.WebGL;
import ixdar.platform.gl.web.WebPlatform;
import ixdar.scenes.mesh.MeshNodeViewerScene;

public final class WebLauncher {
    public static final String PRECISION_MEDIUMP_FLOAT_N = "precision mediump float;\n";
    public static final String VOID_MAIN_N = "void main(){\n";
    public static final String STR = "}";
    public static final int NUM_800 = 800;
    public static final int NUM_400 = 400;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    public static float startTime;
    public static boolean broken = false;

    private static String DEFAULT_CANVAS_NAME = "ixdar-canvas";
    private static Canvas3D[] canvas3dScenes;
    private static HTMLCanvasElement[] canvasElements;
    private static WebPlatform[] webPlatforms;
    private static WebGL[] webGLs;

    private WebLauncher() {
    }

    /**
     * TeaVM/browser entry point: for each canvas id in {@code args}, locate the
     * matching {@code <canvas>} element, build its {@link WebPlatform}/{@link WebGL},
     * pick a {@link Canvas3D} (OBJ viewer, DSL viewer, or registered scene), and
     * schedule the per-canvas animation tick.
     *
     * @param args canvas element ids to bind, one per scene
     * @throws InstantiationException if scene reflection fails
     * @throws IllegalAccessException if scene reflection fails
     * @throws IllegalArgumentException if scene reflection fails
     * @throws InvocationTargetException if scene reflection fails
     * @throws NoSuchMethodException if scene reflection fails
     * @throws SecurityException if scene reflection fails
     * @throws UnsupportedEncodingException propagated from scene initialization
     * @throws IOException propagated from scene initialization
     */
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
            if (w < 1) {
                w = NUM_800;
            }
            if (h < 1) {
                h = NUM_400;
            }
            canvas.setWidth(w);
            canvas.setHeight(h);
            Platforms.get().setFrameBufferSize(w, h);

            Canvas3D canvas3d;
            String dataObj = canvas.getAttribute("data-obj");
            String dataDsl = canvas.getAttribute("data-dsl");
            if (dataObj != null && !dataObj.isEmpty()) {
                // Canvas specifies an OBJ file — create an OBJ-only viewer
                Platforms.get().log("OBJ viewer: " + dataObj);
                canvas3d = MeshNodeViewerScene.forObj(dataObj);
            } else if (dataDsl != null && !dataDsl.isEmpty()) {
                // Canvas specifies a DSL file — create a parameterized mesh viewer
                String dataNode = canvas.getAttribute("data-node");
                String dataPort = canvas.getAttribute("data-port");
                if (dataNode == null || dataNode.isEmpty()) dataNode = "output";
                if (dataPort == null || dataPort.isEmpty()) dataPort = "geometry";
                Platforms.get().log("DSL viewer: " + dataDsl + " node=" + dataNode + " port=" + dataPort);
                canvas3d = new MeshNodeViewerScene(dataDsl, dataNode, dataPort);
            } else {
                Supplier<? extends SceneDrawable> cs = CanvasSceneMap.MAP.get(canvasId);
                if (cs == null) {
                    Platforms.get().log("Canvas3D not found for " + canvasId);
                }
                canvas3d = (Canvas3D) cs.get();
            }

            canvas3d.initGL();
            canvasElements[i] = canvas;
            canvas3dScenes[i] = canvas3d;
            final int j = i;
            Window.requestAnimationFrame(ts -> tick(j));
        }
    }

    @JSBody(params = {
            "el" }, script = "if(!el) return false; var style=getComputedStyle(el); if(style.display==='none'||style.visibility==='hidden'||parseFloat(style.opacity)===0) return false; var rect=el.getBoundingClientRect(); var vw=window.innerWidth||document.documentElement.clientWidth; var vh=window.innerHeight||document.documentElement.clientHeight; var m=600; return !(rect.bottom<=-m || rect.top>=vh+m || rect.right<=-m || rect.left>=vw+m);")
    private static native boolean isElementVisible(HTMLCanvasElement el);

    private static void tick(int i) {
        Canvas3D canvas3d = canvas3dScenes[i];
        HTMLCanvasElement canvas = canvasElements[i];
        Platforms.init(webPlatforms[i], webGLs[i]);
        GL gl = Platforms.gl();
        if (!webPlatforms[i].loadedShaders()) {
            Window.requestAnimationFrame(ts -> tick(i));
            return;
        }
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
            if (w < 1) {
                w = NUM_800;
            }
            if (h < 1) {
                h = NUM_400;
            }
            canvas.setWidth(w);
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

    /**
     * Set the host browser document title.
     *
     * @param string new value for {@code document.title}
     */
    public static void setTitle(String string) {
        Window.current().getDocument().setTitle(string);
    }

    private static void drawFallbackTriangle(GL gl, int i) {
        String vs = PRECISION_MEDIUMP_FLOAT_N
                + "attribute vec2 a_pos;\n"
                + VOID_MAIN_N
                + "  gl_Position=vec4(a_pos,0.0,1.0);\n"
                + STR;
        String fs = PRECISION_MEDIUMP_FLOAT_N
                + VOID_MAIN_N
                + "  gl_FragColor=vec4(1.0,0." + 2 * i + ",0.2,1.0);\n"
                + STR;

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
        float[] verts = new float[] { NUM_0, NUM_0_5, -NUM_0_5, -NUM_0_5, NUM_0_5, -NUM_0_5 };
        gl.bufferDataArray(verts, gl.STATIC_DRAW());
        int loc = gl.getAttribLocation(prog, "a_pos");
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, 2, gl.FLOAT(), false, 2 * NUM_4, 0);
        gl.drawArrays(gl.TRIANGLES(), 0, NUM_3);
    }
}
package ixdar.graphics.render.sdf;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.parsing.glsl.GLSLParseText;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;

public abstract class ShaderDrawable {
    public static final float NUM_4 = 4f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_00001 = 0.00001f;
    public static final int NUM_9 = 9;
    public static final int NUM_7 = 7;

    private static final HashMap<Class<?>, Long> counters = new HashMap<>();

    public ShaderProgram shader;
    public float width;
    public float height;
    public Vector2f bottomLeft;
    public Vector2f bottomRight;
    public Vector2f topRight;
    public Vector2f topLeft;
    public Vector2f center;

    public float widthToHeightRatio;

    public float texHeight;

    public float texWidth;

    protected GL gl = Platforms.gl();
    protected Platform platform = Platforms.get();
    protected Camera camera;

    protected float drawX;
    protected float drawY;
    protected Color c = Color.PINK;
    protected boolean culled = false;

    protected final long drawingId;

    protected Vector2f uAxis;

    protected Vector2f vAxis;

    private ShaderProgram.Allocation allocation;
    private boolean geometryDirty = true;
    private boolean colorDirty = true;

    private final Map<Long, ShaderProgram.Allocation> allocationById = new HashMap<>();
    private final Map<Long, Quad> prevQuadById = new HashMap<>();

    /** Reusable buffer for geometry uploads — avoids per-draw ByteBuffer.allocateDirect(). */
    private IxBuffer geometryBuf;

    /**
     * Assign a unique per-class drawing id used for persistent VBO allocation
     * keying.
     */
    protected ShaderDrawable() {
        this.drawingId = nextId(getClass());
    }

    /**
     * Increment and return the next per-class drawing id counter.
     *
     * @param clazz subclass requesting a fresh id
     * @return monotonically increasing id, starting at 0 per class
     */
    protected static long nextId(Class<?> clazz) {
        long id = counters.computeIfAbsent(clazz, c -> 0L);
        counters.put(clazz, id + 1);
        return id++;
    }

    /**
     * The unique id used to key this drawable's persistent VBO allocation.
     *
     * @return drawing id assigned at construction
     */
    public long getDrawingId() {
        return drawingId;
    }

    /**
     * Bind shader, recompute the quad, derive width/height/center/uv axes,
     * push uniforms, and (re)upload geometry into the persistent VBO slice
     * if the quad or color changed.
     *
     * @param camera camera providing transform and z-index
     */
    public void setup(Camera camera) {
        this.camera = camera;
        shader.use();
        shader.begin();
        calculateQuad();

        if (culled) {
            return;
        }

        width = bottomLeft.distance(bottomRight);
        height = bottomLeft.distance(topLeft);
        widthToHeightRatio = width / height;
        texWidth = widthToHeightRatio;
        texHeight = 1;
        center = new Vector2f(bottomLeft)
                .add(bottomRight)
                .add(topRight)
                .add(topLeft)
                .div(NUM_4);

        shader.setFloat("widthToHeightRatio", widthToHeightRatio);
        setUniforms();

        ShaderProgram.Allocation alloc = ensureAllocation(drawingId);
        if (isGeometryDirty(drawingId) || alloc.isDirty() || colorDirty) {
            uploadGeometry(alloc);
            colorDirty = false;

        } else {

            ShaderProgram.Allocation a = allocationById.get(drawingId);
            if (a == null || a.isDirty() || geometryDirty || colorDirty) {
                uploadGeometry(alloc);
                geometryDirty = false;
                colorDirty = false;
            }
        }
    }

    /**
     * End the current shader pass and advance the camera's near z-index.
     *
     * @param c camera whose z-index should be incremented
     */
    public void cleanup(Camera c) {
        shader.end();
        c.incZIndex();
    }

    /**
     * End the current shader pass and decrement the camera's far z-index
     * (used when rendering background-layer drawables).
     *
     * @param c camera whose far z-index should be decremented
     */
    public void cleanupFar(Camera c) {
        shader.end();
        c.decFarZIndex();
    }

    /**
     * Push shader-specific uniforms before the draw call. Subclasses must
     * override.
     *
     * @throws UnsupportedOperationException if a subclass does not override
     */
    protected void setUniforms() {
        throw new UnsupportedOperationException("Unimplemented method");
    }

    /**
     * Snapshot the shader's current uniform values as parseable GLSL text
     * (used by the GLSL editor / inspector UI).
     *
     * @return map keyed by uniform name with rendered text and value tuples
     */
    public Map<String, GLSLParseText> getUniformMap() {
        Map<String, GLSLParseText> map = new HashMap<>();
        Map<String, Object> uniformMap = shader.uniformMap;
        for (String key : uniformMap.keySet()) {
            Object value = uniformMap.get(key);
            if (value instanceof Float) {
                Float f = (Float) value;
                GLSLParseText.put(map, key, f);
            } else if (value instanceof Boolean) {
                Boolean b = (Boolean) value;

                map.put(key, new GLSLParseText(b ? "tru" : "false", ixdar.graphics.render.color.Color.GLSL_BOOLEAN,
                        new Vector4f(b ? NUM_1 : NUM_0, NUM_0, NUM_0, NUM_0), 1, key));
            } else if (value instanceof Vector2f) {
                Vector2f vec2 = (Vector2f) value;
                GLSLParseText.put(map, key, vec2.x, vec2.y);
            } else if (value instanceof Vector3f) {
                Vector3f vec3 = (Vector3f) value;
                GLSLParseText.put(map, key, vec3.x, vec3.y, vec3.z);
            } else if (value instanceof Vector4f) {
                Vector4f vec4 = (Vector4f) value;
                GLSLParseText.put(map, key, vec4.x, vec4.y, vec4.z, vec4.w);
            } else if (value instanceof FloatBuffer) {

            } else if (value instanceof Matrix4f) {

            } else if (value instanceof Texture) {
                Texture texture = (Texture) value;
                map.put(key, new GLSLParseText(texture.toString(), key));
            }

        }
        return map;
    }

    /**
     * Cache rectangle and color and submit a draw.
     *
     * @param drawX bottom-left x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color (ignored when {@code null})
     * @param camera camera providing transform and z-index
     */
    public void draw(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        if (c != null) {
            this.c = c;
        }
        this.drawX = drawX;
        this.drawY = drawY;
        this.width = width;
        this.height = height;
        draw(camera);
    }

    /**
     * Submit one draw: run setup, queue the quad's vertex range, and run
     * cleanup. Logs and returns early if the shader is missing.
     *
     * @param camera camera providing transform and z-index
     * @throws NullPointerException if the shader belongs to a different platform
     */
    public void draw(Camera camera) {
        if (shader == null) {
            platform.log("Shader is null");
            return;
        }
        if (shader.platformId != Platforms.gl().getPlatformID()) {
            platform.log("Shader is not for the current platform");
            throw new NullPointerException();
        }
        this.camera = camera;
        setup(camera);
        if (culled) {
            cleanup(camera);
            return;
        }
        ShaderProgram.Allocation alloc = allocationById.get(drawingId);
        if (alloc != null) {
            shader.queueDraw(alloc, Quad.VERTEX_COUNT);
        }
        cleanup(camera);
    }

    /**
     * Submit one draw using the allocation keyed by {@code id}, advancing the
     * camera's far z-index instead of its near one.
     *
     * @param camera camera providing transform and z-index
     * @param id allocation id to draw (typically a per-instance identifier)
     */
    public void drawFar(Camera camera, Long id) {
        this.camera = camera;
        setup(camera);
        ShaderProgram.Allocation alloc = allocationById.get(id);
        if (alloc != null) {
            shader.queueDraw(alloc, Quad.VERTEX_COUNT);
        }
        cleanupFar(camera);
    }

    /**
     * Cache the camera and rebuild the quad.
     *
     * @param camera2d camera to associate with the recomputed quad
     */
    public void calculateQuad(Camera camera2d) {
        this.camera = camera2d;
        calculateQuad();
    }

    /**
     * Default quad: axis-aligned rectangle of width x height anchored at
     * {@code (drawX, drawY)}, with corresponding u/v axes.
     */
    public void calculateQuad() {
        bottomLeft = new Vector2f(drawX, drawY);
        bottomRight = new Vector2f(bottomLeft).add(width, 0);
        topLeft = new Vector2f(bottomLeft).add(0, height);
        topRight = new Vector2f(bottomLeft).add(width, height);
        uAxis = new Vector2f(bottomRight).sub(bottomLeft);
        vAxis = new Vector2f(topLeft).sub(bottomLeft);
    }

    /**
     * Draw the quad centered on {@code (drawX, drawY)}.
     *
     * @param drawX center x in world coordinates
     * @param drawY center y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    /**
     * Draw the quad with its right edge at {@code drawX} (right-aligned).
     *
     * @param drawX right-edge x in world coordinates
     * @param drawY bottom-left y in world coordinates
     * @param width quad width in world units
     * @param height quad height in world units
     * @param c tint color
     * @param camera camera providing transform and z-index
     */
    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }

    private ShaderProgram.Allocation ensureAllocation(Long id) {
        if (id == null)
            return null;
        ShaderProgram.Allocation alloc = shader.ensureAllocation(id, Quad.VERTEX_COUNT);
        allocationById.put(id, alloc);
        return alloc;
    }

    private boolean isGeometryDirty(Long id) {
        if (id == null) {
            return geometryDirty;
        }
        Quad newQuad = new Quad(bottomLeft, bottomRight, topRight, topLeft, texWidth, texHeight, widthToHeightRatio);
        Quad old = prevQuadById.get(id);
        boolean changed = (old == null) || !sameQuad(old, newQuad);
        prevQuadById.put(id, newQuad);
        return changed;
    }

    /**
     * Return the most recently uploaded quad for this drawable's id (may be
     * {@code null} if nothing has been drawn yet).
     *
     * @return cached quad snapshot or {@code null}
     */
    public Quad getQuad() {
        return prevQuadById.get(drawingId);
    }

    private static boolean sameQuad(Quad a, Quad b) {
        float eps = NUM_0_00001;
        return a.bottomLeft.distance(b.bottomLeft) <= eps && a.bottomRight.distance(b.bottomRight) <= eps
                && a.topRight.distance(b.topRight) <= eps
                && a.topLeft.distance(b.topLeft) <= eps;
    }

    private void uploadGeometry(ShaderProgram.Allocation target) {

        int stride = shader.getStrideFloats();
        if (stride <= 0)
            stride = NUM_9;
        int floatsNeeded = stride * Quad.VERTEX_COUNT;
        if (geometryBuf == null || geometryBuf.capacity() < floatsNeeded) {
            geometryBuf = platform.allocateFloats(floatsNeeded);
        } else {
            geometryBuf.clear();
        }
        IxBuffer buf = geometryBuf;
        Vector4f color = c.toVector4f();
        shader.uniformMap.put("vertexColor", color);
        float r = color.x, g = color.y, b = color.z, a = color.w;
        float z = camera != null ? camera.getZIndex() : NUM_0;

        if (stride == NUM_9) {

            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a).put(NUM_0).put(NUM_0);
            buf.put(topLeft.x).put(topLeft.y).put(z).put(r).put(g).put(b).put(a).put(NUM_0).put(NUM_1);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a).put(NUM_1).put(NUM_1);

            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a).put(NUM_0).put(NUM_0);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a).put(NUM_1).put(NUM_1);
            buf.put(bottomRight.x).put(bottomRight.y).put(z).put(r).put(g).put(b).put(a).put(NUM_1).put(NUM_0);
        } else if (stride == NUM_7) {

            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a);
            buf.put(topLeft.x).put(topLeft.y).put(z).put(r).put(g).put(b).put(a);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a);

            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a);
            buf.put(bottomRight.x).put(bottomRight.y).put(z).put(r).put(g).put(b).put(a);
        }

        buf.flip();
        shader.uploadAllocation(target, buf, Quad.VERTEX_COUNT);
    }

    /**
     * Project a world-space point onto the quad's normalized [0,1] uv space.
     *
     * @param p world-space point
     * @return (u, v) in [0,1] when {@code p} lies on the quad
     */
    public Vector2f toTextureSpace(Vector2f p) {
        if (uAxis == null) {
            uAxis = new Vector2f(bottomRight).sub(bottomLeft);
            vAxis = new Vector2f(topLeft).sub(bottomLeft);
        }
        Vector2f rel = new Vector2f(p).sub(bottomLeft);
        float u = rel.dot(uAxis) / uAxis.dot(uAxis);
        float v = rel.dot(vAxis) / vAxis.dot(vAxis);
        return new Vector2f(u, v);
    }

    /**
     * Project a world-space point into texture space scaled by
     * {@code (texWidth, texHeight)} (i.e. preserves the quad's aspect).
     *
     * @param p world-space point
     * @return scaled (u, v) coordinates
     */
    public Vector2f toScaledTextureSpace(Vector2f p) {
        if (uAxis == null) {
            uAxis = new Vector2f(bottomRight).sub(bottomLeft);
            vAxis = new Vector2f(topLeft).sub(bottomLeft);
            texWidth = uAxis.length();
            texHeight = vAxis.length();
        }
        Vector2f rel = new Vector2f(p).sub(bottomLeft);
        float u = rel.dot(uAxis) / uAxis.dot(uAxis);
        float v = rel.dot(vAxis) / vAxis.dot(vAxis);
        return new Vector2f(u * texWidth, v * texHeight);
    }

    /**
     * The shader program currently driving this drawable.
     *
     * @return active shader
     */
    public ShaderProgram getShader() {
        return shader;
    }

    /**
     * The quad's u-axis (bottomRight - bottomLeft) in world coordinates.
     *
     * @return horizontal edge vector of the last computed quad
     */
    public Vector2f getUAxis() {
        return uAxis;
    }

    /**
     * The quad's v-axis (topLeft - bottomLeft) in world coordinates.
     *
     * @return vertical edge vector of the last computed quad
     */
    public Vector2f getVAxis() {
        return vAxis;
    }

    public static final class Quad {
        public final static int VERTEX_COUNT = 6;
        public final Vector2f bottomLeft, bottomRight, topRight, topLeft;
        public float widthToHeightRatio;
        public float texWidth;
        public float texHeight;

        Quad(Vector2f bl, Vector2f br, Vector2f tr, Vector2f tl, float texWidth, float texHeight,
                float widthToHeightRatio) {
            this.bottomLeft = new Vector2f(bl);
            this.bottomRight = new Vector2f(br);
            this.topRight = new Vector2f(tr);
            this.topLeft = new Vector2f(tl);
            this.texWidth = texWidth;
            this.texHeight = texHeight;
            this.widthToHeightRatio = widthToHeightRatio;
        }
    }

}

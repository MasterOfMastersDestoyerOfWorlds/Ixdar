package shell.render.sdf;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import shell.cameras.Camera;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.IxBuffer;
import shell.platform.gl.Platform;
import shell.render.Texture;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.ui.code.ParseText;

public abstract class ShaderDrawable {

    public ShaderProgram shader;

    public GL gl = Platforms.gl();
    public Platform platform = Platforms.get();
    public Camera camera;

    public float drawX;
    public float drawY;
    public float width;
    public float height;
    public Vector2f bottomLeft;
    public Vector2f bottomRight;
    public Vector2f topRight;
    public Vector2f topLeft;
    public Vector2f center;
    public Color c = Color.PINK;

    // Persistent VBO allocation for this drawable's quad geometry
    private ShaderProgram.Allocation allocation;
    private boolean geometryDirty = true;
    private boolean colorDirty = true;

    private final Map<Long, Object> ownerKeyById = new HashMap<>();
    private final Map<Long, ShaderProgram.Allocation> allocationById = new HashMap<>();
    private final Map<Long, Quad> prevQuadById = new HashMap<>();
    
    private static final HashMap<Class<?>, Long> counters = new HashMap<>();

    protected static long nextId(Class<?> clazz) {
        long id = counters.computeIfAbsent(clazz, c -> 0L);
        return id++;
    }

    protected final long drawingId;

    protected ShaderDrawable() {
        this.drawingId = nextId(getClass());
    }

    public long getDrawingId() {
        return drawingId;
    }

    private static final class Quad {
        final Vector2f bl, br, tr, tl;
        public final static int VERTEX_COUNT = 6;

        Quad(Vector2f bl, Vector2f br, Vector2f tr, Vector2f tl) {
            this.bl = new Vector2f(bl);
            this.br = new Vector2f(br);
            this.tr = new Vector2f(tr);
            this.tl = new Vector2f(tl);
        }
    }

    public void setup(Camera camera) {
        this.camera = camera;
        shader.use();
        shader.begin();
        calculateQuad();

        width = bottomLeft.distance(bottomRight);
        height = bottomLeft.distance(topLeft);

        center = new Vector2f(bottomLeft)
                .add(bottomRight)
                .add(topRight)
                .add(topLeft)
                .div(4f);

        setUniforms();

        // Provide default varying-like values for shader preview/evaluator
        // so expressions like textureCoord.x can be resolved by the parser.
        if (!shader.uniformMap.containsKey("textureCoord")) {
            shader.uniformMap.put("textureCoord", new Vector2f(0.5f, 0.5f));
        }

        // Prepare or update persistent VBO geometry for this quad
        ShaderProgram.Allocation alloc = ensureAllocation(drawingId);
        if (isGeometryDirty(drawingId) || alloc.isDirty() || colorDirty) {
            uploadGeometry(alloc);
            colorDirty = false;

        } else {
            if (allocation == null) {
                allocation = shader.ensureAllocation(this, Quad.VERTEX_COUNT);
                geometryDirty = true;
                colorDirty = true;
            }
            if (allocation.isDirty() || geometryDirty || colorDirty) {
                uploadGeometry(allocation);
                geometryDirty = false;
                colorDirty = false;
            }
        }
    }

    public void cleanup(Camera c) {
        shader.end();
        c.incZIndex();
    }

    public void cleanupFar(Camera c) {
        shader.end();
        c.decFarZIndex();
    }

    protected void setUniforms() {
        throw new UnsupportedOperationException("Unimplemented method");
    }

    public Map<String, ParseText> getUniformMap() {
        Map<String, ParseText> map = new HashMap<>();
        Map<String, Object> uniformMap = shader.uniformMap;
        for (String key : uniformMap.keySet()) {
            Object value = uniformMap.get(key);
            if (value instanceof Float) {
                Float f = (Float) value;
                ParseText.put(map, key, f);
            } else if (value instanceof Boolean) {
                Boolean b = (Boolean) value;
                // Provide both display text and numeric data (1/0) for evaluation
                map.put(key, new ParseText(b ? "tru" : "false", shell.render.color.Color.GLSL_BOOLEAN,
                        new Vector4f(b ? 1f : 0f, 0f, 0f, 0f), 1, key));
            } else if (value instanceof Vector2f) {
                Vector2f vec2 = (Vector2f) value;
                ParseText.put(map, key, vec2.x, vec2.y);
            } else if (value instanceof Vector3f) {
                Vector3f vec3 = (Vector3f) value;
                ParseText.put(map, key, vec3.x, vec3.y, vec3.z);
            } else if (value instanceof Vector4f) {
                Vector4f vec4 = (Vector4f) value;
                ParseText.put(map, key, vec4.x, vec4.y, vec4.z, vec4.w);
            } else if (value instanceof FloatBuffer) {
                // skip
            } else if (value instanceof Matrix4f) {
                // skip
            } else if (value instanceof Texture) {
                Texture texture = (Texture) value;
                map.put(key, new ParseText(texture.toString(), key));
            }

        }
        return map;
    }

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

    public void draw(Camera camera) {
        if (shader == null) {
            platform.log("Shader is null");
            return;
        }
        if (shader.platformId != Platforms.gl().getID()) {
            platform.log("Shader is not for the current platform");
            return;
        }
        this.camera = camera;
        setup(camera);
        // Queue draw referencing persistent buffer region instead of repacking each
        // frame
        ShaderProgram.Allocation alloc = allocationById.get(drawingId);
        if (alloc != null) {
            shader.queueDraw(alloc, Quad.VERTEX_COUNT);
        }
        cleanup(camera);
    }

    public void drawFar(Camera camera, Long id) {
        this.camera = camera;
        setup(camera);
        ShaderProgram.Allocation alloc = allocationById.get(id);
        if (alloc != null) {
            shader.queueDraw(alloc, Quad.VERTEX_COUNT);
        }
        cleanupFar(camera);
    }

    public void calculateQuad() {
        bottomLeft = new Vector2f(drawX, drawY);
        bottomRight = new Vector2f(bottomLeft).add(width, 0);
        topLeft = new Vector2f(bottomLeft).add(0, height);
        topRight = new Vector2f(bottomLeft).add(width, height);
    }

    public void drawCentered(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - (width / 2), drawY - (height / 2), width, height, c, camera);
    }

    public void drawRightBound(float drawX, float drawY, float width, float height, Color c, Camera camera) {
        draw(drawX - width, drawY, width, height, c, camera);
    }

    private ShaderProgram.Allocation ensureAllocation(Long id) {
        if (id == null)
            return null;
        ShaderProgram.Allocation alloc = allocationById.get(id);
        if (alloc == null) {
            Object key = ownerKeyById.computeIfAbsent(id, k -> new Object());
            alloc = shader.ensureAllocation(key, Quad.VERTEX_COUNT);
            allocationById.put(id, alloc);
        }
        return alloc;
    }

    private boolean isGeometryDirty(Long id) {
        if (id == null) {
            return geometryDirty;
        }
        Quad newQuad = new Quad(bottomLeft, bottomRight, topRight, topLeft);
        Quad old = prevQuadById.get(id);
        boolean changed = (old == null) || !sameQuad(old, newQuad);
        prevQuadById.put(id, newQuad);
        return changed;
    }

    private static boolean sameQuad(Quad a, Quad b) {
        float eps = 0.01f;
        return a.bl.distance(b.bl) <= eps && a.br.distance(b.br) <= eps && a.tr.distance(b.tr) <= eps
                && a.tl.distance(b.tl) <= eps;
    }

    private void uploadGeometry(ShaderProgram.Allocation target) {
        // Build quad vertex data into a temporary buffer matching the shader's stride
        int stride = shader.getStrideFloats();
        if (stride <= 0)
            stride = 9; // default for SDF/Font
        int floatsNeeded = stride * Quad.VERTEX_COUNT;
        IxBuffer buf = platform.allocateFloats(floatsNeeded);

        // Prepare common color and UVs for SDF/Font shaders; Color-only shaders ignore
        // UVs
        Vector4f color = c.toVector4f();
        // Expose vertexColor in the shader's uniform map for the expression parser
        shader.uniformMap.put("vertexColor", color);
        float r = color.x, g = color.y, b = color.z, a = color.w;
        float z = camera != null ? camera.getZIndex() : 0f;

        // Triangle 1: bottomLeft, topLeft, topRight
        if (stride == 9) {
            // pos3 + color4 + uv2
            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a).put(0f).put(0f);
            buf.put(topLeft.x).put(topLeft.y).put(z).put(r).put(g).put(b).put(a).put(0f).put(1f);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a).put(1f).put(1f);

            // Triangle 2: bottomLeft, topRight, bottomRight
            buf.put(bottomLeft.x).put(bottomLeft.y).put(z).put(r).put(g).put(b).put(a).put(0f).put(0f);
            buf.put(topRight.x).put(topRight.y).put(z).put(r).put(g).put(b).put(a).put(1f).put(1f);
            buf.put(bottomRight.x).put(bottomRight.y).put(z).put(r).put(g).put(b).put(a).put(1f).put(0f);
        } else if (stride == 7) {
            // pos3 + color4
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
}

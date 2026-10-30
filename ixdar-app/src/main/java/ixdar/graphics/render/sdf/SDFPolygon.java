package ixdar.graphics.render.sdf;

import java.util.List;

import org.joml.Vector2f;
import org.joml.Vector4f;

import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;

/**
 * A filled polygon of up to {@link #MAX_CORNERS} corners, such as one cell of an irregular grid, drawn
 * on its bounding box with an anti-aliased edge.
 */
public class SDFPolygon extends ShaderDrawable {
    public static final int MAX_CORNERS = 8;
    public static final float EDGE_PIXELS = 1f;

    private final Vector2f[] corners = new Vector2f[MAX_CORNERS];
    private int cornerCount;

    /**
     * A polygon drawn with the polygon shader.
     */
    public SDFPolygon() {
        super();
        shader = ShaderType.PolygonSDF.getShader();
    }

    /**
     * Fill a polygon.
     *
     * @param points its corners in world coordinates, in order round it; only the first eight are used
     * @param fill   the fill colour
     * @param camera camera providing transform and z-index
     */
    public void draw(List<Vector2f> points, Color fill, Camera camera) {
        cornerCount = Math.min(points.size(), MAX_CORNERS);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int k = 0; k < cornerCount; k++) {
            corners[k] = points.get(k);
            minX = Math.min(minX, corners[k].x);
            minY = Math.min(minY, corners[k].y);
            maxX = Math.max(maxX, corners[k].x);
            maxY = Math.max(maxY, corners[k].y);
        }
        draw(minX - EDGE_PIXELS, minY - EDGE_PIXELS, maxX - minX + 2 * EDGE_PIXELS, maxY - minY + 2 * EDGE_PIXELS,
                fill, camera);
    }

    /**
     * The corners in the quad's texture space, packed two to a vector, and an edge one pixel soft.
     */
    @Override
    protected void setUniforms() {
        float[] packed = new float[MAX_CORNERS * 2];
        for (int k = 0; k < cornerCount; k++) {
            Vector2f t = toTextureSpace(corners[k]);
            packed[2 * k] = t.x * texWidth;
            packed[2 * k + 1] = t.y;
        }
        String[] names = { "corners01", "corners23", "corners45", "corners67" };
        for (int v = 0; v < names.length; v++) {
            shader.setVec4(names[v], new Vector4f(packed[4 * v], packed[4 * v + 1], packed[4 * v + 2],
                    packed[4 * v + 3]));
        }
        shader.setFloat("cornerCount", cornerCount);
        shader.setFloat("edgeSharpness", EDGE_PIXELS / height);
    }
}

package ixdar.graphics.render.model;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.render.color.Color;

/**
 * Dots over a surface, each a disc a fixed number of pixels across that nearer surface hides; a
 * vertex dot takes its faces' depth. {@link MeshOverlayRuntime#setDots} shows a set.
 */
public final class SurfaceDots {

    // Disc corner rows: centre, corner, face normal, colour, diameter in pixels.
    public static final VertexLayout DISC_CORNER_LAYOUT = new VertexLayout(
            new int[] { 0, 1, 2, 3, 4 }, new int[] { 3, 2, 3, 3, 1 });

    private static final float[] DISC_CORNERS = { -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1 };
    private static final int DISC_CORNER_COUNT = 6;
    private static final int INITIAL_DOTS = 16;

    /** Dots added, counting a vertex dot once however many faces it is copied over. */
    public int count;

    private float[] rows =
            new float[INITIAL_DOTS * DISC_CORNER_COUNT * DISC_CORNER_LAYOUT.floatsPerVertex];
    private int floats;
    private final Vector3f position = new Vector3f();
    private final Vector3f normal = new Vector3f();

    /**
     * Every point of a packed cloud as one dot at its bare position.
     *
     * @param packed         flat xyz, or {@code null} for none
     * @param color          colour of every dot
     * @param diameterPixels diameter of every dot, in framebuffer pixels
     */
    public void addCloud(float[] packed, Color color, float diameterPixels) {
        for (int point = 0; packed != null && point + 2 < packed.length; point += 3) {
            add(packed[point], packed[point + 1], packed[point + 2], color, diameterPixels);
        }
    }

    /**
     * A dot on a mesh vertex, taking the depth of every face around it.
     *
     * @param mesh           the mesh the vertex belongs to
     * @param vertexId       the vertex
     * @param color          dot colour
     * @param diameterPixels diameter in framebuffer pixels
     */
    public void addVertex(MeshTopology mesh, int vertexId, Color color, float diameterPixels) {
        mesh.vertexPosition(vertexId, position);
        int faces = mesh.vertexFaceCount(vertexId);
        if (faces == 0) {
            appendDisc(position, normal.zero(), color, diameterPixels);
        }
        for (int face = 0; face < faces; face++) {
            mesh.faceNormal(mesh.vertexFaceAt(vertexId, face), normal);
            appendDisc(position, normal, color, diameterPixels);
        }
        count++;
    }

    /**
     * A dot at a bare position, keeping that position's own depth.
     *
     * @param point          world position
     * @param color          dot colour
     * @param diameterPixels diameter in framebuffer pixels
     */
    public void add(Vector3f point, Color color, float diameterPixels) {
        add(point.x, point.y, point.z, color, diameterPixels);
    }

    /**
     * A dot at a bare position, keeping that position's own depth.
     *
     * @param x              x
     * @param y              y
     * @param z              z
     * @param color          dot colour
     * @param diameterPixels diameter in framebuffer pixels
     */
    public void add(float x, float y, float z, Color color, float diameterPixels) {
        appendDisc(position.set(x, y, z), normal.zero(), color, diameterPixels);
        count++;
    }

    /**
     * The disc corner rows written so far, in {@link #DISC_CORNER_LAYOUT}.
     *
     * @return a trimmed copy of the rows
     */
    public float[] vertices() {
        return Arrays.copyOf(rows, floats);
    }

    private void appendDisc(Vector3f center, Vector3f faceNormal, Color color, float diameterPixels) {
        int needed = floats + DISC_CORNER_COUNT * DISC_CORNER_LAYOUT.floatsPerVertex;
        if (needed > rows.length) {
            rows = Arrays.copyOf(rows, Math.max(needed, 2 * rows.length));
        }
        Vector3f rgb = color.toVector3f();
        for (int corner = 0; corner < DISC_CORNER_COUNT; corner++) {
            rows[floats++] = center.x;
            rows[floats++] = center.y;
            rows[floats++] = center.z;
            rows[floats++] = DISC_CORNERS[2 * corner];
            rows[floats++] = DISC_CORNERS[2 * corner + 1];
            rows[floats++] = faceNormal.x;
            rows[floats++] = faceNormal.y;
            rows[floats++] = faceNormal.z;
            rows[floats++] = rgb.x;
            rows[floats++] = rgb.y;
            rows[floats++] = rgb.z;
            rows[floats++] = diameterPixels;
        }
    }
}

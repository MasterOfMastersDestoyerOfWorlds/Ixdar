package ixdar.procgen.dungeon.render;

import org.joml.Matrix4f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;

/**
 * Renders a low-poly capsule for debugging the player's bounding shape in third-person mode.
 * Builds an {@link ArrayMesh} of the capsule once at the player's actual radius / halfHeight,
 * then on each render writes a translation-and-rotation model matrix into the wrapped
 * {@link HalfEdgeMeshRuntime} so the mesh follows the player without per-frame mesh rebuild.
 */
public final class DebugCapsuleRuntime {
    public static final float NUM_0_8 = 0.8f;
    public static final float NUM_0_45 = 0.45f;
    public static final float NUM_0_2 = 0.2f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final double NUM_2_0 = 2.0;

    private static final int LON = 16;
    private static final int LAT = 6;

    private final HalfEdgeMeshRuntime runtime;
    private final Matrix4f model = new Matrix4f();
    private float builtRadius = -1f;
    private float builtHalfHeight = -1f;

    /**
     * Allocate the underlying mesh runtime and configure a flat solid color. The capsule mesh
     * itself is built lazily on the first {@link #render} or {@link #buildIfNeeded} call.
     *
     * @throws Exception if the underlying {@link HalfEdgeMeshRuntime} fails to initialize
     */
    public DebugCapsuleRuntime() throws Exception {
        this.runtime = new HalfEdgeMeshRuntime();
        this.runtime.setSolidColor(ColorRGB.BLUE_WHITE.toVector4f());
    }

    /**
     * Build (or rebuild) the capsule mesh at the given dimensions. Cheap; safe to call once.
     *
     * @param radius     capsule cylinder radius (also the radius of each hemisphere cap)
     * @param halfHeight half the cylinder length between the two hemisphere centers
     */
    public void buildIfNeeded(float radius, float halfHeight) {
        if (Math.abs(radius - builtRadius) < NUM_1e_6 && Math.abs(halfHeight - builtHalfHeight) < NUM_1e_6) {
            return;
        }
        ArrayMesh mesh = buildCapsuleMesh(radius, halfHeight);
        mesh.computeNormals();
        runtime.upload(mesh);
        builtRadius = radius;
        builtHalfHeight = halfHeight;
    }

    /**
     * Rebuild the mesh if dimensions changed, then translate it to {@code (px, py, pz)},
     * rotate it around Y by {@code yawRadians}, and submit a draw call against {@code camera}.
     *
     * @param camera     scene camera to render through
     * @param px         player position X in world units
     * @param py         player position Y in world units
     * @param pz         player position Z in world units
     * @param yawRadians player facing yaw in radians (Y-axis rotation)
     * @param radius     capsule radius
     * @param halfHeight capsule half-length between hemisphere centers
     */
    public void render(Camera3D camera, float px, float py, float pz, float yawRadians,
                        float radius, float halfHeight) {
        buildIfNeeded(radius, halfHeight);
        model.identity()
              .translate(px, py, pz)
              .rotateY(yawRadians);
        runtime.setModelMatrix(model);
        runtime.render(camera);
    }

    /**
     * Release the GPU resources held by the underlying mesh runtime. Idempotent on the runtime.
     */
    public void dispose() {
        runtime.dispose();
    }

    private static ArrayMesh buildCapsuleMesh(float r, float h) {
        // Vertex layout:
        //   0                          : top pole         (0, h+r, 0)
        //   1 .. LAT*LON               : top hemisphere rings 1..LAT, each LON verts
        //   1+LAT*LON .. 2*LAT*LON     : bottom hemisphere rings 0..LAT-1, each LON verts
        //   1+2*LAT*LON                : bottom pole      (0, -h-r, 0)
        int vertCount = 2 + 2 * LAT * LON;
        float[] positions = new float[vertCount * NUM_3];

        int topPole = 0;
        positions[topPole * NUM_3 + 0] = NUM_0;
        positions[topPole * NUM_3 + 1] = h + r;
        positions[topPole * NUM_3 + 2] = NUM_0;

        int topRingBase = 1;          // ring 1 starts here; ring i starts at topRingBase + (i-1)*LON
        int bottomRingBase = 1 + LAT * LON;  // bottom ring 0 starts here; ring i at bottomRingBase + i*LON
        int bottomPole = 1 + 2 * LAT * LON;
        positions[bottomPole * NUM_3 + 0] = NUM_0;
        positions[bottomPole * NUM_3 + 1] = -h - r;
        positions[bottomPole * NUM_3 + 2] = NUM_0;

        // Top hemisphere rings: theta from PI/(2*LAT) (just below pole) to PI/2 (cylinder top, inclusive).
        for (int i = 1; i <= LAT; i++) {
            float theta = (float) (i * Math.PI / (NUM_2_0 * LAT));
            float ringR = r * (float) Math.sin(theta);
            float ringY = h + r * (float) Math.cos(theta);
            int base = topRingBase + (i - 1) * LON;
            for (int j = 0; j < LON; j++) {
                float phi = (float) (j * NUM_2_0 * Math.PI / LON);
                int v = base + j;
                positions[v * NUM_3 + 0] = ringR * (float) Math.cos(phi);
                positions[v * NUM_3 + 1] = ringY;
                positions[v * NUM_3 + 2] = ringR * (float) Math.sin(phi);
            }
        }
        // Bottom hemisphere rings: phi from 0 (cylinder bottom) up to (LAT-1)*PI/(2*LAT) (one before pole).
        for (int i = 0; i < LAT; i++) {
            float phiLat = (float) (i * Math.PI / (NUM_2_0 * LAT));
            float ringR = r * (float) Math.cos(phiLat);
            float ringY = -h - r * (float) Math.sin(phiLat);
            int base = bottomRingBase + i * LON;
            for (int j = 0; j < LON; j++) {
                float lon = (float) (j * NUM_2_0 * Math.PI / LON);
                int v = base + j;
                positions[v * NUM_3 + 0] = ringR * (float) Math.cos(lon);
                positions[v * NUM_3 + 1] = ringY;
                positions[v * NUM_3 + 2] = ringR * (float) Math.sin(lon);
            }
        }

        // Triangles, CCW seen from outside.
        // Counts:
        //   top cap (pole + ring1)              : LON
        //   top hemisphere body (rings i..i+1)  : (LAT-1) * 2 * LON
        //   cylinder body (top LAT to bot 0)    : 2 * LON
        //   bottom hemisphere body              : (LAT-1) * 2 * LON
        //   bottom cap (ring LAT-1 + pole)      : LON
        int triCount = LON + (LAT - 1) * 2 * LON + 2 * LON + (LAT - 1) * 2 * LON + LON;
        int[] indices = new int[triCount * NUM_3];
        int cursor = 0;

        // Top cap: pole, ring1[j], ring1[j+1]
        int topRing1 = topRingBase;
        for (int j = 0; j < LON; j++) {
            int j1 = (j + 1) % LON;
            indices[cursor++] = topPole;
            indices[cursor++] = topRing1 + j;
            indices[cursor++] = topRing1 + j1;
        }

        // Top hemisphere body: between ring i and ring i+1 (i in 1..LAT-1).
        for (int i = 1; i < LAT; i++) {
            int ringA = topRingBase + (i - 1) * LON;
            int ringB = topRingBase + i * LON;
            for (int j = 0; j < LON; j++) {
                int j1 = (j + 1) % LON;
                // quad (a0, a1, b1, b0) -> tris (a0, b0, b1) and (a0, b1, a1) for outward CCW.
                indices[cursor++] = ringA + j;
                indices[cursor++] = ringB + j;
                indices[cursor++] = ringB + j1;
                indices[cursor++] = ringA + j;
                indices[cursor++] = ringB + j1;
                indices[cursor++] = ringA + j1;
            }
        }

        // Cylinder body: top ring LAT to bottom ring 0.
        int topCylRing = topRingBase + (LAT - 1) * LON;
        int botCylRing = bottomRingBase;
        for (int j = 0; j < LON; j++) {
            int j1 = (j + 1) % LON;
            indices[cursor++] = topCylRing + j;
            indices[cursor++] = botCylRing + j;
            indices[cursor++] = botCylRing + j1;
            indices[cursor++] = topCylRing + j;
            indices[cursor++] = botCylRing + j1;
            indices[cursor++] = topCylRing + j1;
        }

        // Bottom hemisphere body: between ring i and ring i+1 (i in 0..LAT-2).
        for (int i = 0; i < LAT - 1; i++) {
            int ringA = bottomRingBase + i * LON;
            int ringB = bottomRingBase + (i + 1) * LON;
            for (int j = 0; j < LON; j++) {
                int j1 = (j + 1) % LON;
                indices[cursor++] = ringA + j;
                indices[cursor++] = ringB + j;
                indices[cursor++] = ringB + j1;
                indices[cursor++] = ringA + j;
                indices[cursor++] = ringB + j1;
                indices[cursor++] = ringA + j1;
            }
        }

        // Bottom cap: pole, ring(LAT-1)[j+1], ring(LAT-1)[j]  (note swapped winding for outward).
        int botLastRing = bottomRingBase + (LAT - 1) * LON;
        for (int j = 0; j < LON; j++) {
            int j1 = (j + 1) % LON;
            indices[cursor++] = bottomPole;
            indices[cursor++] = botLastRing + j1;
            indices[cursor++] = botLastRing + j;
        }

        return new ArrayMesh(positions, null, indices, NUM_3);
    }
}

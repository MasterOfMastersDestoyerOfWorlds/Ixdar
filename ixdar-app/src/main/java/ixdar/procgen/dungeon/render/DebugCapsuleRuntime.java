package ixdar.procgen.dungeon.render;

import org.joml.Matrix4f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.scenes.Scene;

/**
 * Renders a low-poly capsule for debugging the player's bounding shape in third-person mode.
 * Builds a {@link HalfEdgeMesh} of the capsule once at the player's actual radius / halfHeight,
 * then on each render writes a translation-and-rotation model matrix into the wrapped
 * {@link HalfEdgeMeshRuntime} so the mesh follows the player without per-frame mesh rebuild.
 */
public final class DebugCapsuleRuntime {
    public static final float MESH_REBUILD_EPSILON = 1e-6f;

    private static final int LON = 16;
    private static final int LAT = 6;

    private final HalfEdgeMeshRuntime runtime;
    private final Matrix4f model = new Matrix4f();
    private float builtRadius = -1f;
    private float builtHalfHeight = -1f;

    /**
     * Allocate the underlying mesh runtime. The capsule mesh itself is built lazily on the first
     * {@link #render} or {@link #buildIfNeeded} call, and drawn flat in the theme's line colour.
     *
     * @throws Exception if the underlying {@link HalfEdgeMeshRuntime} fails to initialize
     */
    public DebugCapsuleRuntime() throws Exception {
        this.runtime = new HalfEdgeMeshRuntime();
    }

    /**
     * Build (or rebuild) the capsule mesh at the given dimensions. Cheap; safe to call once.
     *
     * @param radius     capsule cylinder radius (also the radius of each hemisphere cap)
     * @param halfHeight half the cylinder length between the two hemisphere centers
     */
    public void buildIfNeeded(float radius, float halfHeight) {
        if (Math.abs(radius - builtRadius) < MESH_REBUILD_EPSILON
                && Math.abs(halfHeight - builtHalfHeight) < MESH_REBUILD_EPSILON) {
            return;
        }
        HalfEdgeMesh mesh = buildCapsuleMesh(radius, halfHeight);
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
        runtime.setSolidColor(Scene.activePalette().line.toVector4f());
        runtime.render(camera);
    }

    /**
     * Release the GPU resources held by the underlying mesh runtime. Idempotent on the runtime.
     */
    public void dispose() {
        runtime.dispose();
    }

    private static HalfEdgeMesh buildCapsuleMesh(float r, float h) {
        int vertCount = 2 + 2 * LAT * LON;
        PackedPoints positions = new PackedPoints(vertCount);

        int topPole = 0;
        positions.set(topPole, 0f, h + r, 0f);

        int topRingBase = 1;          // ring 1 starts here; ring i starts at topRingBase + (i-1)*LON
        int bottomRingBase = 1 + LAT * LON;  // bottom ring 0 starts here; ring i at bottomRingBase + i*LON
        int bottomPole = 1 + 2 * LAT * LON;
        positions.set(bottomPole, 0f, -h - r, 0f);

        for (int i = 1; i <= LAT; i++) {
            float theta = (float) (i * Math.PI / (2.0 * LAT));
            float ringR = r * (float) Math.sin(theta);
            float ringY = h + r * (float) Math.cos(theta);
            int base = topRingBase + (i - 1) * LON;
            for (int j = 0; j < LON; j++) {
                float phi = (float) (j * 2.0 * Math.PI / LON);
                int v = base + j;
                positions.set(v, ringR * (float) Math.cos(phi), ringY, ringR * (float) Math.sin(phi));
            }
        }
        for (int i = 0; i < LAT; i++) {
            float phiLat = (float) (i * Math.PI / (2.0 * LAT));
            float ringR = r * (float) Math.cos(phiLat);
            float ringY = -h - r * (float) Math.sin(phiLat);
            int base = bottomRingBase + i * LON;
            for (int j = 0; j < LON; j++) {
                float lon = (float) (j * 2.0 * Math.PI / LON);
                int v = base + j;
                positions.set(v, ringR * (float) Math.cos(lon), ringY, ringR * (float) Math.sin(lon));
            }
        }

        int triCount = LON + (LAT - 1) * 2 * LON + 2 * LON + (LAT - 1) * 2 * LON + LON;
        int[] indices = new int[triCount * 3];
        int cursor = 0;

        int topRing1 = topRingBase;
        for (int j = 0; j < LON; j++) {
            int j1 = (j + 1) % LON;
            indices[cursor++] = topPole;
            indices[cursor++] = topRing1 + j;
            indices[cursor++] = topRing1 + j1;
        }

        for (int i = 1; i < LAT; i++) {
            int ringA = topRingBase + (i - 1) * LON;
            int ringB = topRingBase + i * LON;
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

        int botLastRing = bottomRingBase + (LAT - 1) * LON;
        for (int j = 0; j < LON; j++) {
            int j1 = (j + 1) % LON;
            indices[cursor++] = bottomPole;
            indices[cursor++] = botLastRing + j1;
            indices[cursor++] = botLastRing + j;
        }

        return HalfEdgeMeshOps.bulkAllocate(positions, indices, 3);
    }
}

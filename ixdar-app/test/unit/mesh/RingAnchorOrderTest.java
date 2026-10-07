package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfacePathCrossings;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * The ring tool's anchor cycle: on a flattened tube, whose two faces are closer in 3D than
 * neighbouring anchors on one face, any click order gives one simple ring.
 */
class RingAnchorOrderTest {

    private static final int XYZ = 3;

    private static final int SIDES = 96;

    private static final int RINGS = 41;

    private static final float HALF_LENGTH = 1f;

    private static final float FLAT_HALF_WIDTH = 0.44f;

    private static final float HALF_THICKNESS = 0.06f;

    private static final float ROUND_RADIUS = 0.4f;

    // Where the anchors sit across the tube's width, each on both flat faces; no two are mirror
    // images, so no anchor on one face is equally far from one on the other both ways round.
    private static final float[] ACROSS = { -0.4f, -0.22f, -0.03f, 0.17f, 0.36f };

    // Along the tube, the anchors alternate this far either side of the middle, so no plane
    // holds them all.
    private static final float ALONG_JITTER = 0.03f;

    private static final int SHUFFLES = 12;

    private static final long SEED = 44L;

    // The base normal a click on the preview hands the draft: along the tube, tipped a little as
    // a girdling plane found from a hit always is, so the plane does not run through a whole
    // ring of vertices.
    private static final float[] TUBE_AXIS = { 0.998f, 0.05f, 0.03f };

    private static final int QUARTERS = 4;

    private static final double MOVED_ANCHOR_DEGREES = 200.0;

    @Test
    void anchorsAddedInAnyOrderGiveTheSameSimpleRingByLeastAddedLength() {
        MeshTopology tube = tube(FLAT_HALF_WIDTH, HALF_THICKNESS);
        // The top face across its width first, then the bottom face across the same widths.
        float[] xyz = new float[XYZ * 2 * ACROSS.length];
        for (int face = 0; face < 2; face++) {
            for (int anchor = 0; anchor < ACROSS.length; anchor++) {
                int base = XYZ * (face * ACROSS.length + anchor);
                xyz[base] = (anchor + face) % 2 == 0 ? ALONG_JITTER : -ALONG_JITTER;
                xyz[base + 1] = ACROSS[anchor];
                xyz[base + 2] = face == 0 ? HALF_THICKNESS : -HALF_THICKNESS;
            }
        }
        int[] anchors = SurfaceWaypoints.snap(tube, xyz, 2 * ACROSS.length);
        // Across the top face, then back across the bottom.
        int[] around = new int[anchors.length];
        for (int anchor = 0; anchor < ACROSS.length; anchor++) {
            around[anchor] = anchor;
            around[ACROSS.length + anchor] = 2 * ACROSS.length - 1 - anchor;
        }
        String expected = canonicalCycle(around);
        Random random = new Random(SEED);
        SurfacePathCrossings crossings = new SurfacePathCrossings();
        for (int shuffle = 0; shuffle < SHUFFLES; shuffle++) {
            int[] order = new int[anchors.length];
            for (int anchor = 0; anchor < order.length; anchor++) {
                order[anchor] = anchor;
            }
            for (int slot = order.length - 1; slot > 0; slot--) {
                int swap = random.nextInt(slot + 1);
                int held = order[slot];
                order[slot] = order[swap];
                order[swap] = held;
            }
            // A refused click is clicked again after the next one the ring takes, the way a user
            // comes back to a spot once the ring has grown toward it.
            RingTool tool = draftOn(tube, anchors[order[0]], TUBE_AXIS);
            int[] pending = Arrays.copyOfRange(order, 1, order.length);
            int pendingCount = pending.length;
            int refused = 0;
            while (pendingCount > 0 && refused < pendingCount) {
                int click = pending[0];
                System.arraycopy(pending, 1, pending, 0, pendingCount - 1);
                int[] before = tool.draftAuthoredVertexId;
                SurfaceSpline drawn = tool.draft;
                if (!tool.addAuthoredAnchor(anchors[click])) {
                    assertTrue(tool.lastError.contains("cross itself"), tool.lastError);
                    assertSame(drawn, tool.draft, "a refused click changed the ring");
                    pending[pendingCount - 1] = click;
                    refused++;
                    continue;
                }
                pendingCount--;
                refused = 0;
                // The held anchors keep their cyclic order, the new one added somewhere.
                int[] after = tool.draftAuthoredVertexId;
                int[] kept = new int[before.length];
                int next = 0;
                for (int vertexId : after) {
                    boolean held = false;
                    for (int heldId : before) {
                        held |= heldId == vertexId;
                    }
                    if (held) {
                        kept[next++] = vertexId;
                    }
                }
                assertTrue(before.length < AuthoredSplineRing.PLANE_STARTED_ANCHORS
                        || canonicalCycle(kept).equals(canonicalCycle(before)), "click " + click
                                + " reordered " + Arrays.toString(before) + " into "
                                + Arrays.toString(after));
                assertTrue(crossings.isSimple(tube, tool.draft.surfacePath()),
                        "order " + Arrays.toString(order) + ", click " + click + ": " + tool.lastRow);
            }
            assertEquals(0, pendingCount, "order " + Arrays.toString(order) + " left clicks "
                    + Arrays.toString(Arrays.copyOf(pending, pendingCount)) + " refused: "
                    + tool.lastError);
            int[] cycle = new int[anchors.length];
            for (int anchor = 0; anchor < cycle.length; anchor++) {
                for (int index = 0; index < anchors.length; index++) {
                    cycle[anchor] = anchors[index] == tool.draftAuthoredVertexId[anchor] ? index
                            : cycle[anchor];
                }
            }
            assertEquals(expected, canonicalCycle(cycle), "order " + Arrays.toString(order));
        }
    }

    @Test
    void aMoveThatWouldCrossTheRingIsRefused() {
        MeshTopology tube = tube(0f, ROUND_RADIUS);
        float[] xyz = new float[XYZ * QUARTERS];
        for (int quarter = 0; quarter < QUARTERS; quarter++) {
            double angle = 2.0 * Math.PI * quarter / QUARTERS;
            xyz[XYZ * quarter + 1] = (float) (ROUND_RADIUS * Math.cos(angle));
            xyz[XYZ * quarter + 2] = (float) (ROUND_RADIUS * Math.sin(angle));
        }
        int[] quarters = SurfaceWaypoints.snap(tube, xyz, QUARTERS);
        RingTool tool = draftOn(tube, quarters[0], TUBE_AXIS);
        for (int quarter = 1; quarter < QUARTERS; quarter++) {
            assertTrue(tool.addAuthoredAnchor(quarters[quarter]), tool.lastError);
        }
        int[] before = tool.draftAuthoredVertexId;
        SurfaceSpline drawn = tool.draft;
        // The quarter-turn anchor keeps its place between the first and the half-turn one, but
        // moves past the half turn, so the ring must double back over itself to reach it.
        double angle = Math.toRadians(MOVED_ANCHOR_DEGREES);
        int moved = SurfaceWaypoints.snap(tube, new float[] { 0f,
            (float) (ROUND_RADIUS * Math.cos(angle)), (float) (ROUND_RADIUS * Math.sin(angle)) },
                1)[0];
        tool.selectedAnchorVertexId = quarters[1];

        assertFalse(tool.moveSelectedAnchor(moved), "the crossing move was taken");
        assertTrue(tool.lastError.startsWith("refused"), tool.lastError);
        assertArrayEquals(before, tool.draftAuthoredVertexId);
        assertSame(drawn, tool.draft);
        assertEquals(quarters[1], tool.selectedAnchorVertexId);
    }

    /**
     * The tool with a one-anchor draft on a tube, its plane across the tube, as a click on the
     * preview opens it.
     */
    private static RingTool draftOn(MeshTopology tube, int firstAnchor, float[] normal) {
        RingScene scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return tube;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return tube;
            }
        };
        RingTool tool = scene.ringTool;
        tool.geodesics = SurfaceGeodesics.over(tube);
        AuthoredSplineRing first = new AuthoredSplineRing(tool.geodesics);
        assertTrue(first.trace(new int[] { firstAnchor }, 1, normal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), first.failure);
        System.arraycopy(normal, 0, tool.draftBaseNormal, 0, XYZ);
        tool.draftAuthoredVertexId = first.authoredVertexId;
        tool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        tool.draft = SurfaceSpline.of(first.tracer);
        return tool;
    }

    private static String canonicalCycle(int[] cycle) {
        int count = cycle.length;
        int start = 0;
        for (int index = 1; index < count; index++) {
            start = cycle[index] < cycle[start] ? index : start;
        }
        int[] forward = new int[count];
        int[] backward = new int[count];
        for (int step = 0; step < count; step++) {
            forward[step] = cycle[(start + step) % count];
            backward[step] = cycle[Math.floorMod(start - step, count)];
        }
        return Arrays.toString(Arrays.compare(forward, backward) <= 0 ? forward : backward);
    }

    /**
     * An open tube along x whose cross-section is a stadium: two flat faces {@code flatHalfWidth}
     * either side of the middle joined by half circles, sampled evenly by arc length.
     */
    public static MeshTopology tube(float flatHalfWidth, float radius) {
        double flat = 2.0 * flatHalfWidth;
        double perimeter = 2.0 * flat + 2.0 * Math.PI * radius;
        float[] positions = new float[XYZ * SIDES * RINGS];
        for (int ring = 0; ring < RINGS; ring++) {
            float x = -HALF_LENGTH + 2f * HALF_LENGTH * ring / (RINGS - 1);
            for (int side = 0; side < SIDES; side++) {
                double along = perimeter * side / SIDES;
                double y;
                double z;
                if (along < flat) {
                    y = flatHalfWidth - along;
                    z = radius;
                } else if (along < flat + Math.PI * radius) {
                    double angle = Math.PI / 2.0 + (along - flat) / radius;
                    y = -flatHalfWidth + radius * Math.cos(angle);
                    z = radius * Math.sin(angle);
                } else if (along < 2.0 * flat + Math.PI * radius) {
                    y = -flatHalfWidth + (along - flat - Math.PI * radius);
                    z = -radius;
                } else {
                    double angle = -Math.PI / 2.0
                            + (along - 2.0 * flat - Math.PI * radius) / radius;
                    y = flatHalfWidth + radius * Math.cos(angle);
                    z = radius * Math.sin(angle);
                }
                int base = XYZ * (SIDES * ring + side);
                positions[base] = x;
                positions[base + 1] = (float) y;
                positions[base + 2] = (float) z;
            }
        }
        int[] faces = new int[2 * XYZ * SIDES * (RINGS - 1)];
        int corner = 0;
        for (int ring = 0; ring + 1 < RINGS; ring++) {
            for (int side = 0; side < SIDES; side++) {
                int here = SIDES * ring + side;
                int ahead = SIDES * ring + (side + 1) % SIDES;
                int over = SIDES * (ring + 1) + side;
                int overAhead = SIDES * (ring + 1) + (side + 1) % SIDES;
                faces[corner++] = here;
                faces[corner++] = over;
                faces[corner++] = overAhead;
                faces[corner++] = here;
                faces[corner++] = overAhead;
                faces[corner++] = ahead;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }
}

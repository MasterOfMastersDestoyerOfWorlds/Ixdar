package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshRepair;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.modifier.RepairMeshNode;

/**
 * One procedural fixture per defect class {@code repair_mesh} handles. No fixture reads a file:
 * every mesh here is built from coordinates in the test itself.
 */
class MeshRepairTest {

    /** Corners of a triangle. */
    private static final int TRIANGLE_CORNERS = 3;

    /** Weld distance used where the fixture asks for one. */
    private static final double WELD_EPSILON = 1e-4;

    /** Faces of the cube fixture. */
    private static final int CUBE_FACES = 12;

    /** Segments around the Mobius band. */
    private static final int MOBIUS_SEGMENTS = 12;

    /** Half-width of the Mobius band's cross section. */
    private static final double MOBIUS_HALF_WIDTH = 0.3;

    /** Vertices on the annulus fixture's inner rim, which is its six-edge hole. */
    private static final int RIM_VERTICES = 6;

    /** Outer-ring vertices per rim vertex on the annulus fixture. */
    private static final int OUTER_PER_RIM = 3;

    /** Radius of the annulus fixture's inner rim. */
    private static final double RIM_RADIUS = 1.0;

    /** Radius of the annulus fixture's outer ring. */
    private static final double OUTER_RADIUS = 1.1;

    /** Full turn in radians. */
    private static final double TAU = Math.PI * 2;

    /** Radius of the tetrahedral bubble floating inside the octahedron fixture. */
    private static final float BUBBLE_RADIUS = 0.25f;

    /** Faces of the octahedron fixture. */
    private static final int OCTAHEDRON_FACES = 8;

    /** Faces of the tetrahedral bubble. */
    private static final int TETRAHEDRON_FACES = 4;

    @Test
    void seamSplitSquareWeldsToFourVerticesWithUvsIntactPerCorner() {
        float[] positions = {
            0, 0, 0, 1, 0, 0, 1, 1, 0,
            0, 0, 0, 1, 1, 0, 0, 1, 0,
        };
        int[] faces = { 0, 1, 2, 3, 4, 5 };
        double[] cornerU = { 0, 1, 1, 0, 1, 0 };
        double[] cornerV = { 0, 0, 1, 0, 1, 1 };
        GeometryBundle input = GeometryBundle.ofMesh(new ArrayMesh(positions, null, faces,
                TRIANGLE_CORNERS)).withSlot(CornerUvField.SLOT,
                        new CornerUvField(cornerU.clone(), cornerV.clone()));

        MeshRepair repair = repair(input, WELD_EPSILON, 0, 1);

        assertEquals(2, repair.report.weldedVertexCount, "the seam duplicates fuse");
        assertEquals(4, repair.report.outputVertexCount, "the square keeps four corners");
        assertEquals(2, repair.report.outputFaceCount, "no face is lost");
        CornerUvField uv = (CornerUvField) repair.output.slots().get(CornerUvField.SLOT);
        assertArrayEquals(cornerU, uv.cornerU, "u survives the weld per corner");
        assertArrayEquals(cornerV, uv.cornerV, "v survives the weld per corner");
    }

    @Test
    void finOnACubeBecomesItsOwnBoundaryFlap() {
        MeshRepair repair = repair(cubeWithFin(), 0, 0, 1);

        assertEquals(1, repair.report.nonManifoldEdgeCount, "the fin's root edge carries three faces");
        assertEquals(2, repair.report.splitVertexCount, "the fin gets its own copy of both endpoints");
        assertEquals(2, repair.report.shellCount, "the fin comes away as its own shell");
        assertArrayEquals(new int[] { CUBE_FACES, 1 }, repair.report.shellFaceCounts,
                "the cube keeps its twelve faces and the fin its one");
        assertArrayEquals(new boolean[] { false, false }, repair.report.shellContained,
                "the fin sticks out of the cube");
        assertEquals(0, repair.report.droppedShellCount, "min_shell_faces of one keeps the flap");
        assertEquals(1, repair.report.holeCount, "the flap has one boundary loop");
        assertArrayEquals(new int[] { 3 }, repair.report.holeEdgeCounts,
                "the flap's loop is its three edges");
        assertHalfEdgeBuildable(repair);
    }

    @Test
    void finBelowTheDebrisThresholdIsDroppedAndReported() {
        MeshRepair repair = repair(cubeWithFin(), 0, 0, 2);

        assertEquals(1, repair.report.droppedShellCount, "a one-face shell is debris at min two");
        assertEquals(1, repair.report.droppedShellFaceCount, "the dropped face is counted");
        assertEquals(CUBE_FACES, repair.report.outputFaceCount, "the cube is untouched");
        assertFalse(repair.report.hasUnrepaired(),
                "a dropped debris shell is a deliberate drop, not a tear strict refuses");
    }

    @Test
    void mobiusStripIsReportedNotSilentlyOriented() {
        MeshRepair repair = repair(mobiusStrip(), 0, 0, 1);

        assertTrue(repair.report.mobiusEdgeCount >= 1,
                "the twist makes at least one edge demand both windings");
        assertTrue(repair.report.nonManifoldEdgeCount >= 1,
                "the contradiction is reported as non-manifold");
        assertHalfEdgeBuildable(repair);
    }

    @Test
    void sphereWithAnInteriorBubbleDropsTheBubbleAndKeepsTheSphere() {
        MeshRepair repair = repair(sphereWithBubble(), 0, 0, 1);

        assertEquals(2, repair.report.shellCount, "the bubble is its own shell");
        assertArrayEquals(new int[] { OCTAHEDRON_FACES, TETRAHEDRON_FACES },
                repair.report.shellFaceCounts, "shells are reported largest first");
        assertArrayEquals(new boolean[] { false, true }, repair.report.shellContained,
                "ray parity puts the smaller shell inside the larger");
        assertArrayEquals(new boolean[] { true, false }, repair.report.shellKept,
                "an enclosed bubble is dropped even above the debris threshold");
        assertEquals(1, repair.report.droppedShellCount, "one shell goes");
        assertEquals(repair.report.shellFaceCounts[0], repair.report.outputFaceCount,
                "exactly the outer sphere survives");
        assertEquals(0, repair.report.outputBoundaryEdgeCount, "the sphere stays closed");
    }

    @Test
    void sixEdgeHoleIsFilledAndFairedWhileTheLongLoopStaysOpen() {
        MeshRepair repair = repair(annulusWithSixEdgeHole(), 0, RIM_VERTICES, 1);

        assertEquals(2, repair.report.holeCount, "the annulus has an inner and an outer loop");
        assertArrayEquals(new int[] { RIM_VERTICES * OUTER_PER_RIM, RIM_VERTICES },
                repair.report.holeEdgeCounts, "loops are reported longest first");
        assertArrayEquals(new boolean[] { false, true }, repair.report.holeFilled,
                "only the six-edge loop is inside max_hole_edges");
        assertEquals(1, repair.report.openHoleCount, "the outer loop is reported open");
        assertEquals(1, repair.report.oversizedHoleCount, "and reported as over the cap");
        assertEquals(0, repair.report.unfillableHoleCount, "nothing failed to triangulate");
        assertTrue(repair.report.hasUnrepaired(), "a loop over the cap is what strict refuses");
        assertTrue(repair.report.fillFaceCount >= RIM_VERTICES - 2,
                "a hexagon needs at least four triangles");
        assertTrue(repair.report.fillVertexCount >= 1,
                "the refinement inserts at least one vertex for the fairing to move");
        assertEquals(RIM_VERTICES * OUTER_PER_RIM, repair.report.outputBoundaryEdgeCount,
                "only the outer loop is left open");
        assertHalfEdgeBuildable(repair);
    }

    @Test
    void fillVerticesGetNoTextureCoordinateAndAreReported() {
        GeometryBundle annulus = annulusWithSixEdgeHole();
        int corners = annulus.mesh().faceCount() * TRIANGLE_CORNERS;
        GeometryBundle withUv = annulus.withSlot(CornerUvField.SLOT,
                new CornerUvField(new double[corners], new double[corners]));

        MeshRepair repair = repair(withUv, 0, RIM_VERTICES, 1);

        assertEquals(repair.report.fillFaceCount * TRIANGLE_CORNERS,
                repair.report.unsetUvCornerCount, "every filled corner is reported unset");
        CornerUvField uv = (CornerUvField) repair.output.slots().get(CornerUvField.SLOT);
        assertEquals(repair.report.outputFaceCount, uv.faceCount(), "the field still covers faces");
        assertTrue(Double.isNaN(uv.cornerU[uv.cornerU.length - 1]),
                "the last corner belongs to a fill triangle and has no u");
    }

    @Test
    void theReportIsByteStableAcrossRuns() {
        String first = repair(cubeWithFin(), WELD_EPSILON, RIM_VERTICES, 2).report.toText();
        String second = repair(cubeWithFin(), WELD_EPSILON, RIM_VERTICES, 2).report.toText();

        assertEquals(first, second, "two runs over one mesh render the same report");
        assertTrue(first.startsWith("mesh repair report\n"), "the report names itself first");
    }

    @Test
    void strictModeFailsOnALoopOverTheCap() {
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, cubeWithFin());
        context.setInput(RepairMeshNode.MAX_HOLE_EDGES.name, 0);
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, 1);
        context.setInput(RepairMeshNode.STRICT.name, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> node.evaluate(context));

        assertTrue(failure.getMessage().contains("over max_hole_edges"), "the reason is named");
        assertTrue(failure.getMessage().contains("oversizedHoleCount=1"), "the report is attached");
    }

    @Test
    void strictModeIgnoresADroppedDebrisShell() {
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, cubeWithFin());
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, 2);
        context.setInput(RepairMeshNode.STRICT.name, true);
        node.evaluate(context);

        GeometryBundle output = context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class);
        MeshRepairReport report = (MeshRepairReport) output.slots().get(MeshRepairReport.SLOT);
        assertEquals(1, report.droppedShellCount, "the fin is still dropped as debris");
        assertEquals(0, report.openHoleCount, "and the cube that remains is closed");
    }

    @Test
    void aCleanRepairPassesStrictMode() {
        RepairMeshNode node = new RepairMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(RepairMeshNode.GEOMETRY.name, cubeWithFin());
        context.setInput(RepairMeshNode.MIN_SHELL_FACES.name, 1);
        context.setInput(RepairMeshNode.STRICT.name, true);
        node.evaluate(context);

        GeometryBundle output = context.getOutput(RepairMeshNode.GEOMETRY.name, GeometryBundle.class);
        assertFalse(output.slots().isEmpty(), "the report and the boundary marks ride the output");
        MeshRepairReport report = (MeshRepairReport) output.slots().get(MeshRepairReport.SLOT);
        assertEquals(0, report.outputBoundaryEdgeCount,
                "the default cap fills the flap's loop, leaving no boundary at all");
    }

    /**
     * Runs a repair with the three parameters the node exposes.
     *
     * @param input bundle to repair
     * @param weldEpsilon weld distance, zero to skip the weld
     * @param maxHoleEdges longest boundary loop to fill
     * @param minShellFaces debris threshold
     * @return the finished repair, carrying its report and output
     */
    private static MeshRepair repair(GeometryBundle input, double weldEpsilon, int maxHoleEdges,
            int minShellFaces) {
        MeshRepair repair = new MeshRepair(input);
        repair.weldEpsilon = weldEpsilon;
        repair.maxHoleEdges = maxHoleEdges;
        repair.minShellFaces = minShellFaces;
        repair.build();
        return repair;
    }

    /**
     * Asserts the repaired mesh is what the half-edge build accepts, which is the whole point of
     * the non-manifold split.
     *
     * @param repair a finished repair
     */
    private static void assertHalfEdgeBuildable(MeshRepair repair) {
        HalfEdgeMesh built = (HalfEdgeMesh) repair.output.mesh();
        assertEquals(repair.report.outputFaceCount, built.faceCount(),
                "every repaired face is accepted");
        assertEquals(repair.report.outputVertexCount, built.vertexCount(),
                "every repaired vertex is accepted");
    }

    /**
     * A unit cube of twelve outward triangles with one extra triangle glued to the bottom-front
     * edge, which gives that edge a third incident face.
     *
     * @return the fixture bundle
     */
    private static GeometryBundle cubeWithFin() {
        float[] positions = {
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
            0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1,
            0.5f, -0.5f, -0.5f,
        };
        int[] faces = {
            0, 3, 2, 0, 2, 1,
            4, 5, 6, 4, 6, 7,
            0, 1, 5, 0, 5, 4,
            3, 7, 6, 3, 6, 2,
            0, 4, 7, 0, 7, 3,
            1, 2, 6, 1, 6, 5,
            0, 1, 8,
        };
        return GeometryBundle.ofMesh(new ArrayMesh(positions, null, faces, TRIANGLE_CORNERS));
    }

    /**
     * A triangulated Mobius band: a closed ladder whose last rung swaps its two rails, so no
     * consistent winding exists.
     *
     * @return the fixture bundle
     */
    private static GeometryBundle mobiusStrip() {
        float[] positions = new float[MOBIUS_SEGMENTS * 2 * TRIANGLE_CORNERS];
        for (int segment = 0; segment < MOBIUS_SEGMENTS; segment++) {
            double angle = TAU * segment / MOBIUS_SEGMENTS;
            double half = angle / 2;
            double outer = 1 + MOBIUS_HALF_WIDTH * Math.cos(half);
            double inner = 1 - MOBIUS_HALF_WIDTH * Math.cos(half);
            double height = MOBIUS_HALF_WIDTH * Math.sin(half);
            int rail = segment * 2 * TRIANGLE_CORNERS;
            positions[rail] = (float) (outer * Math.cos(angle));
            positions[rail + 1] = (float) (outer * Math.sin(angle));
            positions[rail + 2] = (float) height;
            positions[rail + TRIANGLE_CORNERS] = (float) (inner * Math.cos(angle));
            positions[rail + TRIANGLE_CORNERS + 1] = (float) (inner * Math.sin(angle));
            positions[rail + TRIANGLE_CORNERS + 2] = (float) -height;
        }
        int[] faces = new int[MOBIUS_SEGMENTS * 2 * TRIANGLE_CORNERS];
        for (int segment = 0; segment < MOBIUS_SEGMENTS; segment++) {
            int railA = segment * 2;
            int railB = segment * 2 + 1;
            boolean last = segment == MOBIUS_SEGMENTS - 1;
            int nextA = last ? 1 : (segment + 1) * 2;
            int nextB = last ? 0 : (segment + 1) * 2 + 1;
            int slot = segment * 2 * TRIANGLE_CORNERS;
            faces[slot] = railA;
            faces[slot + 1] = railB;
            faces[slot + 2] = nextB;
            faces[slot + TRIANGLE_CORNERS] = railA;
            faces[slot + TRIANGLE_CORNERS + 1] = nextB;
            faces[slot + TRIANGLE_CORNERS + 2] = nextA;
        }
        return GeometryBundle.ofMesh(new ArrayMesh(positions, null, faces, TRIANGLE_CORNERS));
    }

    /**
     * An octahedron of eight faces with a smaller closed tetrahedron floating inside it.
     *
     * @return the fixture bundle
     */
    private static GeometryBundle sphereWithBubble() {
        float[] spherePositions = octahedronPositions(1);
        int[] sphereFaces = octahedronFaces();
        int sphereVertexCount = spherePositions.length / TRIANGLE_CORNERS;
        float[] bubblePositions = tetrahedronPositions(BUBBLE_RADIUS);
        int[] bubbleFaces = tetrahedronFaces();
        float[] positions = Arrays.copyOf(spherePositions,
                spherePositions.length + bubblePositions.length);
        System.arraycopy(bubblePositions, 0, positions, spherePositions.length,
                bubblePositions.length);
        int[] faces = Arrays.copyOf(sphereFaces, sphereFaces.length + bubbleFaces.length);
        for (int index = 0; index < bubbleFaces.length; index++) {
            faces[sphereFaces.length + index] = bubbleFaces[index] + sphereVertexCount;
        }
        return GeometryBundle.ofMesh(new ArrayMesh(positions, null, faces, TRIANGLE_CORNERS));
    }

    /**
     * The four corners of a regular tetrahedron at the given radius.
     *
     * @param radius distance of each vertex from the origin
     * @return packed xyz triples
     */
    private static float[] tetrahedronPositions(float radius) {
        float leg = (float) (radius / Math.sqrt(TRIANGLE_CORNERS));
        return new float[] {
            leg, leg, leg, leg, -leg, -leg, -leg, leg, -leg, -leg, -leg, leg,
        };
    }

    /**
     * The four outward-wound faces of a tetrahedron over {@link #tetrahedronPositions}.
     *
     * @return flat triangle index buffer
     */
    private static int[] tetrahedronFaces() {
        return new int[] { 0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2 };
    }

    /**
     * The six axis vertices of an octahedron at the given radius.
     *
     * @param radius distance of each vertex from the origin
     * @return packed xyz triples
     */
    private static float[] octahedronPositions(float radius) {
        return new float[] {
            radius, 0, 0, -radius, 0, 0,
            0, radius, 0, 0, -radius, 0,
            0, 0, radius, 0, 0, -radius,
        };
    }

    /**
     * The eight outward-wound faces of an octahedron over {@link #octahedronPositions}.
     *
     * @return flat triangle index buffer
     */
    private static int[] octahedronFaces() {
        return new int[] {
            0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4,
            2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5,
        };
    }

    /**
     * Appends one triangle to a flat index buffer.
     *
     * @param faces buffer to write into
     * @param faceCount faces already written
     * @param cornerA first corner
     * @param cornerB second corner
     * @param cornerC third corner
     * @return the new face count
     */
    private static int appendFace(int[] faces, int faceCount, int cornerA, int cornerB,
            int cornerC) {
        faces[faceCount * TRIANGLE_CORNERS] = cornerA;
        faces[faceCount * TRIANGLE_CORNERS + 1] = cornerB;
        faces[faceCount * TRIANGLE_CORNERS + 2] = cornerC;
        return faceCount + 1;
    }

    /**
     * A flat annulus whose inner rim is a six-edge hole and whose outer ring is three times as
     * dense, so the hole fill has a finer neighbourhood to match and the refinement has to insert.
     *
     * @return the fixture bundle
     */
    private static GeometryBundle annulusWithSixEdgeHole() {
        int outerCount = RIM_VERTICES * OUTER_PER_RIM;
        float[] positions = new float[(RIM_VERTICES + outerCount) * TRIANGLE_CORNERS];
        for (int rim = 0; rim < RIM_VERTICES; rim++) {
            double angle = TAU * rim / RIM_VERTICES;
            positions[rim * TRIANGLE_CORNERS] = (float) (RIM_RADIUS * Math.cos(angle));
            positions[rim * TRIANGLE_CORNERS + 1] = (float) (RIM_RADIUS * Math.sin(angle));
        }
        for (int outer = 0; outer < outerCount; outer++) {
            double angle = TAU * outer / outerCount;
            int vertex = RIM_VERTICES + outer;
            positions[vertex * TRIANGLE_CORNERS] = (float) (OUTER_RADIUS * Math.cos(angle));
            positions[vertex * TRIANGLE_CORNERS + 1] = (float) (OUTER_RADIUS * Math.sin(angle));
        }
        int[] faces = new int[RIM_VERTICES * (OUTER_PER_RIM + 1) * TRIANGLE_CORNERS];
        int faceCount = 0;
        for (int rim = 0; rim < RIM_VERTICES; rim++) {
            int nextRim = (rim + 1) % RIM_VERTICES;
            for (int step = 0; step < OUTER_PER_RIM; step++) {
                int outer = RIM_VERTICES + (rim * OUTER_PER_RIM + step) % outerCount;
                int nextOuter = RIM_VERTICES + (rim * OUTER_PER_RIM + step + 1) % outerCount;
                faceCount = appendFace(faces, faceCount, rim, outer, nextOuter);
            }
            int lastOuter = RIM_VERTICES + (rim * OUTER_PER_RIM + OUTER_PER_RIM) % outerCount;
            faceCount = appendFace(faces, faceCount, rim, lastOuter, nextRim);
        }
        return GeometryBundle.ofMesh(new ArrayMesh(positions, null, faces, TRIANGLE_CORNERS));
    }
}

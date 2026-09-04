package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A hand-built T-mesh on a torus, carrying the configuration LCBK19 Figure 9 walks
 * through: a non-simple zero-patch with a T-joint on a non-zero side, a row of zero arcs,
 * and two simple zero-patches.
 *
 * <p>It is built rather than traced on purpose. Reaching this configuration through the
 * real pipeline would make the test depend on the cross field, the tracer and the integer
 * program, so a failure would say something broke without saying what — and the expected
 * numbers would have to be read off whatever the pipeline happened to produce rather than
 * derived. Here every quantized length is chosen, so the operators are exercised in
 * isolation from everything upstream of them.
 *
 * <p><b>Why three horizontal loops and not two.</b> The middle row is the zero row, and
 * collapsing its arcs merges the nodes at its two ends. With only two horizontal loops
 * each vertical loop of the torus is cut into just two arcs, so merging across one of them
 * turns the other into a loop of positive length — a degenerate state no real quantization
 * produces, and one the operators are entitled to refuse. A third loop leaves a bigon
 * instead, which is exactly the state operator (3) exists to consume.
 *
 * <pre>
 *   minor 4  o-------------------o-------------------o     h2 (wraps)
 *            |     row C, h=1    |                   |
 *   minor 2  o---------o---------o-------------------o     h1 (wraps)
 *            |  row B, h=0  (zero row)               |
 *   minor 0  o---------o---------o-------------------o     h0 (wraps)
 *          major 0     2         4                   8
 * </pre>
 *
 * <p>The stub vertical at major 2 crosses only the bottom row, so it stops on h0 and h1 —
 * and a vertical that stops is a T-junction. The zero row's leftmost patch spans majors 0
 * to 4 and therefore has two arcs along its bottom against one along its top: three
 * non-zero arcs, which is what makes it non-simple.
 */
class EmbeddedTMeshTest {

    /** Face divisions the long way around the torus. */
    private static final int MAJOR_SEGMENTS = 12;

    /** Face divisions around the tube. */
    private static final int MINOR_SEGMENTS = 8;

    /** A torus is genus 1, so V - E + F is zero for any cell decomposition of it. */
    private static final int TORUS_EULER_CHARACTERISTIC = 0;

    /** Minor coordinate of the lowest horizontal loop. */
    private static final int LOOP_BOTTOM = 0;

    /** Minor coordinate of the middle horizontal loop. */
    private static final int LOOP_MIDDLE = 2;

    /** Minor coordinate of the highest horizontal loop. */
    private static final int LOOP_TOP = 4;

    /** Quantized height of the bottom row, the one the stub vertical crosses. */
    private static final int BOTTOM_ROW_HEIGHT = 2;

    /** Quantized height of the middle row, which is what makes its patches zero-patches. */
    private static final int ZERO_ROW_HEIGHT = 0;

    /** Quantized height of the top row. */
    private static final int TOP_ROW_HEIGHT = 1;

    /** Quantized width of the column spanning majors 4 to 8. */
    private static final int WIDE_COLUMN = 3;

    /** Quantized width of the column spanning majors 8 back to 0. */
    private static final int WRAP_COLUMN = 2;

    /** Quantized width of the column spanning majors 0 to 4, which the stub halves. */
    private static final int SPLIT_COLUMN = 2;

    /** Nodes the T-mesh has, over all three horizontal loops. */
    private static final int EXPECTED_NODES = 11;

    /** Arcs the T-mesh has. */
    private static final int EXPECTED_ARCS = 21;

    /** Patches the T-mesh has: four in the bottom row, three in each of the other two. */
    private static final int EXPECTED_PATCHES = 10;

    private EmbeddedMeshTopology topology;
    private ArcNetwork tmesh;

    /** Node id at each (major, minor) grid position that carries one. */
    private final Map<Long, Integer> nodeAt = new HashMap<>();

    @Test
    void handBuiltTMeshIsACellDecompositionOfTheTorus() {
        build();

        assertEquals(EXPECTED_NODES, tmesh.nodes.size(), "nodes");
        assertEquals(EXPECTED_ARCS, tmesh.arcs.size(), "arcs");
        assertEquals(EXPECTED_PATCHES, tmesh.patches.size(), "patches");

        // Throws unless V - E + F equals the torus's characteristic, unless every patch
        // closes, and unless every patch's opposite sides carry equal quantized length.
        tmesh.validate();
    }

    @Test
    void theZeroRowIsThreeZeroPatchesAndTheOtherRowsAreNone() {
        build();

        List<Integer> zeroPatches = zeroPatches();
        assertEquals(3, zeroPatches.size(),
                "the middle row is quantized to height zero, so all three of its patches"
                        + " have zero parametric area");
        for (int patchId : zeroPatches) {
            assertEquals(0, tmesh.sideQuantizedLength(patchId, 1),
                    "a zero-patch's height must be zero on one side");
            assertEquals(0, tmesh.sideQuantizedLength(patchId, 3), "and on the other");
        }
    }

    /**
     * The classification operator (2) turns on. LCBK19: a zero-patch is non-simple "if more
     * than two non-zero arcs are involved, i.e. if there are flat arcs, corresponding to
     * T-joints along the patch's non-zero sides". Counting arcs rather than sides is what
     * sees it: the zero-patch spanning majors 0 to 4 has two arcs along its bottom, because
     * the stub vertical stops in the middle of that side, and one along its top.
     */
    @Test
    void theZeroPatchWithATJointIsNonSimpleAndTheOthersAreSimple() {
        build();

        int nonSimple = 0;
        int simple = 0;
        for (int patchId : zeroPatches()) {
            int nonZeroArcs = tmesh.nonZeroArcCount(patchId);
            if (nonZeroArcs > 2) {
                nonSimple++;
                assertEquals(3, nonZeroArcs,
                        "two arcs along the T-jointed side, one along the side opposite");
            } else {
                simple++;
                assertEquals(2, nonZeroArcs, "a simple zero-patch has exactly two non-zero arcs");
            }
        }
        assertEquals(1, nonSimple, "exactly one zero-patch carries the T-joint");
        assertEquals(2, simple, "the other two zero-patches are already simple");
    }

    /**
     * A T-junction is a node that some patch's side runs flat through. The stub vertical
     * stops on the two horizontal loops it reaches, so those two nodes have three arcs,
     * while every node a vertical loop passes straight through has four.
     */
    @Test
    void theStubVerticalCreatesExactlyTwoDegreeThreeNodes() {
        build();

        int degreeThree = 0;
        int degreeFour = 0;
        for (int nodeId = 0; nodeId < tmesh.nodes.size(); nodeId++) {
            int degree = tmesh.degree(nodeId);
            if (degree == 3) {
                degreeThree++;
            } else if (degree == 4) {
                degreeFour++;
            } else {
                throw new AssertionError("node " + nodeId + " has unexpected degree " + degree);
            }
        }
        assertEquals(2, degreeThree, "the stub vertical stops on each loop it reaches");
        assertEquals(EXPECTED_NODES - 2, degreeFour, "every other node is passed straight through");
    }

    /**
     * Collapsing a zero arc must never leave a positive arc joining a node to itself. That
     * is the state this layout was reshaped to avoid, and it is worth asserting rather than
     * trusting: the two nodes at the ends of each zero arc must have a second, longer route
     * between them, so that merging them leaves a bigon and not a loop.
     */
    @Test
    void noZeroArcJoinsTwoNodesThatOnlyItConnects() {
        build();

        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).quantizedLength != 0) {
                continue;
            }
            int start = tmesh.arcs.get(arcId).startNodeId;
            int end = tmesh.arcs.get(arcId).endNodeId;
            int routes = 0;
            for (int otherId : tmesh.arcEndsByNode.get(start)) {
                if (tmesh.arcs.get(otherId).otherNode(start) == end) {
                    routes++;
                }
            }
            assertEquals(1, routes, "zero arc " + arcId + " must be the only arc between nodes "
                    + start + " and " + end + ", or collapsing it would create a loop");
        }
    }

    /**
     * Opposite sides are walked in opposite directions around a patch's boundary, so an
     * offset measured from the start of one is measured from the end of the other. This is
     * the formula operator (2) and the T-junction extension both use to find "the
     * corresponding point on the opposite side", and getting it backwards connects the
     * extension to the wrong place while still producing something that looks like a layout.
     */
    @Test
    void oppositeOffsetMirrorsAcrossThePatch() {
        build();

        int nonSimplePatch = nonSimpleZeroPatch();
        int width = tmesh.sideQuantizedLength(nonSimplePatch, 0);
        assertEquals(SPLIT_COLUMN, width, "the T-jointed patch spans the split column");
        assertEquals(width, tmesh.oppositeOffset(nonSimplePatch, 0, 0),
                "the start of one side faces the end of the other");
        assertEquals(0, tmesh.oppositeOffset(nonSimplePatch, 0, width),
                "and the end faces the start");
        assertEquals(1, tmesh.oppositeOffset(nonSimplePatch, 0, 1),
                "the T-joint sits at offset 1, and faces offset 1 on the opposite side");
    }

    /**
     * Consistency — CBK15's "the parametric lengths of the edges on opposite sides sum up to
     * the same value" — is what makes a patch a rectangle in parameter space at all. An
     * operator that changed one side without its opposite would leave the T-mesh describing
     * a shape that cannot be mapped to a rectangle, so validate must refuse it.
     */
    @Test
    void validateRejectsAPatchWhoseOppositeSidesDisagree() {
        build();
        tmesh.validate();

        tmesh.arcs.get(0).quantizedLength += 1;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tmesh.validate());
        assertTrue(failure.getMessage().contains("not a rectangle"),
                "expected the consistency check to fire, got: " + failure.getMessage());
    }

    /**
     * The Euler check is the one that catches structural corruption, so it is worth proving
     * it actually fires rather than trusting that it would.
     */
    @Test
    void validateRejectsALostArc() {
        build();
        tmesh.validate();

        tmesh.arcs.get(0).alive = false;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tmesh.validate());
        assertTrue(failure.getMessage().contains("cell decomposition"),
                "expected the Euler check to fire, got: " + failure.getMessage());
    }

    /**
     * A patch is a zero-patch only when the quantization gives it no area, never merely
     * because it is thin on the surface. The bottom row's patches are two units high and
     * must never be offered to the collapse operators, however narrow they look.
     */
    @Test
    void patchesWithPositiveAreaAreNotZeroPatches() {
        build();
        for (int patchId = 0; patchId < 4; patchId++) {
            assertFalse(tmesh.isZeroPatch(patchId),
                    "patch " + patchId + " is in the bottom row and has positive area");
        }
    }

    /**
     * Splitting an arc is how operator (2) and the T-junction extension insert a node onto
     * an existing side, and MPZ14 §7.3 is explicit that the two halves must carry the two
     * halves of the parent's path: no new geometry is invented, so both patches bordering
     * the arc see the split at the same vertex, and the boundary they share stays one curve.
     * That is the property the whole layout's watertightness rests on.
     */
    @Test
    void splittingAnArcHandsEachHalfHalfOfThePath() {
        build();
        int arcId = arcBetween(4, LOOP_BOTTOM, 8, LOOP_BOTTOM);
        List<Integer> parentPath = new ArrayList<>(tmesh.arcs.get(arcId).path.copyVertexPath);
        int leftPatch = tmesh.arcs.get(arcId).leftPatchId;
        int rightPatch = tmesh.arcs.get(arcId).rightPatchId;

        int[] halves = tmesh.splitArc(arcId, 1, 2);

        assertFalse(tmesh.arcs.get(arcId).alive, "the parent arc is retired");
        assertEquals(1, tmesh.arcs.get(halves[0]).quantizedLength, "the first half takes the offset");
        assertEquals(WIDE_COLUMN - 1, tmesh.arcs.get(halves[1]).quantizedLength,
                "the second half takes the remainder");
        assertEquals(parentPath.subList(0, 3), tmesh.arcs.get(halves[0]).path.copyVertexPath,
                "the first half is exactly the first part of the parent's path");
        assertEquals(parentPath.subList(2, parentPath.size()),
                tmesh.arcs.get(halves[1]).path.copyVertexPath,
                "the second half is exactly the rest of it, sharing the split vertex");

        int splitNode = tmesh.arcs.get(halves[0]).endNodeId;
        assertEquals(parentPath.get(2), tmesh.nodes.get(splitNode).copyVertex,
                "the new node sits on the vertex the path was cut at");
        assertEquals(leftPatch, tmesh.arcs.get(halves[0]).leftPatchId, "halves inherit the patches");
        assertEquals(rightPatch, tmesh.arcs.get(halves[1]).rightPatchId, "on both sides");

        // A node and an arc are both gained, so the characteristic is unmoved: the split
        // refines the complex without changing the surface it decomposes.
        tmesh.validate();

        // Degree two, not three. A freshly split node carries only the two halves of the
        // arc it was cut from, so it is flat on BOTH of the patches that share that arc —
        // a pure subdivision point, not yet a T-junction. It becomes one only when the arc
        // that motivated the split lands on it and gives it a third end. This is why a
        // T-junction cannot be defined as "a node of degree three": that would catch a
        // valence-3 singularity, which is a corner of every patch around it, and miss this
        // node, which is a corner of none.
        assertEquals(2, tmesh.degree(splitNode),
                "a node inserted by a split is flat on both sides until something lands on it");
    }

    /**
     * Both patches bordering the split arc must see it, in the order each of them walks the
     * side. They walk it in opposite directions, so one gets the halves in the parent's
     * order and the other gets them reversed — and a patch that kept the parent's id, or
     * took the halves the wrong way round, would leave the boundary describing a path that
     * does not exist.
     */
    @Test
    void splittingAnArcUpdatesBothPatchesInTheirOwnWalkingOrder() {
        build();
        int arcId = arcBetween(4, LOOP_BOTTOM, 8, LOOP_BOTTOM);
        int leftPatch = tmesh.arcs.get(arcId).leftPatchId;
        int rightPatch = tmesh.arcs.get(arcId).rightPatchId;

        int[] halves = tmesh.splitArc(arcId, 1, 2);

        List<Integer> leftSide = sideContaining(leftPatch, halves[0]);
        List<Integer> rightSide = sideContaining(rightPatch, halves[0]);
        assertEquals(List.of(halves[0], halves[1]), leftSide,
                "the patch walking the arc forwards sees the halves in the parent's order");
        assertEquals(List.of(halves[1], halves[0]), rightSide,
                "the patch walking it backwards sees them reversed");
    }

    /**
     * The arc joining two grid positions, whichever way round it runs.
     *
     * @param fromMajor major coordinate of one end
     * @param fromMinor minor coordinate of one end
     * @param toMajor   major coordinate of the other end
     * @param toMinor   minor coordinate of the other end
     * @return the arc's id
     */
    private int arcBetween(int fromMajor, int fromMinor, int toMajor, int toMinor) {
        int from = nodeAt.get(key(fromMajor, fromMinor));
        int to = nodeAt.get(key(toMajor, toMinor));
        for (int arcId : tmesh.arcEndsByNode.get(from)) {
            if (tmesh.arcs.get(arcId).otherNode(from) == to) {
                return arcId;
            }
        }
        throw new AssertionError("no arc between the given grid positions");
    }

    /**
     * The side of a patch that carries an arc, as a list of arc ids in walking order.
     *
     * @param patchId patch to look in
     * @param arcId   arc to find
     * @return the arcs of the side it lies on
     */
    private List<Integer> sideContaining(int patchId, int arcId) {
        for (List<Integer> side : tmesh.patches.get(patchId).sideArcIds) {
            if (side.contains(arcId)) {
                return side;
            }
        }
        throw new AssertionError("arc " + arcId + " is not on patch " + patchId);
    }

    /** The live zero-patches, in id order. */
    private List<Integer> zeroPatches() {
        List<Integer> found = new ArrayList<>();
        for (int patchId = 0; patchId < tmesh.patches.size(); patchId++) {
            if (tmesh.patches.get(patchId).alive && tmesh.isZeroPatch(patchId)) {
                found.add(patchId);
            }
        }
        return found;
    }

    /** The one zero-patch that carries a T-joint, which is the one operator (2) splits. */
    private int nonSimpleZeroPatch() {
        for (int patchId : zeroPatches()) {
            if (tmesh.nonZeroArcCount(patchId) > 2) {
                return patchId;
            }
        }
        throw new AssertionError("the non-simple zero-patch must exist");
    }

    /** Builds the torus, the working copy over it, and the hand-authored T-mesh. */
    private void build() {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(TorusMeshNode.MAJOR_SEGMENTS.name, MAJOR_SEGMENTS);
        context.setInput(TorusMeshNode.MINOR_SEGMENTS.name, MINOR_SEGMENTS);
        context.setInput(TorusMeshNode.TRIANGULATE.name, true);
        node.evaluate(context);
        HalfEdgeMesh torus = (HalfEdgeMesh) context.getOutput(TorusMeshNode.MESH.name, GeometryBundle.class).mesh();

        topology = new EmbeddedMeshTopology(torus);
        tmesh = new ArcNetwork(topology);

        for (int major : new int[] { 0, 2, 4, 8 }) {
            addNode(major, LOOP_BOTTOM);
            addNode(major, LOOP_MIDDLE);
        }
        for (int major : new int[] { 0, 4, 8 }) {
            addNode(major, LOOP_TOP);
        }

        // The two lower loops carry a node at major 2, where the stub vertical stops.
        int bottom02 = horizontalArc(LOOP_BOTTOM, 0, 2, 1);
        int bottom24 = horizontalArc(LOOP_BOTTOM, 2, 4, 1);
        int bottom48 = horizontalArc(LOOP_BOTTOM, 4, 8, WIDE_COLUMN);
        int bottom80 = horizontalArc(LOOP_BOTTOM, 8, 0, WRAP_COLUMN);
        int middle02 = horizontalArc(LOOP_MIDDLE, 0, 2, 1);
        int middle24 = horizontalArc(LOOP_MIDDLE, 2, 4, 1);
        int middle48 = horizontalArc(LOOP_MIDDLE, 4, 8, WIDE_COLUMN);
        int middle80 = horizontalArc(LOOP_MIDDLE, 8, 0, WRAP_COLUMN);
        int top04 = horizontalArc(LOOP_TOP, 0, 4, SPLIT_COLUMN);
        int top48 = horizontalArc(LOOP_TOP, 4, 8, WIDE_COLUMN);
        int top80 = horizontalArc(LOOP_TOP, 8, 0, WRAP_COLUMN);

        int bottomRow0 = verticalArc(0, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow2 = verticalArc(2, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow4 = verticalArc(4, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow8 = verticalArc(8, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);

        // The zero row: these three are what the collapse operators consume.
        int zeroRow0 = verticalArc(0, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int zeroRow4 = verticalArc(4, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int zeroRow8 = verticalArc(8, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);

        int topRow0 = verticalArc(0, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);
        int topRow4 = verticalArc(4, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);
        int topRow8 = verticalArc(8, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);

        addPatch(2, LOOP_BOTTOM, List.of(bottom02), List.of(bottomRow0), List.of(middle02),
                List.of(bottomRow2));
        addPatch(4, LOOP_BOTTOM, List.of(bottom24), List.of(bottomRow2), List.of(middle24),
                List.of(bottomRow4));
        addPatch(8, LOOP_BOTTOM, List.of(bottom48), List.of(bottomRow4), List.of(middle48),
                List.of(bottomRow8));
        addPatch(0, LOOP_BOTTOM, List.of(bottom80), List.of(bottomRow8), List.of(middle80),
                List.of(bottomRow0));

        // The non-simple zero-patch: two arcs along its bottom, one along its top.
        addPatch(4, LOOP_MIDDLE, List.of(middle24, middle02), List.of(zeroRow0), List.of(top04),
                List.of(zeroRow4));
        addPatch(8, LOOP_MIDDLE, List.of(middle48), List.of(zeroRow4), List.of(top48),
                List.of(zeroRow8));
        addPatch(0, LOOP_MIDDLE, List.of(middle80), List.of(zeroRow8), List.of(top80),
                List.of(zeroRow0));

        addPatch(4, LOOP_TOP, List.of(top04), List.of(topRow0), List.of(bottom02, bottom24),
                List.of(topRow4));
        addPatch(8, LOOP_TOP, List.of(top48), List.of(topRow4), List.of(bottom48),
                List.of(topRow8));
        addPatch(0, LOOP_TOP, List.of(top80), List.of(topRow8), List.of(bottom80),
                List.of(topRow0));
    }

    /**
     * Registers a T-mesh node at a grid position of the torus.
     *
     * @param major major grid coordinate
     * @param minor minor grid coordinate
     */
    private void addNode(int major, int minor) {
        int nodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(major, minor), false, false);
        nodeAt.put(key(major, minor), nodeId);
    }

    /**
     * Adds an arc running the long way around the torus at a fixed minor coordinate.
     *
     * @param minor           minor coordinate the arc runs along
     * @param fromMajor       major coordinate the arc starts at
     * @param toMajor         major coordinate the arc ends at, possibly through the wrap
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int horizontalArc(int minor, int fromMajor, int toMajor, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        int major = fromMajor;
        path.add(copyVertex(major, minor));
        while (major != toMajor) {
            major = (major + 1) % MAJOR_SEGMENTS;
            path.add(copyVertex(major, minor));
        }
        return tmesh.addArc(ArcNetwork.NONE, nodeAt.get(key(fromMajor, minor)),
                nodeAt.get(key(toMajor, minor)), quantizedLength, false, path);
    }

    /**
     * Adds an arc running around the tube at a fixed major coordinate.
     *
     * @param major           major coordinate the arc runs along
     * @param fromMinor       minor coordinate the arc starts at
     * @param toMinor         minor coordinate the arc ends at, possibly through the wrap
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int verticalArc(int major, int fromMinor, int toMinor, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        int minor = fromMinor;
        path.add(copyVertex(major, minor));
        while (minor != toMinor) {
            minor = (minor + 1) % MINOR_SEGMENTS;
            path.add(copyVertex(major, minor));
        }
        return tmesh.addArc(ArcNetwork.NONE, nodeAt.get(key(major, fromMinor)),
                nodeAt.get(key(major, toMinor)), quantizedLength, false, path);
    }

    /**
     * Adds a patch, given its four sides walked counter-clockwise seen from outside,
     * interior on the left, from its bottom-right corner: bottom walked major-down, then
     * left, top and right.
     *
     * @param cornerMajor major coordinate of the corner the walk starts at
     * @param cornerMinor minor coordinate of the corner the walk starts at
     * @param bottom      arcs of the side walked first
     * @param left        arcs of the side walked second
     * @param top         arcs of the side walked third
     * @param right       arcs of the side walked fourth
     */
    private void addPatch(int cornerMajor, int cornerMinor, List<Integer> bottom,
            List<Integer> left, List<Integer> top, List<Integer> right) {
        tmesh.addPatch(ArcNetwork.NONE, List.of(bottom, left, top, right),
                nodeAt.get(key(cornerMajor, cornerMinor)));
    }

    /**
     * The working copy's vertex at a grid position of the torus. The primitive adds vertices
     * major-outer and minor-inner, so the source vertex id follows directly.
     *
     * @param major major grid coordinate
     * @param minor minor grid coordinate
     * @return the copy vertex there
     */
    private int copyVertex(int major, int minor) {
        return topology.copyVertexForSourceVertexId(major * MINOR_SEGMENTS + minor);
    }

    /**
     * A map key for a grid position.
     *
     * @param major major grid coordinate
     * @param minor minor grid coordinate
     * @return the packed key
     */
    private static long key(int major, int minor) {
        return ((long) major << Integer.SIZE) | minor;
    }
}

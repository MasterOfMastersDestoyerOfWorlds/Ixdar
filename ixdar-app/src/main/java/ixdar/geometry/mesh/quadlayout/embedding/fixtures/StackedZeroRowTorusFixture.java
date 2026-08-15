package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * {@link TorusLayoutFixture} with a second zero row (minor 4 to 6) stacked on its first, so the
 * non-simple zero-patch's opposite side is shared with another zero-patch instead of an ordinary
 * one. The stub vertical at major 2 still makes the lower row non-simple.
 *
 * <p>See also: LCBK19 Section 6.1, Appendix A.3
 */
public final class StackedZeroRowTorusFixture implements LayoutFixture {

    /** Face divisions the long way around the torus. */
    public static final int MAJOR_SEGMENTS = 12;

    /** Face divisions around the tube. */
    public static final int MINOR_SEGMENTS = 8;

    /** A torus is genus 1, so V - E + F is zero for any cell decomposition of it. */
    public static final int TORUS_EULER_CHARACTERISTIC = 0;

    /** Minor coordinate of the lowest horizontal loop. */
    private static final int LOOP_BOTTOM = 0;

    /** Minor coordinate of the loop below both zero rows. */
    private static final int LOOP_MIDDLE = 2;

    /** Minor coordinate of the loop between the two zero rows. */
    private static final int LOOP_TOP = 4;

    /** Minor coordinate of the loop above both zero rows. */
    private static final int LOOP_ROOF = 6;

    /** Quantized height of the bottom row, the one the stub vertical crosses. */
    private static final int BOTTOM_ROW_HEIGHT = 2;

    /** Quantized height of the two stacked rows that are zero-patches. */
    private static final int ZERO_ROW_HEIGHT = 0;

    /** Quantized height of the roof row, which closes the tube. */
    private static final int ROOF_ROW_HEIGHT = 1;

    /** Quantized width of the column spanning majors 4 to 8. */
    private static final int WIDE_COLUMN = 3;

    /** Quantized width of the column spanning majors 8 back to 0. */
    private static final int WRAP_COLUMN = 2;

    /** Quantized width of the column spanning majors 0 to 4, which the stub halves. */
    private static final int SPLIT_COLUMN = 2;

    public HalfEdgeMesh torus;
    public EmbeddedMeshTopology topology;
    public EmbeddedTMesh tmesh;

    /** The non-simple zero-patch of the lower zero row, the one operator (2) must split. */
    public int nonSimplePatchId;

    /** The simple zero-patch stacked directly above it, sharing the arc that gets split. */
    public int stackedPatchId;

    /** Node id at each (major, minor) grid position that carries one. */
    private final Map<Long, Integer> nodeAt = new HashMap<>();

    /**
     * Builds the torus, the working copy over it, and the hand-authored T-mesh.
     */
    public StackedZeroRowTorusFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Stacked zero row torus";
    }

    @Override
    public EmbeddedTMesh build() {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(TorusMeshNode.MAJOR_SEGMENTS_2, MAJOR_SEGMENTS);
        context.setInput(TorusMeshNode.MINOR_SEGMENTS_2, MINOR_SEGMENTS);
        context.setInput(TorusMeshNode.TRIANGULATE_2, true);
        node.evaluate(context);
        this.torus = context.getOutput(TorusMeshNode.MESH_2, HalfEdgeMesh.class);
        this.topology = new EmbeddedMeshTopology(torus);
        this.tmesh = new EmbeddedTMesh(topology);
        nodeAt.clear();
        layOutTMesh();
        return tmesh;
    }

    /**
     * The node id at a grid position, for callers that want to reach into the fixture.
     *
     * @param major major grid coordinate
     * @param minor minor grid coordinate
     * @return the node id there
     */
    public int nodeIdAt(int major, int minor) {
        return nodeAt.get(key(major, minor));
    }

    /**
     * Lays the nodes, arcs and patches onto the torus.
     */
    private void layOutTMesh() {
        for (int major : new int[] { 0, 2, 4, 8 }) {
            addNode(major, LOOP_BOTTOM);
            addNode(major, LOOP_MIDDLE);
        }
        for (int major : new int[] { 0, 4, 8 }) {
            addNode(major, LOOP_TOP);
            addNode(major, LOOP_ROOF);
        }

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
        int roof04 = horizontalArc(LOOP_ROOF, 0, 4, SPLIT_COLUMN);
        int roof48 = horizontalArc(LOOP_ROOF, 4, 8, WIDE_COLUMN);
        int roof80 = horizontalArc(LOOP_ROOF, 8, 0, WRAP_COLUMN);

        int bottomRow0 = verticalArc(0, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow2 = verticalArc(2, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow4 = verticalArc(4, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow8 = verticalArc(8, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);

        int lowerZero0 = verticalArc(0, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int lowerZero4 = verticalArc(4, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int lowerZero8 = verticalArc(8, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);

        int upperZero0 = verticalArc(0, LOOP_TOP, LOOP_ROOF, ZERO_ROW_HEIGHT);
        int upperZero4 = verticalArc(4, LOOP_TOP, LOOP_ROOF, ZERO_ROW_HEIGHT);
        int upperZero8 = verticalArc(8, LOOP_TOP, LOOP_ROOF, ZERO_ROW_HEIGHT);

        int roofRow0 = verticalArc(0, LOOP_ROOF, LOOP_BOTTOM, ROOF_ROW_HEIGHT);
        int roofRow4 = verticalArc(4, LOOP_ROOF, LOOP_BOTTOM, ROOF_ROW_HEIGHT);
        int roofRow8 = verticalArc(8, LOOP_ROOF, LOOP_BOTTOM, ROOF_ROW_HEIGHT);

        addPatch(0, LOOP_BOTTOM, List.of(bottom02), List.of(bottomRow2), List.of(middle02),
                List.of(bottomRow0));
        addPatch(2, LOOP_BOTTOM, List.of(bottom24), List.of(bottomRow4), List.of(middle24),
                List.of(bottomRow2));
        addPatch(4, LOOP_BOTTOM, List.of(bottom48), List.of(bottomRow8), List.of(middle48),
                List.of(bottomRow4));
        addPatch(8, LOOP_BOTTOM, List.of(bottom80), List.of(bottomRow0), List.of(middle80),
                List.of(bottomRow8));

        nonSimplePatchId = addPatch(0, LOOP_MIDDLE, List.of(middle02, middle24),
                List.of(lowerZero4), List.of(top04), List.of(lowerZero0));
        addPatch(4, LOOP_MIDDLE, List.of(middle48), List.of(lowerZero8), List.of(top48),
                List.of(lowerZero4));
        addPatch(8, LOOP_MIDDLE, List.of(middle80), List.of(lowerZero0), List.of(top80),
                List.of(lowerZero8));

        stackedPatchId = addPatch(0, LOOP_TOP, List.of(top04), List.of(upperZero4),
                List.of(roof04), List.of(upperZero0));
        addPatch(4, LOOP_TOP, List.of(top48), List.of(upperZero8), List.of(roof48),
                List.of(upperZero4));
        addPatch(8, LOOP_TOP, List.of(top80), List.of(upperZero0), List.of(roof80),
                List.of(upperZero8));

        addPatch(0, LOOP_ROOF, List.of(roof04), List.of(roofRow4), List.of(bottom24, bottom02),
                List.of(roofRow0));
        addPatch(4, LOOP_ROOF, List.of(roof48), List.of(roofRow8), List.of(bottom48),
                List.of(roofRow4));
        addPatch(8, LOOP_ROOF, List.of(roof80), List.of(roofRow0), List.of(bottom80),
                List.of(roofRow8));
        tmesh.resolveWalkOrientation();
    }

    /**
     * Registers a T-mesh node at a grid position of the torus.
     *
     * @param major major grid coordinate
     * @param minor minor grid coordinate
     */
    private void addNode(int major, int minor) {
        int nodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(major, minor), false, false);
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
        return tmesh.addArc(EmbeddedTMesh.NONE, nodeAt.get(key(fromMajor, minor)),
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
        return tmesh.addArc(EmbeddedTMesh.NONE, nodeAt.get(key(major, fromMinor)),
                nodeAt.get(key(major, toMinor)), quantizedLength, false, path);
    }

    /**
     * Adds a patch, given its four sides walked counter-clockwise from a corner.
     *
     * @param cornerMajor major coordinate of the corner the walk starts at
     * @param cornerMinor minor coordinate of the corner the walk starts at
     * @param bottom      arcs of the side walked first
     * @param right       arcs of the side walked second
     * @param top         arcs of the side walked third
     * @param left        arcs of the side walked fourth
     * @return the new patch's id
     */
    private int addPatch(int cornerMajor, int cornerMinor, List<Integer> bottom,
            List<Integer> right, List<Integer> top, List<Integer> left) {
        return tmesh.addPatch(EmbeddedTMesh.NONE, List.of(bottom, right, top, left),
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

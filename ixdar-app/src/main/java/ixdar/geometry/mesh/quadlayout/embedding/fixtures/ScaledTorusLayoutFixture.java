package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * {@link TorusLayoutFixture}'s layout on a torus refined by an integer scale.
 *
 * <p>Coordinates and arc segment counts multiply by the scale, so the same T-mesh sits on a
 * triangle mesh that many times finer — isolating density from layout.
 *
 * <p>See also: LCBK19 Figure 9
 */
public final class ScaledTorusLayoutFixture implements LayoutFixture {

    /** A torus is genus 1, so V - E + F is zero for any cell decomposition of it. */
    public static final int TORUS_EULER_CHARACTERISTIC = 0;

    /** Base face divisions the long way around the torus, before scaling. */
    private static final int BASE_MAJOR_SEGMENTS = 12;

    /** Base face divisions around the tube, before scaling. */
    private static final int BASE_MINOR_SEGMENTS = 8;

    /** Minor coordinate of the lowest horizontal loop, before scaling. */
    private static final int LOOP_BOTTOM = 0;

    /** Minor coordinate of the middle horizontal loop, before scaling. */
    private static final int LOOP_MIDDLE = 2;

    /** Minor coordinate of the highest horizontal loop, before scaling. */
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

    public final int scale;
    public final int majorSegments;
    public final int minorSegments;
    public HalfEdgeMesh torus;
    public EmbeddedMeshTopology topology;
    public ArcNetwork tmesh;

    /** Node id at each (major, minor) grid position that carries one. */
    private final Map<Long, Integer> nodeAt = new HashMap<>();

    /**
     * Builds the torus at the given refinement scale, the working copy over it, and the T-mesh.
     *
     * @param scale integer refinement, one being {@link TorusLayoutFixture}
     */
    public ScaledTorusLayoutFixture(int scale) {
        this.scale = scale;
        this.majorSegments = BASE_MAJOR_SEGMENTS * scale;
        this.minorSegments = BASE_MINOR_SEGMENTS * scale;
        build();
    }

    @Override
    public String displayName() {
        return "Scaled torus x" + scale;
    }

    @Override
    public ArcNetwork build() {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(TorusMeshNode.MAJOR_SEGMENTS.name, majorSegments);
        context.setInput(TorusMeshNode.MINOR_SEGMENTS.name, minorSegments);
        context.setInput(TorusMeshNode.TRIANGULATE.name, true);
        node.evaluate(context);
        this.torus = context.getOutput(TorusMeshNode.MESH.name, HalfEdgeMesh.class);
        this.topology = new EmbeddedMeshTopology(torus);
        this.tmesh = new ArcNetwork(topology);
        nodeAt.clear();
        layOutTMesh();
        return tmesh;
    }

    /**
     * Lays the nodes, arcs and patches onto the torus, every coordinate scaled up.
     */
    private void layOutTMesh() {
        for (int major : new int[] { 0, 2, 4, 8 }) {
            addNode(major, LOOP_BOTTOM);
            addNode(major, LOOP_MIDDLE);
        }
        for (int major : new int[] { 0, 4, 8 }) {
            addNode(major, LOOP_TOP);
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

        int bottomRow0 = verticalArc(0, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow2 = verticalArc(2, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow4 = verticalArc(4, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);
        int bottomRow8 = verticalArc(8, LOOP_BOTTOM, LOOP_MIDDLE, BOTTOM_ROW_HEIGHT);

        int zeroRow0 = verticalArc(0, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int zeroRow4 = verticalArc(4, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);
        int zeroRow8 = verticalArc(8, LOOP_MIDDLE, LOOP_TOP, ZERO_ROW_HEIGHT);

        int topRow0 = verticalArc(0, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);
        int topRow4 = verticalArc(4, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);
        int topRow8 = verticalArc(8, LOOP_TOP, LOOP_BOTTOM, TOP_ROW_HEIGHT);

        addPatch(0, LOOP_BOTTOM, List.of(bottom02), List.of(bottomRow2), List.of(middle02),
                List.of(bottomRow0));
        addPatch(2, LOOP_BOTTOM, List.of(bottom24), List.of(bottomRow4), List.of(middle24),
                List.of(bottomRow2));
        addPatch(4, LOOP_BOTTOM, List.of(bottom48), List.of(bottomRow8), List.of(middle48),
                List.of(bottomRow4));
        addPatch(8, LOOP_BOTTOM, List.of(bottom80), List.of(bottomRow0), List.of(middle80),
                List.of(bottomRow8));

        addPatch(0, LOOP_MIDDLE, List.of(middle02, middle24), List.of(zeroRow4), List.of(top04),
                List.of(zeroRow0));
        addPatch(4, LOOP_MIDDLE, List.of(middle48), List.of(zeroRow8), List.of(top48),
                List.of(zeroRow4));
        addPatch(8, LOOP_MIDDLE, List.of(middle80), List.of(zeroRow0), List.of(top80),
                List.of(zeroRow8));

        addPatch(0, LOOP_TOP, List.of(top04), List.of(topRow4), List.of(bottom24, bottom02),
                List.of(topRow0));
        addPatch(4, LOOP_TOP, List.of(top48), List.of(topRow8), List.of(bottom48),
                List.of(topRow4));
        addPatch(8, LOOP_TOP, List.of(top80), List.of(topRow0), List.of(bottom80),
                List.of(topRow8));
        tmesh.resolveWalkOrientation();
    }

    /**
     * Registers a T-mesh node at a base grid position, scaled onto the refined torus.
     *
     * @param major base major grid coordinate
     * @param minor base minor grid coordinate
     */
    private void addNode(int major, int minor) {
        int nodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(major, minor), false, false);
        nodeAt.put(key(major, minor), nodeId);
    }

    /**
     * Adds an arc running the long way around the torus, walking every refined vertex between the
     * scaled endpoints.
     *
     * @param minor           base minor coordinate the arc runs along
     * @param fromMajor        base major coordinate the arc starts at
     * @param toMajor          base major coordinate the arc ends at, possibly through the wrap
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int horizontalArc(int minor, int fromMajor, int toMajor, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        int major = fromMajor * scale;
        int end = toMajor * scale;
        path.add(scaledVertex(major, minor * scale));
        while (major != end) {
            major = (major + 1) % majorSegments;
            path.add(scaledVertex(major, minor * scale));
        }
        return tmesh.addArc(ArcNetwork.NONE, nodeAt.get(key(fromMajor, minor)),
                nodeAt.get(key(toMajor, minor)), quantizedLength, false, path);
    }

    /**
     * Adds an arc running around the tube, walking every refined vertex between the scaled endpoints.
     *
     * @param major           base major coordinate the arc runs along
     * @param fromMinor        base minor coordinate the arc starts at
     * @param toMinor          base minor coordinate the arc ends at, possibly through the wrap
     * @param quantizedLength the arc's prescribed parametric length
     * @return the new arc's id
     */
    private int verticalArc(int major, int fromMinor, int toMinor, int quantizedLength) {
        List<Integer> path = new ArrayList<>();
        int minor = fromMinor * scale;
        int end = toMinor * scale;
        path.add(scaledVertex(major * scale, minor));
        while (minor != end) {
            minor = (minor + 1) % minorSegments;
            path.add(scaledVertex(major * scale, minor));
        }
        return tmesh.addArc(ArcNetwork.NONE, nodeAt.get(key(major, fromMinor)),
                nodeAt.get(key(major, toMinor)), quantizedLength, false, path);
    }

    /**
     * Adds a patch, given its four sides walked counter-clockwise from a corner.
     *
     * @param cornerMajor base major coordinate of the corner the walk starts at
     * @param cornerMinor base minor coordinate of the corner the walk starts at
     * @param bottom      arcs of the side walked first
     * @param right       arcs of the side walked second
     * @param top         arcs of the side walked third
     * @param left        arcs of the side walked fourth
     */
    private void addPatch(int cornerMajor, int cornerMinor, List<Integer> bottom,
            List<Integer> right, List<Integer> top, List<Integer> left) {
        tmesh.addPatch(ArcNetwork.NONE, List.of(bottom, right, top, left),
                nodeAt.get(key(cornerMajor, cornerMinor)));
    }

    /**
     * The refined copy vertex at a base grid position.
     *
     * @param major base major grid coordinate
     * @param minor base minor grid coordinate
     * @return the copy vertex there
     */
    private int copyVertex(int major, int minor) {
        return scaledVertex(major * scale, minor * scale);
    }

    /**
     * The copy vertex at a refined grid position.
     *
     * @param major refined major grid coordinate
     * @param minor refined minor grid coordinate
     * @return the copy vertex there
     */
    private int scaledVertex(int major, int minor) {
        return topology.copyVertexForSourceVertexId(major * minorSegments + minor);
    }

    /**
     * A map key for a base grid position.
     *
     * @param major base major grid coordinate
     * @param minor base minor grid coordinate
     * @return the packed key
     */
    private static long key(int major, int minor) {
        return ((long) major << Integer.SIZE) | minor;
    }
}

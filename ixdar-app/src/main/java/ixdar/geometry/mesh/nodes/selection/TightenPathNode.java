package ixdar.geometry.mesh.nodes.selection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Tightens a rough path or loop through authored surface points into a locally shortest geodesic
 * with FlipOut, emitting both the geodesic polyline and the mesh edges nearest it.
 */
@MeshNodeAnnotation(id = "tighten_path")
public class TightenPathNode implements MeshNode {

    /** Edge-marks label the snapped edge cycle is written under unless {@code label} says else. */
    public static final String DEFAULT_MARK_LABEL = "tightened_path";

    /** Coordinates per waypoint in the {@code path} string. */
    public static final int COORDINATES_PER_WAYPOINT = 3;

    /** Waypoints a closed loop needs before its seed walk encloses anything. */
    public static final int CLOSED_LOOP_MINIMUM_WAYPOINTS = 3;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort PATH = new InputPort("path", PortType.STRING, "");
    public static final InputPort CLOSED = new InputPort("closed", PortType.BOOLEAN, false);
    public static final InputPort ITERATIONS = new InputPort("iterations", PortType.INT, -1, -1f,
            1000000f);
    public static final InputPort LABEL = new InputPort("label", PortType.STRING,
            DEFAULT_MARK_LABEL);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, PATH, CLOSED, ITERATIONS, LABEL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Tightens a rough path or loop through authored surface points into a locally "
                + "shortest geodesic by flipping intrinsic edges (Sharp & Crane 2020 FlipOut), "
                + "producing the geodesic polyline and the mesh edges nearest it.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the triangle mesh to tighten on. Output: the same bundle carrying the "
                        + "tightened geodesic as curve geometry and the snapped edge cycle in the "
                        + "edge-marks slot under `label`.",
                PATH.name,
                "Surface waypoints as \"x,y,z; x,y,z; ...\". Each snaps to its nearest vertex and "
                        + "consecutive waypoints are joined by a shortest edge walk to make the "
                        + "seed; two waypoints suffice for an open path, three for a loop.",
                CLOSED.name,
                "If true the seed walks back from the last waypoint to the first and tightens as "
                        + "a loop with no fixed endpoints; if false the two end waypoints are "
                        + "pinned and only the interior straightens.",
                ITERATIONS.name,
                "Cap on wedge straightenings: -1 runs until every wedge is straight, 0 emits the "
                        + "untightened seed walk, and a positive value stops part-way so the "
                        + "tightening can be watched.",
                LABEL.name,
                "Name the snapped edge cycle is stored under in the edge-marks slot, so a seed "
                        + "run and a tightened run of this node can both be kept on one bundle.",
                SELECTION.name,
                "Per-edge BoolField, true on every edge of the conforming cycle nearest the "
                        + "tightened geodesic.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        boolean closed = Boolean.TRUE.equals(ctx.getInput(CLOSED.name, Boolean.class));
        Number iterationsInput = ctx.getInput(ITERATIONS.name, Number.class);
        int iterations = iterationsInput == null ? FlipGeodesics.UNBOUNDED_ITERATIONS
                : Math.max(FlipGeodesics.UNBOUNDED_ITERATIONS, iterationsInput.intValue());
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = DEFAULT_MARK_LABEL;
        }
        int[] waypointVertexIds = waypointVertices(mesh, ctx.getInput(PATH.name, String.class),
                closed);

        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(mesh);
        IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, waypointVertexIds, closed);
        FlipGeodesics flipper = new FlipGeodesics();
        int[] tightened = flipper.shorten(intrinsic, seed, closed, iterations);
        TracedSurfacePath traced = tracer.trace(intrinsic, tightened, closed);
        boolean[] marksByEdgeId = new ConformingLoopSnap().snap(mesh, traced);

        GeometryBundle out = bundle.withSlot(CurveGeometry.SLOT, polyline(traced));
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, marksByEdgeId));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] = edgeId < marksByEdgeId.length && marksByEdgeId[edgeId];
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }

    /**
     * Reads the {@code path} string into the mesh vertices its waypoints snap to.
     *
     * @param mesh   mesh the waypoints are snapped against
     * @param path   waypoint list as {@code "x,y,z; x,y,z; ..."}
     * @param closed whether the caller asked for a loop, which needs one more waypoint
     * @throws IllegalArgumentException when the string is malformed or too short
     * @return mesh vertex ids in waypoint order
     */
    private static int[] waypointVertices(MeshTopology mesh, String path, boolean closed) {
        List<float[]> points = new ArrayList<>();
        if (path != null) {
            for (String chunk : path.split(";")) {
                String trimmed = chunk.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                if (parts.length != COORDINATES_PER_WAYPOINT) {
                    throw new IllegalArgumentException("tighten_path waypoint '" + trimmed
                            + "' needs three comma-separated coordinates");
                }
                points.add(new float[] {
                    Float.parseFloat(parts[0].trim()),
                    Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()) });
            }
        }
        int minimum = closed ? CLOSED_LOOP_MINIMUM_WAYPOINTS : 2;
        if (points.size() < minimum) {
            throw new IllegalArgumentException("tighten_path needs at least " + minimum
                    + " waypoints in `path`, got " + points.size());
        }
        int[] vertexIds = new int[points.size()];
        for (int index = 0; index < vertexIds.length; index++) {
            float[] point = points.get(index);
            vertexIds[index] = NearestVertex.find(mesh, point[0], point[1], point[2]);
        }
        return vertexIds;
    }

    private static CurveGeometry polyline(TracedSurfacePath traced) {
        int pointCount = traced.closed ? traced.pointCount + 1 : traced.pointCount;
        float[] packed = new float[CurveGeometry.NUM_3 * pointCount];
        for (int index = 0; index < pointCount; index++) {
            int source = CurveGeometry.NUM_3 * (index % Math.max(1, traced.pointCount));
            int target = CurveGeometry.NUM_3 * index;
            packed[target] = (float) traced.positions[source];
            packed[target + 1] = (float) traced.positions[source + 1];
            packed[target + 2] = (float) traced.positions[source + 2];
        }
        return CurveGeometry.singlePolyline(packed);
    }
}

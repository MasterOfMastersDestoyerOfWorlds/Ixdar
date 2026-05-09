package ixdar.geometry.mesh.nodes.modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

/**
 * Bridges two boundary edge loops with quad faces.
 * <p>
 * Finds boundary vertices belonging to each tagged group, orders them
 * into loops by walking boundary half-edges, then connects corresponding
 * vertices with quads. Both loops must have the same vertex count.
 * <p>
 * Optionally creates intermediate interpolation rings for smoother transitions.
 */
@MeshNodeAnnotation(id = "bridge_edge_loops")
public class BridgeEdgeLoopsNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String LOOP_A_TAG_2 = "loop_a_tag";
    public static final String LOOP_B_TAG_2 = "loop_b_tag";
    public static final String SEGMENTS_2 = "segments";
    public static final String TWIST_2 = "twist";
    public static final String BRIDGE_EDGE_LOOPS_TAG = "bridge_edge_loops: tag '";
    public static final String NOT_FOUND = "' not found";
    public static final String STR = "=";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LOOP_A_TAG = new InputPort(LOOP_A_TAG_2, PortType.STRING, "");
    private static final InputPort LOOP_B_TAG = new InputPort(LOOP_B_TAG_2, PortType.STRING, "");
    private static final InputPort SEGMENTS = new InputPort(SEGMENTS_2, PortType.INT, 1, 1f, 32f);
    private static final InputPort TWIST = new InputPort(TWIST_2, PortType.INT, 0, -32f, 32f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LOOP_A_TAG, LOOP_B_TAG, SEGMENTS, TWIST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Connects two tagged boundary edge loops of equal vertex count with quad faces, with optional intermediate interpolation rings for smoother transitions.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                GEOMETRY_2, "Input/output. Two tagged boundary loops of EQUAL vertex count are bridged with quads. For unequal counts use adaptive_bridge_loops.",
                LOOP_A_TAG_2, "Tag identifying the first boundary loop.",
                LOOP_B_TAG_2, "Tag identifying the second boundary loop.",
                SEGMENTS_2, "Number of intermediate interpolation rings. 0 = direct bridge; higher = smoother.",
                TWIST_2, "Rotation offset (radians) around the bridge axis when pairing vertices."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        MeshTopology meshTopo = base.mesh();
        if (meshTopo == null) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        String tagA = ctx.getInput(LOOP_A_TAG_2, String.class);
        String tagB = ctx.getInput(LOOP_B_TAG_2, String.class);
        if (tagA == null || tagA.isBlank() || tagB == null || tagB.isBlank()) {
            throw new IllegalArgumentException("bridge_edge_loops: both loop_a_tag and loop_b_tag are required");
        }

        Number segIn = ctx.getInput(SEGMENTS_2, Number.class);
        int segments = Math.max(1, segIn == null ? 1 : segIn.intValue());

        Number twistIn = ctx.getInput(TWIST_2, Number.class);
        int twist = twistIn == null ? 0 : twistIn.intValue();

        Map<String, boolean[]> tags = TagGeometryNode.getTags(base);
        if (tags == null) {
            throw new IllegalArgumentException("bridge_edge_loops: geometry has no tags");
        }
        boolean[] maskA = tags.get(tagA.strip());
        boolean[] maskB = tags.get(tagB.strip());
        if (maskA == null) {
            throw new IllegalArgumentException(BRIDGE_EDGE_LOOPS_TAG + tagA + NOT_FOUND);
        }
        if (maskB == null) {
            throw new IllegalArgumentException(BRIDGE_EDGE_LOOPS_TAG + tagB + NOT_FOUND);
        }

        // Convert to HalfEdgeMesh if needed for boundary walking
        if (!(meshTopo instanceof HalfEdgeMesh)) {
            throw new IllegalArgumentException("bridge_edge_loops: requires HalfEdgeMesh input");
        }
        HalfEdgeMesh mesh = (HalfEdgeMesh) meshTopo;

        // Find boundary loops from tagged vertices
        List<Integer> loopA = findBoundaryLoop(mesh, maskA);
        List<Integer> loopB = findBoundaryLoop(mesh, maskB);

        if (loopA.size() != loopB.size()) {
            throw new IllegalArgumentException("bridge_edge_loops: loops must have same vertex count. "
                    + tagA + STR + loopA.size() + ", " + tagB + STR + loopB.size());
        }

        // Apply twist to loop B alignment
        if (twist != 0) {
            int n = loopB.size();
            int shift = ((twist % n) + n) % n;
            List<Integer> rotated = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rotated.add(loopB.get((i + shift) % n));
            }
            loopB = rotated;
        }

        // Build bridge quads (with optional intermediate rings)
        int loopSize = loopA.size();
        Vector3f posA = new Vector3f();
        Vector3f posB = new Vector3f();

        // Create intermediate vertex rings
        List<int[]> rings = new ArrayList<>();
        // Ring 0 = loopA, ring (segments) = loopB
        int[] ringA = new int[loopSize];
        for (int i = 0; i < loopSize; i++) {
            ringA[i] = loopA.get(i);
        }
        rings.add(ringA);

        for (int s = 1; s < segments; s++) {
            float t = (float) s / segments;
            int[] intermediateRing = new int[loopSize];
            for (int i = 0; i < loopSize; i++) {
                mesh.vertexPosition(loopA.get(i), posA);
                mesh.vertexPosition(loopB.get(i), posB);
                float x = posA.x + (posB.x - posA.x) * t;
                float y = posA.y + (posB.y - posA.y) * t;
                float z = posA.z + (posB.z - posA.z) * t;
                intermediateRing[i] = mesh.addVertex(x, y, z);
            }
            rings.add(intermediateRing);
        }

        int[] ringB = new int[loopSize];
        for (int i = 0; i < loopSize; i++) {
            ringB[i] = loopB.get(i);
        }
        rings.add(ringB);

        // Create quad faces between consecutive rings
        for (int s = 0; s < rings.size() - 1; s++) {
            int[] r0 = rings.get(s);
            int[] r1 = rings.get(s + 1);
            for (int i = 0; i < loopSize; i++) {
                int next = (i + 1) % loopSize;
                mesh.addFace(r0[i], r0[next], r1[next], r1[i]);
            }
        }

        mesh.computeNormals();
        ctx.setOutput(GEOMETRY_2, base.withMesh(mesh));
    }

    /**
     * Find an ordered boundary loop from tagged vertices.
     * Walks boundary half-edges starting from any tagged boundary vertex.
     */
    private static List<Integer> findBoundaryLoop(HalfEdgeMesh mesh, boolean[] tagMask) {
        // Find a tagged vertex that has a boundary edge
        int startVid = -1;
        int nv = mesh.vertexCount();
        for (int vi = 0; vi < nv; vi++) {
            int vid = mesh.vertexIdAt(vi);
            if (vid >= tagMask.length || !tagMask[vid]) continue;

            // Check if this vertex has a boundary half-edge
            int edgeCount = mesh.vertexEdgeCount(vid);
            for (int j = 0; j < edgeCount; j++) {
                int eid = mesh.vertexEdgeAt(vid, j);
                int he = mesh.edgeHalfEdge(eid);
                if (he != MeshTopology.NONE && mesh.halfEdgeTwin(he) == MeshTopology.NONE) {
                    startVid = vid;
                    break;
                }
                // Check the other half-edge direction
                int twin = mesh.halfEdgeTwin(he);
                if (twin != MeshTopology.NONE && mesh.halfEdgeTwin(twin) == MeshTopology.NONE) {
                    startVid = vid;
                    break;
                }
            }
            if (startVid >= 0) break;
        }

        if (startVid < 0) {
            throw new IllegalArgumentException("bridge_edge_loops: no boundary vertices found in tag group");
        }

        // Walk the boundary loop starting from startVid
        List<Integer> loop = new ArrayList<>();
        loop.add(startVid);

        int currentVid = startVid;
        int maxIter = mesh.vertexCount() + 1;
        for (int iter = 0; iter < maxIter; iter++) {
            int nextVid = findNextBoundaryVertex(mesh, currentVid, loop.size() > 1 ? loop.get(loop.size() - 2) : -1);
            if (nextVid < 0 || nextVid == startVid) break;
            loop.add(nextVid);
            currentVid = nextVid;
        }

        return loop;
    }

    /**
     * Find the next vertex along the boundary from currentVid, avoiding prevVid.
     */
    private static int findNextBoundaryVertex(HalfEdgeMesh mesh, int currentVid, int prevVid) {
        int edgeCount = mesh.vertexEdgeCount(currentVid);
        for (int j = 0; j < edgeCount; j++) {
            int eid = mesh.vertexEdgeAt(currentVid, j);
            int he = mesh.edgeHalfEdge(eid);
            if (he == MeshTopology.NONE) continue;

            // Check if this is a boundary edge (one side has no twin)
            boolean isBoundary = mesh.halfEdgeTwin(he) == MeshTopology.NONE;
            if (!isBoundary) {
                int twin = mesh.halfEdgeTwin(he);
                isBoundary = twin != MeshTopology.NONE && mesh.halfEdgeTwin(twin) == MeshTopology.NONE;
            }
            if (!isBoundary) continue;

            // Get the other vertex of this edge
            int startV = mesh.halfEdgeVertex(he);
            int endV = mesh.halfEdgeEndVertex(he);
            int otherVid = (startV == currentVid) ? endV : startV;

            if (otherVid != prevVid && otherVid >= 0) {
                return otherVid;
            }
        }
        return -1;
    }
}

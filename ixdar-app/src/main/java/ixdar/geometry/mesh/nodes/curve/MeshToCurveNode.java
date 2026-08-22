package ixdar.geometry.mesh.nodes.curve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "mesh_to_curve")
public class MeshToCurveNode implements MeshNode {
    public static final String ALL_EDGES = "ALL_EDGES";
    public static final String CURVE = "_curve";
    public static final int NUM_6 = 6;
    public static final int NUM_8 = 8;
    public static final int NUM_16 = 16;
    public static final int NUM_3 = 3;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SOURCE = new InputPort("source", PortType.STRING, ALL_EDGES);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Extracts curve geometry from a mesh, either as individual edge segments (ALL_EDGES) or as an ordered boundary loop (BOUNDARY).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                // Shared input+output key — input is the source mesh, output is the extracted curve bundle.
                GEOMETRY.name, "Input: source mesh to extract curves from. Output: extracted curve geometry bundle (polylines).",
                SOURCE.name, "Extraction mode: ALL_EDGES (every mesh edge as a 2-point segment) or BOUNDARY (ordered polyline around each open boundary)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SOURCE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput(GEOMETRY.name, Object.class));
        if (gb == null) {
            ctx.setOutput(GEOMETRY.name,GeometryBundle.empty());
            return;
        }
        MeshTopology mesh = gb.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput(GEOMETRY.name,gb.withSlot(CURVE, CurveGeometry.singlePolyline(new float[0])));
            return;
        }

        String source = ctx.getInput(SOURCE.name, String.class);
        if (source == null) {
            source = ALL_EDGES;
        }
        if ("BOUNDARY".equalsIgnoreCase(source.trim())) {
            CurveGeometry boundary = boundaryPolyline(mesh);
            if (boundary != null && boundary.pointCount() >= 2) {
                ctx.setOutput(GEOMETRY.name,gb.withSlot(CURVE, boundary));
                return;
            }
        }

        int nSeg = mesh.edgeCount();
        ArrayList<Float> pts = new ArrayList<>(nSeg * NUM_6);
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        for (int ei = 0; ei < nSeg; ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            int va = mesh.halfEdgeVertex(he);
            int vb = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(va, a);
            mesh.vertexPosition(vb, b);
            pts.add(a.x);
            pts.add(a.y);
            pts.add(a.z);
            pts.add(b.x);
            pts.add(b.y);
            pts.add(b.z);
        }
        float[] pos = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            pos[i] = pts.get(i);
        }
        int[] off = new int[nSeg + 1];
        for (int i = 0; i <= nSeg; i++) {
            off[i] = 2 * i;
        }
        CurveGeometry curve = new CurveGeometry(pos, off);
        ctx.setOutput(GEOMETRY.name,gb.withSlot(CURVE, curve));
    }

    /**
     * One ordered loop / polyline along manifold boundary edges. Closed loops repeat the first vertex at
     * the end so downstream nodes (e.g. {@code curve_sweep}) can detect closure.
     */
    private static CurveGeometry boundaryPolyline(MeshTopology mesh) {
        HashMap<Integer, ArrayList<Integer>> adj = new HashMap<>();
        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            if (!mesh.isBoundaryEdge(ei)) {
                continue;
            }
            int he = mesh.edgeHalfEdge(ei);
            int va = mesh.halfEdgeVertex(he);
            int vb = mesh.halfEdgeEndVertex(he);
            adj.computeIfAbsent(va, k -> new ArrayList<>(2)).add(vb);
            adj.computeIfAbsent(vb, k -> new ArrayList<>(2)).add(va);
        }
        if (adj.isEmpty()) {
            return null;
        }
        int start = -1;
        for (Map.Entry<Integer, ArrayList<Integer>> e : adj.entrySet()) {
            if (e.getValue().size() == 1) {
                start = e.getKey();
                break;
            }
        }
        if (start == -1) {
            start = adj.keySet().stream().min(Integer::compareTo).orElse(-1);
        }
        ArrayList<Integer> order = new ArrayList<>();
        int prev = MeshTopology.NONE;
        int cur = start;
        int guard = 0;
        int maxGuard = mesh.vertexCount() * NUM_8 + NUM_16;
        while (true) {
            order.add(cur);
            ArrayList<Integer> nbs = adj.get(cur);
            if (nbs == null) {
                break;
            }
            int next = MeshTopology.NONE;
            for (int nb : nbs) {
                if (nb != prev) {
                    next = nb;
                    break;
                }
            }
            if (next == MeshTopology.NONE) {
                break;
            }
            if (next == start && order.size() > 1) {
                order.add(start);
                break;
            }
            prev = cur;
            cur = next;
            guard++;
            if (guard > maxGuard) {
                break;
            }
        }
        if (order.size() < 2) {
            return null;
        }
        float[] pos = new float[order.size() * NUM_3];
        Vector3f p = new Vector3f();
        for (int i = 0; i < order.size(); i++) {
            mesh.vertexPosition(order.get(i), p);
            int b = NUM_3 * i;
            pos[b] = p.x;
            pos[b + 1] = p.y;
            pos[b + 2] = p.z;
        }
        return CurveGeometry.singlePolyline(pos);
    }
}

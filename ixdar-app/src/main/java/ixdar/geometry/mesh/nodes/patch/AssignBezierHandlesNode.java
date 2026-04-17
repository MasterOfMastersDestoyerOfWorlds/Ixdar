package ixdar.geometry.mesh.nodes.patch;

import java.util.List;

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
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Stores per-edge cubic bezier handle offsets in geometry bundle slots, indexed
 * by edge id: {@code _bezier_handles_start} / {@code _bezier_handles_end} (3
 * floats per edge id slot).
 * <p>
 * Default handle length formula: length
 * {@code edgeLength * QUARTER_CIRCLE_RATIO * weight}, direction opposite the
 * average of other incident edge directions at each endpoint (outward bias).
 */
@MeshNodeAnnotation(id = "assign_bezier_handles")
public class AssignBezierHandlesNode implements MeshNode {

    /**
     * {@code 4 * (sqrt(2) - 1) / 3} — cubic bezier control length for a quarter
     * circle.
     */
    public static final float QUARTER_CIRCLE_RATIO = 0.5523f;

    public static final String SLOT_HANDLES_START = "_bezier_handles_start";
    public static final String SLOT_HANDLES_END = "_bezier_handles_end";

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort WEIGHT = new InputPort("weight", PortType.FLOAT, 1.0f, 0f, 10f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Computes and stores per-edge cubic Bezier handle offsets on a mesh, with handle directions derived from adjacent edge geometry and magnitude scaled by weight.";
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, WEIGHT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Object w = FieldBroadcast.getInputOrDefault(ctx, "weight", WEIGHT.defaultValue());
        float weight = FieldBroadcast.floatScalarOrDefault(w, 1.0f);

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        int maxEdgeId = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(i));
        }
        int slotLen = (maxEdgeId + 1) * 3;
        float[] hStart = new float[slotLen];
        float[] hEnd = new float[slotLen];

        Vector3f meshCenter = meshCenter(mesh);
        Vector3f va = new Vector3f();
        Vector3f vb = new Vector3f();
        Vector3f outDir = new Vector3f();

        for (int i = 0; i < mesh.edgeCount(); i++) {
            int eid = mesh.edgeIdAt(i);
            int he = mesh.edgeHalfEdge(eid);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, va);
            mesh.vertexPosition(v1, vb);
            float edgeLen = va.distance(vb);
            float handleMag = edgeLen * QUARTER_CIRCLE_RATIO * weight;
            if (edgeLen < 1e-20f || handleMag < 1e-20f) {
                continue;
            }

            handleOffsetAtVertex(mesh, eid, v0, meshCenter, handleMag, outDir);
            int o = eid * 3;
            hStart[o] = outDir.x;
            hStart[o + 1] = outDir.y;
            hStart[o + 2] = outDir.z;

            handleOffsetAtVertex(mesh, eid, v1, meshCenter, handleMag, outDir);
            hEnd[o] = outDir.x;
            hEnd[o + 1] = outDir.y;
            hEnd[o + 2] = outDir.z;
        }

        GeometryBundle out = base.withSlot(SLOT_HANDLES_START, hStart).withSlot(SLOT_HANDLES_END, hEnd);
        ctx.setOutput("geometry", out);
    }

    private static Vector3f meshCenter(MeshTopology mesh) {
        Vector3f acc = new Vector3f();
        int n = mesh.vertexCount();
        if (n == 0) {
            return acc;
        }
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            acc.add(p);
        }
        acc.mul(1f / n);
        return acc;
    }

    /**
     * Handle vector at {@code vert} for edge {@code eid} (offset from vertex),
     * length {@code handleMag}.
     */
    private static void handleOffsetAtVertex(
            MeshTopology mesh,
            int eid,
            int vert,
            Vector3f meshCenter,
            float handleMag,
            Vector3f dest) {
        Vector3f vertPos = new Vector3f();
        mesh.vertexPosition(vert, vertPos);

        Vector3f avg = new Vector3f();
        int count = 0;
        Vector3f other = new Vector3f();
        Vector3f dir = new Vector3f();

        int nEdges = mesh.vertexEdgeCount(vert);
        for (int j = 0; j < nEdges; j++) {
            int ej = mesh.vertexEdgeAt(vert, j);
            if (ej == eid) {
                continue;
            }
            int otherVid = otherVertex(mesh, ej, vert);
            mesh.vertexPosition(otherVid, other);
            dir.set(other).sub(vertPos);
            float len = dir.length();
            if (len < 1e-20f) {
                continue;
            }
            dir.mul(1f / len);
            avg.add(dir);
            count++;
        }

        if (count > 0) {
            avg.mul(1f / count);
            if (avg.lengthSquared() > 1e-8f) {
                avg.normalize().mul(-handleMag);
                dest.set(avg);
                return;
            }
        }

        int otherVid = otherVertex(mesh, eid, vert);
        mesh.vertexPosition(otherVid, other);
        dir.set(other).sub(vertPos);
        float el = dir.length();
        if (el < 1e-20f) {
            dest.set(0f, 0f, handleMag);
            return;
        }
        Vector3f edgeDir = new Vector3f(dir).mul(1f / el);

        Vector3f outward = new Vector3f(vertPos).sub(meshCenter);
        if (outward.lengthSquared() < 1e-8f) {
            outward.set(0f, 0f, 1f);
        } else {
            outward.normalize();
        }
        float along = outward.dot(edgeDir);
        Vector3f perp = new Vector3f(outward).sub(new Vector3f(edgeDir).mul(along));
        if (perp.lengthSquared() > 1e-8f) {
            perp.normalize().mul(handleMag);
            dest.set(perp);
            return;
        }

        mesh.vertexNormal(vert, perp);
        if (perp.lengthSquared() > 1e-8f) {
            perp.normalize().mul(handleMag);
            dest.set(perp);
            return;
        }
        dest.set(0f, 0f, handleMag);
    }

    private static int otherVertex(MeshTopology mesh, int eid, int vert) {
        int he = mesh.edgeHalfEdge(eid);
        int a = mesh.halfEdgeVertex(he);
        int b = mesh.halfEdgeEndVertex(he);
        return vert == a ? b : a;
    }
}

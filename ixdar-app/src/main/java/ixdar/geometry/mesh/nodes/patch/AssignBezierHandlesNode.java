package ixdar.geometry.mesh.nodes.patch;

import java.util.Objects;
import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
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
    public static final float MIN_LENGTH = 1e-20f;
    public static final float MIN_LENGTH_SQUARED = 1e-8f;

    public static final float QUARTER_CIRCLE_RATIO = 0.5523f;

    public static final String SLOT_HANDLES_START = "_bezier_handles_start";
    public static final String SLOT_HANDLES_END = "_bezier_handles_end";
    public static final String SLOT_WEIGHT = "_bezier_handle_weight";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort WEIGHT = new InputPort("weight", PortType.FLOAT, 1.0f, 0f, 10f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Computes and stores per-edge cubic Bezier handle offsets on a mesh, with handle directions derived from adjacent edge geometry and magnitude scaled by weight.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input cage / output bundle with _bezier_handles_start, _bezier_handles_end, _bezier_handle_weight slots populated.",
                WEIGHT.name, "Multiplier on the default quarter-circle tangent magnitude. 0 = straight edges (no rounding); 0.33 ≈ gentle; 1.0 ≈ bulging. Stored in the _bezier_handle_weight slot so downstream topology ops (loop_cut, inset, extrude) can rebuild handles consistently."
        );
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
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Object w = FieldBroadcast.getInputOrDefault(ctx, WEIGHT.name, WEIGHT.defaultValue);
        float weight = FieldBroadcast.floatScalarOrDefault(w, 1.0f);
        ctx.setOutput(GEOMETRY.name, computeHandles(base, weight));
    }

    /**
     * Runs the bezier-handle computation over {@code base}'s mesh and returns
     * a new bundle with {@link #SLOT_HANDLES_START}, {@link #SLOT_HANDLES_END}
     * and {@link #SLOT_WEIGHT} populated. Pure math over topology — no
     * dependence on prior slot state. Called directly by topology-modifying
     * nodes (extrude, inset) to rebuild globally consistent handles after they
     * change the mesh.
     *
     * @param base input bundle whose mesh supplies the topology and vertex positions
     * @param weight handle-magnitude multiplier on the default quarter-circle tangent length
     * @return new bundle carrying populated handle/weight slots; returns {@code base} (with weight slot)
     *         when the mesh is null or has no edges
     */
    public static GeometryBundle computeHandles(GeometryBundle base, float weight) {
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            return base.withSlot(SLOT_WEIGHT, weight);
        }

        int maxEdgeId = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(i));
        }
        int slotLen = (maxEdgeId + 1) * 3;
        float[] hStart = new float[slotLen];
        float[] hEnd = new float[slotLen];

        Vector3f meshCenter = meshCenter(mesh);
        Vector3f outDir = new Vector3f();

        for (int i = 0; i < mesh.edgeCount(); i++) {
            int eid = mesh.edgeIdAt(i);
            int he = mesh.edgeHalfEdge(eid);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            float edgeLen = mesh.edgeLength(eid);
            float handleMag = edgeLen * QUARTER_CIRCLE_RATIO * weight;
            if (edgeLen < MIN_LENGTH || handleMag < MIN_LENGTH) {
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

        return base
                .withSlot(SLOT_HANDLES_START, hStart)
                .withSlot(SLOT_HANDLES_END, hEnd)
                .withSlot(SLOT_WEIGHT, weight);
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
     *
     * @param mesh source topology
     * @param eid edge whose handle is being computed
     * @param vert endpoint vertex of {@code eid} the handle is anchored at
     * @param meshCenter precomputed centroid used to bias the fallback direction outward
     * @param handleMag target handle length
     * @param dest receives the handle offset vector (overwritten on every call)
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
            int otherVid = mesh.edgeOtherVertex(ej, vert);
            mesh.vertexPosition(otherVid, other);
            dir.set(other).sub(vertPos);
            float len = dir.length();
            if (len < MIN_LENGTH) {
                continue;
            }
            dir.mul(1f / len);
            avg.add(dir);
            count++;
        }

        if (count > 0) {
            avg.mul(1f / count);
            if (avg.lengthSquared() > MIN_LENGTH_SQUARED) {
                avg.normalize().mul(-handleMag);
                dest.set(avg);
                return;
            }
        }

        int otherVid = mesh.edgeOtherVertex(eid, vert);
        mesh.vertexPosition(otherVid, other);
        dir.set(other).sub(vertPos);
        float el = dir.length();
        if (el < MIN_LENGTH) {
            dest.set(0f, 0f, handleMag);
            return;
        }
        Vector3f edgeDir = new Vector3f(dir).mul(1f / el);

        Vector3f outward = new Vector3f(vertPos).sub(meshCenter);
        if (outward.lengthSquared() < MIN_LENGTH_SQUARED) {
            outward.set(0f, 0f, 1f);
        } else {
            outward.normalize();
        }
        float along = outward.dot(edgeDir);
        Vector3f perp = new Vector3f(outward).sub(new Vector3f(edgeDir).mul(along));
        if (perp.lengthSquared() > MIN_LENGTH_SQUARED) {
            perp.normalize().mul(handleMag);
            dest.set(perp);
            return;
        }

        mesh.vertexNormal(vert, perp);
        if (perp.lengthSquared() > MIN_LENGTH_SQUARED) {
            perp.normalize().mul(handleMag);
            dest.set(perp);
            return;
        }
        dest.set(0f, 0f, handleMag);
    }
}

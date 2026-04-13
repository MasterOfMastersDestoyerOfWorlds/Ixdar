package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Parametric tube segment with dual-axis (X/Y) cross-section control and G1
 * Hermite tangent matching at segment boundaries.
 * <p>
 * Each cross-section slice at parameter t ∈ [0,1] has an elliptical radius:
 * {@code r(θ,t) = √((Rx(t)·cosθ)² + (Ry(t)·sinθ)²)}
 * <p>
 * Rx(t) and Ry(t) are cubic Hermite curves defined by start/end radius and
 * tangent values. Tangent inputs are <b>spatial derivatives</b> (∂r/∂y), internally
 * scaled by segment length for Hermite evaluation. This ensures G1 continuity
 * when chaining segments of different lengths via end→start ports.
 * <p>
 * The tube extends along +Y from y=0 to y=length. Segment resolution is
 * controlled by {@code rings} (slices along length) and {@code segments}
 * (vertices per cross-section ring).
 */
@MeshNodeAnnotation(id = "dual_radial_segment")
public class DualRadialSegmentNode implements MeshNode {

    // Geometry input (optional — for chaining geometry between segments)
    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);

    // Hermite boundary conditions for X-axis radius
    private static final InputPort START_RX = new InputPort("start_rx", PortType.FLOAT, 0.5f, 0.001f, 10f);
    private static final InputPort START_TX = new InputPort("start_tx", PortType.FLOAT, 0.0f, -10f, 10f);
    private static final InputPort END_RX = new InputPort("end_rx", PortType.FLOAT, 0.4f, 0.001f, 10f);
    private static final InputPort END_TX = new InputPort("end_tx", PortType.FLOAT, 0.0f, -10f, 10f);

    // Hermite boundary conditions for Y-axis radius
    private static final InputPort START_RY = new InputPort("start_ry", PortType.FLOAT, 0.5f, 0.001f, 10f);
    private static final InputPort START_TY = new InputPort("start_ty", PortType.FLOAT, 0.0f, -10f, 10f);
    private static final InputPort END_RY = new InputPort("end_ry", PortType.FLOAT, 0.4f, 0.001f, 10f);
    private static final InputPort END_TY = new InputPort("end_ty", PortType.FLOAT, 0.0f, -10f, 10f);

    // Segment geometry parameters
    private static final InputPort LENGTH = new InputPort("length", PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final InputPort RINGS = new InputPort("rings", PortType.INT, 8, (float) 1, (float) 64);
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 12, (float) 3, (float) 128);

    // Geometry output
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    // Pass-through outputs for chaining — next segment reads these as start conditions
    private static final OutputPort OUT_END_RX = new OutputPort("end_rx", PortType.FLOAT);
    private static final OutputPort OUT_END_RY = new OutputPort("end_ry", PortType.FLOAT);
    private static final OutputPort OUT_END_TX = new OutputPort("end_tx", PortType.FLOAT);
    private static final OutputPort OUT_END_TY = new OutputPort("end_ty", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, START_RX, START_TX, END_RX, END_TX,
                START_RY, START_TY, END_RY, END_TY, LENGTH, RINGS, SEGMENTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, OUT_END_RX, OUT_END_RY, OUT_END_TX, OUT_END_TY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        // Read Hermite boundary conditions
        float srx = floatInput(ctx, "start_rx", 0.5f);
        float stx = floatInput(ctx, "start_tx", 0.0f);
        float erx = floatInput(ctx, "end_rx", 0.4f);
        float etx = floatInput(ctx, "end_tx", 0.0f);

        float sry = floatInput(ctx, "start_ry", 0.5f);
        float sty = floatInput(ctx, "start_ty", 0.0f);
        float ery = floatInput(ctx, "end_ry", 0.4f);
        float ety = floatInput(ctx, "end_ty", 0.0f);

        float length = floatInput(ctx, "length", 1.0f);
        int rings = Math.max(2, intInput(ctx, "rings", 8));
        int segments = Math.max(3, intInput(ctx, "segments", 12));

        // Determine chaining context from input geometry
        Object geoIn = ctx.getInput("geometry", Object.class);
        GeometryBundle base = null;
        HalfEdgeMesh mesh = null;
        float yOffset = 0f;
        int[] topRing = null;

        if (geoIn instanceof GeometryBundle gb && gb.mesh() != null && gb.mesh().vertexCount() > 0
                && gb.mesh() instanceof HalfEdgeMesh h) {
            base = gb;
            mesh = h;
            yOffset = findMaxY(mesh);
            topRing = findTopRing(mesh, yOffset, segments);
        }

        if (mesh == null) {
            mesh = new HalfEdgeMesh(
                    rings * segments + 2, 0,
                    (rings - 1) * segments + 2 * segments, 0);
        }

        // Scale spatial tangents (∂r/∂y) to parametric tangents (∂r/∂t) for Hermite.
        // User-facing tangent inputs are spatial derivatives so that chaining segments
        // of different lengths preserves G1 continuity at boundaries.
        float stxParam = stx * length;
        float etxParam = etx * length;
        float styParam = sty * length;
        float etyParam = ety * length;

        // Generate tube directly into the mesh, reusing topRing as ring[0] if chaining
        generateTubeInto(mesh, topRing, yOffset,
                srx, stxParam, erx, etxParam, sry, styParam, ery, etyParam,
                length, rings, segments);
        mesh.computeNormals();

        GeometryBundle result = base != null ? base.withMesh(mesh) : GeometryBundle.ofMesh(mesh);
        ctx.setOutput("geometry", result);

        // Pass through end conditions for chaining
        ctx.setOutput("end_rx", erx);
        ctx.setOutput("end_ry", ery);
        ctx.setOutput("end_tx", etx);
        ctx.setOutput("end_ty", ety);
    }

    /**
     * Generate a tube mesh with Hermite-interpolated elliptical cross-sections.
     * <p>
     * When {@code baseTopRing} is non-null (chaining mode), ring[0] reuses those
     * vertex IDs from the existing mesh instead of creating new vertices. This
     * gives shared vertices at segment boundaries → watertight topology.
     *
     * @param mesh        target mesh (may already contain geometry from prior segments)
     * @param baseTopRing vertex IDs for ring[0] (null if this is the first segment)
     * @param yOffset     Y position where this segment starts (0 if first)
     */
    private static void generateTubeInto(
            HalfEdgeMesh mesh, int[] baseTopRing, float yOffset,
            float srx, float stx, float erx, float etx,
            float sry, float sty, float ery, float ety,
            float length, int rings, int segments) {

        int[][] verts = new int[rings][segments];

        for (int r = 0; r < rings; r++) {
            float t = (float) r / (rings - 1);
            float y = yOffset + t * length;

            float rx = Math.max(0.001f, hermite(t, srx, stx, erx, etx));
            float ry = Math.max(0.001f, hermite(t, sry, sty, ery, ety));

            for (int s = 0; s < segments; s++) {
                // Ring 0 with chaining: reuse existing top ring vertices
                if (r == 0 && baseTopRing != null && s < baseTopRing.length) {
                    verts[0][s] = baseTopRing[s];
                    continue;
                }

                float theta = 2.0f * (float) Math.PI * s / segments;
                float cosT = (float) Math.cos(theta);
                float sinT = (float) Math.sin(theta);

                float denom = (float) Math.sqrt(ry * ry * cosT * cosT + rx * rx * sinT * sinT);
                float radius = (denom > 0.0001f) ? (rx * ry) / denom : (rx + ry) * 0.5f;

                verts[r][s] = mesh.addVertex(radius * cosT, y, radius * sinT);
            }
        }

        // Quad faces between adjacent rings
        for (int r = 0; r < rings - 1; r++) {
            for (int s = 0; s < segments; s++) {
                int ns = (s + 1) % segments;
                mesh.addFace(verts[r][s], verts[r][ns], verts[r + 1][ns], verts[r + 1][s]);
            }
        }
    }

    /**
     * Find the maximum Y coordinate across all active vertices.
     */
    private static float findMaxY(HalfEdgeMesh mesh) {
        Vector3f pos = new Vector3f();
        float maxY = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.vertexPosition(v, pos);
            if (pos.y > maxY) maxY = pos.y;
        }
        return maxY;
    }

    /**
     * Find the ring of vertices at max Y, sorted by angle around the Y axis.
     * Returns vertex IDs in consistent angular order for stitching to a new segment.
     */
    private static int[] findTopRing(HalfEdgeMesh mesh, float maxY, int expectedCount) {
        Vector3f pos = new Vector3f();
        float tolerance = 0.001f;

        // Collect candidates at max Y
        int[] candidates = new int[expectedCount * 2]; // oversize buffer
        float[] angles = new float[candidates.length];
        int found = 0;

        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.vertexPosition(v, pos);
            if (Math.abs(pos.y - maxY) < tolerance && found < candidates.length) {
                candidates[found] = v;
                angles[found] = (float) Math.atan2(pos.z, pos.x);
                found++;
            }
        }

        if (found < 3) return null;

        // Sort by angle (insertion sort — small N)
        for (int i = 1; i < found; i++) {
            float keyAngle = angles[i];
            int keyVert = candidates[i];
            int j = i - 1;
            while (j >= 0 && angles[j] > keyAngle) {
                angles[j + 1] = angles[j];
                candidates[j + 1] = candidates[j];
                j--;
            }
            angles[j + 1] = keyAngle;
            candidates[j + 1] = keyVert;
        }

        // Rotate so index 0 is closest to theta=0 (+X axis).
        // The tube generates vertices with theta = 2π*s/N starting at angle 0,
        // but atan2 sorts from -π to +π. Without rotation, index 0 is at -X
        // and quads twist 180°, collapsing the mesh at segment boundaries.
        int bestIdx = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < found; i++) {
            float dist = Math.abs(angles[i]); // distance from angle 0
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }

        int[] result = new int[found];
        for (int i = 0; i < found; i++) {
            result[i] = candidates[(i + bestIdx) % found];
        }
        return result;
    }

    /**
     * Cubic Hermite interpolation.
     * H(t) = (2t³−3t²+1)·p₀ + (t³−2t²+t)·m₀ + (−2t³+3t²)·p₁ + (t³−t²)·m₁
     */
    private static float hermite(float t, float p0, float m0, float p1, float m1) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0
                + (t3 - 2 * t2 + t) * m0
                + (-2 * t3 + 3 * t2) * p1
                + (t3 - t2) * m1;
    }

    private static float floatInput(NodeContext ctx, String name, float def) {
        Object obj = FieldBroadcast.getInputOrDefault(ctx, name, def);
        return FieldBroadcast.floatScalarOrDefault(obj, def);
    }

    private static int intInput(NodeContext ctx, String name, int def) {
        Number n = ctx.getInput(name, Number.class);
        return n != null ? n.intValue() : def;
    }
}

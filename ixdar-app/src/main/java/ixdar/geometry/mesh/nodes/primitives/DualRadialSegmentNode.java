package ixdar.geometry.mesh.nodes.primitives;

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
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
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
    public static final String GEOMETRY_2 = "geometry";
    public static final String START_RX_2 = "start_rx";
    public static final String START_TX_2 = "start_tx";
    public static final String END_RX_2 = "end_rx";
    public static final String END_TX_2 = "end_tx";
    public static final String START_RY_2 = "start_ry";
    public static final String START_TY_2 = "start_ty";
    public static final String END_RY_2 = "end_ry";
    public static final String END_TY_2 = "end_ty";
    public static final String LENGTH_2 = "length";
    public static final String RINGS_2 = "rings";
    public static final String SEGMENTS_2 = "segments";
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_4 = 0.4f;
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final int NUM_12 = 12;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_001 = 0.001f;
    public static final float NUM_2_0 = 2.0f;
    public static final float NUM_0_0001 = 0.0001f;
    public static final int NUM__2 = -2;

    // Geometry input (optional — for chaining geometry between segments)
    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);

    // Hermite boundary conditions for X-axis radius
    private static final InputPort START_RX = new InputPort(START_RX_2, PortType.FLOAT, 0.5f, 0.001f, 10f);
    private static final InputPort START_TX = new InputPort(START_TX_2, PortType.FLOAT, 0.0f, -10f, 10f);
    private static final InputPort END_RX = new InputPort(END_RX_2, PortType.FLOAT, 0.4f, 0.001f, 10f);
    private static final InputPort END_TX = new InputPort(END_TX_2, PortType.FLOAT, 0.0f, -10f, 10f);

    // Hermite boundary conditions for Y-axis radius
    private static final InputPort START_RY = new InputPort(START_RY_2, PortType.FLOAT, 0.5f, 0.001f, 10f);
    private static final InputPort START_TY = new InputPort(START_TY_2, PortType.FLOAT, 0.0f, -10f, 10f);
    private static final InputPort END_RY = new InputPort(END_RY_2, PortType.FLOAT, 0.4f, 0.001f, 10f);
    private static final InputPort END_TY = new InputPort(END_TY_2, PortType.FLOAT, 0.0f, -10f, 10f);

    // Segment geometry parameters
    private static final InputPort LENGTH = new InputPort(LENGTH_2, PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final InputPort RINGS = new InputPort(RINGS_2, PortType.INT, 8, (float) 1, (float) 64);
    private static final InputPort SEGMENTS = new InputPort(SEGMENTS_2, PortType.INT, 12, (float) 3, (float) 128);

    // Geometry output
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    // Pass-through outputs for chaining — next segment reads these as start conditions
    private static final OutputPort OUT_END_RX = new OutputPort(END_RX_2, PortType.FLOAT);
    private static final OutputPort OUT_END_RY = new OutputPort(END_RY_2, PortType.FLOAT);
    private static final OutputPort OUT_END_TX = new OutputPort(END_TX_2, PortType.FLOAT);
    private static final OutputPort OUT_END_TY = new OutputPort(END_TY_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Generates a parametric tube segment with independent X/Y elliptical radius profiles interpolated via cubic Hermite curves, supporting G1-continuous chaining through geometry and end tangent outputs.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY_2, "Optional upstream GeometryBundle to append to; on the output, the tube mesh (appended if input provided). If input is null, a fresh mesh is created."),
                Map.entry(START_RX_2, "X-axis radius at t=0. Ellipse half-width at the segment start. Default 0.5."),
                Map.entry(START_TX_2, "X-axis radius tangent at t=0 (∂Rx/∂y). Zero = flat at the start; positive = expanding."),
                Map.entry(END_RX_2, "X-axis radius at t=1 on the input side; pass-through value on the output side for G1 chaining into the next segment's start_rx."),
                Map.entry(END_TX_2, "X-axis radius tangent at t=1 (∂Rx/∂y) on the input side; pass-through for chaining on the output side."),
                Map.entry(START_RY_2, "Y-axis radius at t=0. Paired with start_rx for elliptical cross-sections."),
                Map.entry(START_TY_2, "Y-axis radius tangent at t=0 (∂Ry/∂y)."),
                Map.entry(END_RY_2, "Y-axis radius at t=1 on input; pass-through for chaining on output."),
                Map.entry(END_TY_2, "Y-axis radius tangent at t=1 on input; pass-through for chaining on output."),
                Map.entry(LENGTH_2, "Extent along +Y. Segment spans y=0 to y=length."),
                Map.entry(RINGS_2, "Number of cross-section slices along the length. Default 8."),
                Map.entry(SEGMENTS_2, "Vertices per cross-section ring. Higher = smoother. Default 12.")
        );
    }

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
        float srx = floatInput(ctx, START_RX_2, NUM_0_5);
        float stx = floatInput(ctx, START_TX_2, 0.0f);
        float erx = floatInput(ctx, END_RX_2, NUM_0_4);
        float etx = floatInput(ctx, END_TX_2, 0.0f);

        float sry = floatInput(ctx, START_RY_2, NUM_0_5);
        float sty = floatInput(ctx, START_TY_2, 0.0f);
        float ery = floatInput(ctx, END_RY_2, NUM_0_4);
        float ety = floatInput(ctx, END_TY_2, 0.0f);

        float length = floatInput(ctx, LENGTH_2, 1.0f);
        int rings = Math.max(2, intInput(ctx, RINGS_2, NUM_8));
        int segments = Math.max(NUM_3, intInput(ctx, SEGMENTS_2, NUM_12));

        // Determine chaining context from input geometry
        Object geoIn = ctx.getInput(GEOMETRY_2, Object.class);
        GeometryBundle base = null;
        HalfEdgeMesh mesh = null;
        float yOffset = NUM_0;
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
        ctx.setOutput(GEOMETRY_2, result);

        // Pass through end conditions for chaining
        ctx.setOutput(END_RX_2, erx);
        ctx.setOutput(END_RY_2, ery);
        ctx.setOutput(END_TX_2, etx);
        ctx.setOutput(END_TY_2, ety);
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

            float rx = Math.max(NUM_0_001, hermite(t, srx, stx, erx, etx));
            float ry = Math.max(NUM_0_001, hermite(t, sry, sty, ery, ety));

            for (int s = 0; s < segments; s++) {
                // Ring 0 with chaining: reuse existing top ring vertices
                if (r == 0 && baseTopRing != null && s < baseTopRing.length) {
                    verts[0][s] = baseTopRing[s];
                    continue;
                }

                float theta = NUM_2_0 * (float) Math.PI * s / segments;
                float cosT = (float) Math.cos(theta);
                float sinT = (float) Math.sin(theta);

                float denom = (float) Math.sqrt(ry * ry * cosT * cosT + rx * rx * sinT * sinT);
                float radius = (denom > NUM_0_0001) ? (rx * ry) / denom : (rx + ry) * NUM_0_5;

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
        float tolerance = NUM_0_001;

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

        if (found < NUM_3) return null;

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
        return (2 * t3 - NUM_3 * t2 + 1) * p0
                + (t3 - 2 * t2 + t) * m0
                + (NUM__2 * t3 + NUM_3 * t2) * p1
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

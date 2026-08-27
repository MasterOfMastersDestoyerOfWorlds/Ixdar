package ixdar.geometry.mesh.nodes.curve;

import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Converts a curve into a tube mesh by sweeping a circular cross-section along the path.
 * If a profile_curve is provided, its points define the cross-section shape instead.
 * <p>
 * Uses parallel-transported frame for smooth orientation along the curve.
 */
@MeshNodeAnnotation(id = "curve_to_mesh")
public class CurveToMeshNode implements MeshNode {
    public static final float NUM_0_1 = 0.1f;
    public static final int NUM_12 = 12;
    public static final int NUM_3 = 3;
    public static final int NUM_128 = 128;
    public static final float NUM_1e_5 = 1e-5f;
    public static final float NUM_1e_4 = 1e-4f;
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_9 = 0.9f;
    public static final float NUM_1e_10 = 1e-10f;
    public static final float NUM_1 = 1f;

    public static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort PROFILE_CURVE = new InputPort("profile_curve", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.1f, 0.001f, 100f);
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 12, 3f, 128f);
    public static final InputPort FILL_CAPS = new InputPort("fill_caps", PortType.BOOLEAN, true);
    public static final InputPort RADIUS_CLOSURE = new InputPort("radius_closure", PortType.CLOSURE, null);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Converts a curve into a tube mesh by sweeping a circular or custom profile cross-section along the path, with optional radius closure for per-station scaling and cap filling.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE.name, "Path curve to sweep along.",
                PROFILE_CURVE.name, "Optional custom cross-section curve. If null, a circle of `radius` and `resolution` is used.",
                RADIUS.name, "Tube radius (ignored when profile_curve is set).",
                RESOLUTION.name, "Vertices around the circular cross-section (ignored when profile_curve is set).",
                FILL_CAPS.name, "If true, close the two ends of the tube with a disc; if false, leave open.",
                RADIUS_CLOSURE.name, "Optional float closure mapping t∈[0,1] along the path to a per-station radius multiplier. null = uniform.",
                GEOMETRY.name, "Tube mesh as a geometry bundle."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, PROFILE_CURVE, RADIUS, RESOLUTION, FILL_CAPS, RADIUS_CLOSURE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle curveGb = GeometryBundles.bundlePart(ctx.getInput(CURVE.name, Object.class));
        if (curveGb == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        Object rawCurve = curveGb.slots().get(CurveGeometry.SLOT);
        if (!(rawCurve instanceof CurveGeometry cg) || cg.curveCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        float radius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, RADIUS.name, RADIUS.defaultValue), NUM_0_1);
        int resolution = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, RESOLUTION.name, RESOLUTION.defaultValue), 0, NUM_12);
        resolution = Math.max(NUM_3, Math.min(NUM_128, resolution));
        boolean fillCaps = FieldBroadcast.boolAt(
                FieldBroadcast.getInputOrDefault(ctx, FILL_CAPS.name, FILL_CAPS.defaultValue), 0, true);

        Object closureObj = ctx.getInput(RADIUS_CLOSURE.name, Object.class);
        FloatCurveKernel radiusClosure = (closureObj instanceof FloatCurveKernel k) ? k : null;

        // Determine cross-section: use profile curve if provided, otherwise circle
        float[] profileU;
        float[] profileV;
        GeometryBundle profileGb = GeometryBundles.bundlePart(ctx.getInput(PROFILE_CURVE.name, Object.class));
        if (profileGb != null) {
            Object rawProfile = profileGb.slots().get(CurveGeometry.SLOT);
            if (rawProfile instanceof CurveGeometry profileCg && profileCg.pointCount() >= NUM_3) {
                int pn = profileCg.pointCount();
                float[] ppos = profileCg.positions();
                profileU = new float[pn];
                profileV = new float[pn];
                for (int i = 0; i < pn; i++) {
                    profileU[i] = ppos[i * NUM_3];      // X as U
                    profileV[i] = ppos[i * NUM_3 + 1];  // Y as V
                }
            } else {
                profileU = circleU(resolution, radius);
                profileV = circleV(resolution, radius);
            }
        } else {
            profileU = circleU(resolution, radius);
            profileV = circleV(resolution, radius);
        }

        // Build mesh by sweeping profile along each curve segment
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        float[] pos = cg.positions();

        for (int ci = 0; ci < cg.curveCount(); ci++) {
            int off0 = cg.curveOffsets()[ci];
            int off1 = cg.curveOffsets()[ci + 1];
            int nPts = off1 - off0;
            if (nPts < 2) continue;

            Vector3f[] pts = new Vector3f[nPts];
            for (int i = 0; i < nPts; i++) {
                int b = NUM_3 * (off0 + i);
                pts[i] = new Vector3f(pos[b], pos[b + 1], pos[b + 2]);
            }

            float closedEps = NUM_1e_5;
            if (nPts > 2) {
                float pathLen = 0;
                for (int i = 1; i < nPts; i++) pathLen += pts[i].distance(pts[i - 1]);
                closedEps = Math.max(NUM_1e_5, pathLen * NUM_1e_4);
            }
            boolean closed = nPts > 2 && pts[0].distance(pts[nPts - 1]) < closedEps;

            int nSamples = closed ? nPts - 1 : nPts;
            if (nSamples < 2) continue;

            int m = profileU.length;
            sweepAlongCurve(mesh, pts, nSamples, closed, profileU, profileV, m, fillCaps, radiusClosure);
        }

        mesh.computeNormals();
        ctx.setOutput(GEOMETRY.name, curveGb.withMesh(mesh));
    }

    private static float[] circleU(int n, float radius) {
        float[] u = new float[n];
        for (int i = 0; i < n; i++) {
            double angle = NUM_2_0 * Math.PI * i / n;
            u[i] = (float) (Math.cos(angle) * radius);
        }
        return u;
    }

    private static float[] circleV(int n, float radius) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            double angle = NUM_2_0 * Math.PI * i / n;
            v[i] = (float) (Math.sin(angle) * radius);
        }
        return v;
    }

    private static void sweepAlongCurve(HalfEdgeMesh mesh, Vector3f[] pts, int nSamples,
                                         boolean closed, float[] profileU, float[] profileV,
                                         int m, boolean fillCaps, FloatCurveKernel radiusClosure) {
        // Compute initial frame
        Vector3f w = tangent(pts, 0, nSamples, closed);
        if (w.lengthSquared() < NUM_1e_20) return;
        w.normalize();

        Vector3f uDir = new Vector3f();
        Vector3f vDir = new Vector3f();
        stablePerp(w, uDir);
        vDir.set(w).cross(uDir).normalize();
        uDir.set(vDir).cross(w).normalize();

        int[][] vid = new int[nSamples][m];

        for (int i = 0; i < nSamples; i++) {
            if (i > 0) {
                w = tangent(pts, i, nSamples, closed);
                if (w.lengthSquared() < NUM_1e_20) w.set(0, 1, 0);
                w.normalize();
                // Parallel transport: project uDir onto plane perpendicular to new tangent
                uDir.fma(-w.dot(uDir), w);
                if (uDir.lengthSquared() < NUM_1e_12) stablePerp(w, uDir);
                else uDir.normalize();
                vDir.set(w).cross(uDir).normalize();
                uDir.set(vDir).cross(w).normalize();
            }

            Vector3f p = pts[i];
            float scale = 1.0f;
            if (radiusClosure != null) {
                float t = (nSamples > 1) ? (float) i / (nSamples - 1) : NUM_0_5;
                scale = radiusClosure.evaluate(t);
            }
            for (int k = 0; k < m; k++) {
                float pu = profileU[k] * scale;
                float pv = profileV[k] * scale;
                float x = p.x + uDir.x * pu + vDir.x * pv;
                float y = p.y + uDir.y * pu + vDir.y * pv;
                float z = p.z + uDir.z * pu + vDir.z * pv;
                vid[i][k] = mesh.addVertex(x, y, z);
            }
        }

        // Connect quads
        if (closed) {
            for (int i = 0; i < nSamples; i++) {
                int inext = (i + 1) % nSamples;
                for (int j = 0; j < m; j++) {
                    int jn = (j + 1) % m;
                    mesh.addFace(vid[i][j], vid[i][jn], vid[inext][jn], vid[inext][j]);
                }
            }
        } else {
            for (int i = 0; i < nSamples - 1; i++) {
                for (int j = 0; j < m; j++) {
                    int jn = (j + 1) % m;
                    mesh.addFace(vid[i][j], vid[i][jn], vid[i + 1][jn], vid[i + 1][j]);
                }
            }
            if (fillCaps && m >= NUM_3) {
                addCap(mesh, vid[0], m, false);
                addCap(mesh, vid[nSamples - 1], m, true);
            }
        }
    }

    private static Vector3f tangent(Vector3f[] pts, int i, int n, boolean closed) {
        if (closed) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            return new Vector3f(pts[next]).sub(pts[prev]);
        }
        if (i == 0) return new Vector3f(pts[1]).sub(pts[0]);
        if (i == n - 1) return new Vector3f(pts[i]).sub(pts[i - 1]);
        return new Vector3f(pts[i + 1]).sub(pts[i - 1]);
    }

    private static void stablePerp(Vector3f w, Vector3f out) {
        Vector3f ref = new Vector3f(1, 0, 0);
        if (Math.abs(w.dot(ref)) > NUM_0_9) ref.set(0, 1, 0);
        out.set(ref).fma(-w.dot(ref), w);
        if (out.lengthSquared() < NUM_1e_10) out.set(0, 0, 1).fma(-w.z, w);
        out.normalize();
    }

    private static void addCap(HalfEdgeMesh mesh, int[] ring, int m, boolean flip) {
        Vector3f centroid = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int k = 0; k < m; k++) {
            mesh.vertexPosition(ring[k], tmp);
            centroid.add(tmp);
        }
        centroid.mul(NUM_1 / m);
        int cid = mesh.addVertex(centroid.x, centroid.y, centroid.z);
        for (int j = 0; j < m; j++) {
            int jn = (j + 1) % m;
            if (flip) mesh.addFace(cid, ring[j], ring[jn]);
            else mesh.addFace(cid, ring[jn], ring[j]);
        }
    }
}

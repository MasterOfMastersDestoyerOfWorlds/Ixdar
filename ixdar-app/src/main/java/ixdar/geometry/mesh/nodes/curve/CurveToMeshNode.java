package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import org.joml.Quaternionf;
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
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Converts a curve into a tube mesh by sweeping a circular cross-section along the path.
 * If a profile_curve is provided, its points define the cross-section shape instead.
 * <p>
 * Uses parallel-transported frame for smooth orientation along the curve.
 */
@MeshNodeAnnotation(id = "curve_to_mesh")
public class CurveToMeshNode implements MeshNode {

    private static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE_CURVE = new InputPort("profile_curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.1f, 0.001f, 100f);
    private static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 12, 3f, 128f);
    private static final InputPort FILL_CAPS = new InputPort("fill_caps", PortType.BOOLEAN, true);
    private static final InputPort RADIUS_CLOSURE = new InputPort("radius_closure", PortType.CLOSURE, null);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Converts a curve into a tube mesh by sweeping a circular or custom profile cross-section along the path, with optional radius closure for per-station scaling and cap filling.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "curve", "Path curve to sweep along.",
                "profile_curve", "Optional custom cross-section curve. If null, a circle of `radius` and `resolution` is used.",
                "radius", "Tube radius (ignored when profile_curve is set).",
                "resolution", "Vertices around the circular cross-section (ignored when profile_curve is set).",
                "fill_caps", "If true, close the two ends of the tube with a disc; if false, leave open.",
                "radius_closure", "Optional float closure mapping t∈[0,1] along the path to a per-station radius multiplier. null = uniform.",
                "geometry", "Tube mesh as a geometry bundle."
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
        GeometryBundle curveGb = GeometryBundles.bundlePart(ctx.getInput("curve", Object.class));
        if (curveGb == null) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        Object rawCurve = curveGb.slots().get("_curve");
        if (!(rawCurve instanceof CurveGeometry cg) || cg.curveCount() == 0) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        float radius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "radius", RADIUS.defaultValue()), 0.1f);
        int resolution = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "resolution", RESOLUTION.defaultValue()), 0, 12);
        resolution = Math.max(3, Math.min(128, resolution));
        boolean fillCaps = FieldBroadcast.boolAt(
                FieldBroadcast.getInputOrDefault(ctx, "fill_caps", FILL_CAPS.defaultValue()), 0, true);

        Object closureObj = ctx.getInput("radius_closure", Object.class);
        FloatCurveKernel radiusClosure = (closureObj instanceof FloatCurveKernel k) ? k : null;

        // Determine cross-section: use profile curve if provided, otherwise circle
        float[] profileU;
        float[] profileV;
        GeometryBundle profileGb = GeometryBundles.bundlePart(ctx.getInput("profile_curve", Object.class));
        if (profileGb != null) {
            Object rawProfile = profileGb.slots().get("_curve");
            if (rawProfile instanceof CurveGeometry profileCg && profileCg.pointCount() >= 3) {
                int pn = profileCg.pointCount();
                float[] ppos = profileCg.positions();
                profileU = new float[pn];
                profileV = new float[pn];
                for (int i = 0; i < pn; i++) {
                    profileU[i] = ppos[i * 3];      // X as U
                    profileV[i] = ppos[i * 3 + 1];  // Y as V
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
                int b = 3 * (off0 + i);
                pts[i] = new Vector3f(pos[b], pos[b + 1], pos[b + 2]);
            }

            float closedEps = 1e-5f;
            if (nPts > 2) {
                float diag = pts[0].distance(pts[nPts - 1]);
                float pathLen = 0;
                for (int i = 1; i < nPts; i++) pathLen += pts[i].distance(pts[i - 1]);
                closedEps = Math.max(1e-5f, pathLen * 1e-4f);
            }
            boolean closed = nPts > 2 && pts[0].distance(pts[nPts - 1]) < closedEps;

            int nSamples = closed ? nPts - 1 : nPts;
            if (nSamples < 2) continue;

            int m = profileU.length;
            sweepAlongCurve(mesh, pts, nSamples, closed, profileU, profileV, m, fillCaps, radiusClosure);
        }

        mesh.computeNormals();
        ctx.setOutput("geometry", curveGb.withMesh(mesh));
    }

    private static float[] circleU(int n, float radius) {
        float[] u = new float[n];
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            u[i] = (float) (Math.cos(angle) * radius);
        }
        return u;
    }

    private static float[] circleV(int n, float radius) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            v[i] = (float) (Math.sin(angle) * radius);
        }
        return v;
    }

    private static void sweepAlongCurve(HalfEdgeMesh mesh, Vector3f[] pts, int nSamples,
                                         boolean closed, float[] profileU, float[] profileV,
                                         int m, boolean fillCaps, FloatCurveKernel radiusClosure) {
        // Compute initial frame
        Vector3f w = tangent(pts, 0, nSamples, closed);
        if (w.lengthSquared() < 1e-20f) return;
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
                if (w.lengthSquared() < 1e-20f) w.set(0, 1, 0);
                w.normalize();
                // Parallel transport: project uDir onto plane perpendicular to new tangent
                uDir.fma(-w.dot(uDir), w);
                if (uDir.lengthSquared() < 1e-12f) stablePerp(w, uDir);
                else uDir.normalize();
                vDir.set(w).cross(uDir).normalize();
                uDir.set(vDir).cross(w).normalize();
            }

            Vector3f p = pts[i];
            float scale = 1.0f;
            if (radiusClosure != null) {
                float t = (nSamples > 1) ? (float) i / (nSamples - 1) : 0.5f;
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
            if (fillCaps && m >= 3) {
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
        if (Math.abs(w.dot(ref)) > 0.9f) ref.set(0, 1, 0);
        out.set(ref).fma(-w.dot(ref), w);
        if (out.lengthSquared() < 1e-10f) out.set(0, 0, 1).fma(-w.z, w);
        out.normalize();
    }

    private static void addCap(HalfEdgeMesh mesh, int[] ring, int m, boolean flip) {
        Vector3f centroid = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int k = 0; k < m; k++) {
            mesh.vertexPosition(ring[k], tmp);
            centroid.add(tmp);
        }
        centroid.mul(1f / m);
        int cid = mesh.addVertex(centroid.x, centroid.y, centroid.z);
        for (int j = 0; j < m; j++) {
            int jn = (j + 1) % m;
            if (flip) mesh.addFace(cid, ring[j], ring[jn]);
            else mesh.addFace(cid, ring[jn], ring[j]);
        }
    }
}

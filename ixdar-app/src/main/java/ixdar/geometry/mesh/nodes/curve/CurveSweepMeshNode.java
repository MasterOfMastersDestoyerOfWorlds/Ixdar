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
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Sweeps the first face of {@code profile} along the first polyline in {@code curve}.
 * The profile mesh should be a flat polygon; its face normal is aligned to the curve
 * tangent at the start, then the cross-section basis is parallel-transported along the path.
 */
@MeshNodeAnnotation(id = "curve_sweep")
public class CurveSweepMeshNode implements MeshNode {

    private static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort PROFILE = new InputPort("profile", PortType.MESH, null);
    private static final InputPort CAPS = new InputPort("caps", PortType.BOOLEAN, true);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, PROFILE, CAPS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle curveGb = GeometryBundles.bundlePart(ctx.getInput("curve", Object.class));
        MeshTopology prof = ctx.getInput("profile", MeshTopology.class);
        Object capObj = FieldBroadcast.getInputOrDefault(ctx, "caps", CAPS.defaultValue());
        boolean addCaps = capObj instanceof Boolean ? (Boolean) capObj : true;

        if (curveGb == null || prof == null || prof.faceCount() == 0) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        Object rawCurve = curveGb.slots().get("_curve");
        if (!(rawCurve instanceof CurveGeometry cg) || cg.curveCount() == 0) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        int off0 = cg.curveOffsets()[0];
        int off1 = cg.curveOffsets()[1];
        int ptTotal = (off1 - off0);
        if (ptTotal < 2) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        float[] pos = cg.positions();
        Vector3f[] pts = new Vector3f[ptTotal];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < ptTotal; i++) {
            int b = 3 * (off0 + i);
            float x = pos[b];
            float y = pos[b + 1];
            float z = pos[b + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                ctx.setOutput("geometry", GeometryBundle.empty());
                return;
            }
            pts[i] = new Vector3f(x, y, z);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float diag = new Vector3f(maxX - minX, maxY - minY, maxZ - minZ).length();
        float closedEps = Math.max(1e-5f, diag * 1e-4f);
        boolean closedCurve = pts[0].distance(pts[ptTotal - 1]) < closedEps;

        int nSamples = closedCurve ? ptTotal - 1 : ptTotal;
        if (nSamples < 2) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        Vector3f[] samplePts = new Vector3f[nSamples];
        for (int i = 0; i < nSamples; i++) {
            samplePts[i] = pts[i];
        }

        int fid = prof.faceIdAt(0);
        int m = prof.faceVertexCount(fid);
        if (m < 3) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        int[] ring = new int[m];
        Vector3f tmp = new Vector3f();
        for (int k = 0; k < m; k++) {
            ring[k] = prof.faceVertexAt(fid, k);
        }

        Vector3f centroid = new Vector3f();
        for (int k = 0; k < m; k++) {
            prof.vertexPosition(ring[k], tmp);
            centroid.add(tmp);
        }
        centroid.mul(1.0f / m);

        Vector3f nFace = prof.faceNormal(fid, new Vector3f());
        if (nFace.lengthSquared() < 1e-20f) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        nFace.normalize();

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        prof.vertexPosition(ring[0], a);
        prof.vertexPosition(ring[1], b);
        Vector3f uMesh = new Vector3f(b).sub(a);
        uMesh.fma(-nFace.dot(uMesh), nFace);
        if (uMesh.lengthSquared() < 1e-20f) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        uMesh.normalize();
        Vector3f vMesh = nFace.cross(uMesh, new Vector3f());
        if (vMesh.lengthSquared() < 1e-20f || !Float.isFinite(vMesh.lengthSquared())) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        vMesh.normalize();

        float[] u2 = new float[m];
        float[] v2 = new float[m];
        for (int k = 0; k < m; k++) {
            prof.vertexPosition(ring[k], tmp);
            tmp.sub(centroid);
            u2[k] = uMesh.dot(tmp);
            v2[k] = vMesh.dot(tmp);
        }

        Vector3f w0 = curveTangent(samplePts, 0, closedCurve);
        if (w0.lengthSquared() < 1e-20f) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        w0.normalize();

        Vector3f uDir = alignProfileUToTangent(nFace, w0, uMesh);
        Vector3f vDir = new Vector3f();
        makeFrameOrthonormal(w0, uDir, vDir);

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[][] vid = new int[nSamples][m];

        for (int i = 0; i < nSamples; i++) {
            Vector3f w = curveTangent(samplePts, i, closedCurve);
            if (w.lengthSquared() < 1e-20f || !Float.isFinite(w.lengthSquared())) {
                ctx.setOutput("geometry", GeometryBundle.empty());
                return;
            }
            w.normalize();
            if (i > 0) {
                makeFrameOrthonormal(w, uDir, vDir);
            }
            Vector3f p = samplePts[i];
            for (int k = 0; k < m; k++) {
                float x = p.x + uDir.x * u2[k] + vDir.x * v2[k];
                float y = p.y + uDir.y * u2[k] + vDir.y * v2[k];
                float z = p.z + uDir.z * u2[k] + vDir.z * v2[k];
                vid[i][k] = mesh.addVertex(x, y, z);
            }
        }

        if (closedCurve) {
            for (int i = 0; i < nSamples; i++) {
                int inext = (i + 1) % nSamples;
                addTubeQuads(mesh, vid, m, i, inext);
            }
        } else {
            for (int i = 0; i < nSamples - 1; i++) {
                addTubeQuads(mesh, vid, m, i, i + 1);
            }
            if (addCaps) {
                addDiscCap(mesh, vid[0], m, false);
                addDiscCap(mesh, vid[nSamples - 1], m, true);
            }
        }

        mesh.computeNormals();
        ctx.setOutput("geometry", curveGb.withMesh(mesh));
    }

    /**
     * Map profile tangent {@code uMesh} (in plane ⊥ {@code nFace}) into the plane ⊥ {@code w0}.
     * When {@code nFace} ∥ ±{@code w0}, avoids {@link Quaternionf#rotateTo} degeneracy by orthogonal projection.
     */
    private static Vector3f alignProfileUToTangent(Vector3f nFace, Vector3f w0, Vector3f uMesh) {
        float d = nFace.dot(w0);
        Vector3f uDir = new Vector3f(uMesh);
        if (Math.abs(Math.abs(d) - 1f) <= 1e-6f) {
            uDir.fma(-w0.dot(uDir), w0);
            if (uDir.lengthSquared() < 1e-14f || !Float.isFinite(uDir.lengthSquared())) {
                fillStablePerpendicularTo(w0, uDir);
            } else {
                uDir.normalize();
            }
            return uDir;
        }
        new Quaternionf().rotateTo(nFace, w0).transform(uDir);
        if (!Float.isFinite(uDir.x) || !Float.isFinite(uDir.y) || !Float.isFinite(uDir.z)) {
            uDir.set(uMesh).fma(-w0.dot(uMesh), w0);
        }
        if (uDir.lengthSquared() < 1e-20f || !Float.isFinite(uDir.lengthSquared())) {
            uDir.set(uMesh).fma(-w0.dot(uMesh), w0);
            if (uDir.lengthSquared() < 1e-14f) {
                fillStablePerpendicularTo(w0, uDir);
            } else {
                uDir.normalize();
            }
        } else {
            uDir.normalize();
        }
        return uDir;
    }

    private static void fillStablePerpendicularTo(Vector3f w, Vector3f out) {
        Vector3f ref = new Vector3f(1f, 0f, 0f);
        if (Math.abs(w.dot(ref)) > 0.9f) {
            ref.set(0f, 1f, 0f);
        }
        out.set(ref).fma(-w.dot(ref), w);
        if (out.lengthSquared() < 1e-10f) {
            out.set(0f, 0f, 1f).fma(-w.z, w);
        }
        out.normalize();
    }

    /**
     * Right-handed orthonormal frame with {@code w} as tangent; overwrites {@code uAxis} and {@code vAxis}
     * in the plane perpendicular to {@code w}. Avoids normalizing a zero cross product after quaternion steps.
     */
    private static void makeFrameOrthonormal(Vector3f w, Vector3f uAxis, Vector3f vAxis) {
        w.normalize();
        uAxis.fma(-w.dot(uAxis), w);
        float uLenSq = uAxis.lengthSquared();
        if (uLenSq < 1e-12f) {
            Vector3f ref = new Vector3f(1f, 0f, 0f);
            if (Math.abs(w.dot(ref)) > 0.9f) {
                ref.set(0f, 1f, 0f);
            }
            uAxis.set(ref).fma(-w.dot(ref), w);
            uLenSq = uAxis.lengthSquared();
        }
        if (uLenSq < 1e-20f) {
            uAxis.set(0f, 0f, 1f).fma(-w.z, w);
        }
        uAxis.normalize();
        vAxis.set(w).cross(uAxis);
        float vLenSq = vAxis.lengthSquared();
        if (vLenSq < 1e-20f) {
            vAxis.set(uAxis).cross(w);
        }
        vAxis.normalize();
        uAxis.set(vAxis).cross(w).normalize();
    }

    private static Vector3f curveTangent(Vector3f[] p, int i, boolean closed) {
        int n = p.length;
        if (closed) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            return new Vector3f(p[next]).sub(p[prev]);
        }
        if (i == 0) {
            return new Vector3f(p[1]).sub(p[0]);
        }
        if (i == n - 1) {
            return new Vector3f(p[i]).sub(p[i - 1]);
        }
        return new Vector3f(p[i + 1]).sub(p[i - 1]);
    }

    private static void addTubeQuads(HalfEdgeMesh mesh, int[][] vid, int m, int i0, int i1) {
        for (int j = 0; j < m; j++) {
            int jn = (j + 1) % m;
            mesh.addFace(vid[i0][j], vid[i0][jn], vid[i1][jn], vid[i1][j]);
        }
    }

    /** Fan from a new center vertex so ring edges stay at two faces (tube + cap) each. */
    private static void addDiscCap(HalfEdgeMesh mesh, int[] ring, int m, boolean flip) {
        if (m < 3) {
            return;
        }
        Vector3f centroid = new Vector3f();
        Vector3f tmp = new Vector3f();
        for (int k = 0; k < m; k++) {
            mesh.vertexPosition(ring[k], tmp);
            centroid.add(tmp);
        }
        centroid.mul(1.0f / m);
        int centerId = mesh.addVertex(centroid.x, centroid.y, centroid.z);
        for (int j = 0; j < m; j++) {
            int jn = (j + 1) % m;
            if (!flip) {
                mesh.addFace(centerId, ring[jn], ring[j]);
            } else {
                mesh.addFace(centerId, ring[j], ring[jn]);
            }
        }
    }
}

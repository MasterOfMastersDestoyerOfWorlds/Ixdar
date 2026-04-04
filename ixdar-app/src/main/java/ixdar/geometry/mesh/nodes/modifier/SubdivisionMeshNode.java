package ixdar.geometry.mesh.nodes.modifier;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.QuadMeshTopologyHelper;

@MeshNodeAnnotation(id = "subdivision_surface")
public class SubdivisionMeshNode implements MeshNode {

    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology mesh = ctx.getInput("mesh", MeshTopology.class);
        Number levelsInput = ctx.getInput("levels", Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        if (levels == 0) {
            ctx.setOutput("mesh", mesh);
            ctx.setOutput("geometry", GeometryBundle.ofMesh(mesh));
            return;
        }

        DenseQuadMesh dq = extractDenseQuadMesh(mesh);
        float[] positions = dq.positions;
        int[] quadIndices = dq.quadIndices;
        int nv = dq.vertexCount;

        for (int i = 0; i < levels; i++) {
            int nf = quadIndices.length / 4;
            CatmullClarkResult step = applyCatmullClarkLevel(positions, quadIndices, nv, nf);
            positions = step.positions;
            quadIndices = step.quadIndices;
            nv = positions.length / 3;
        }

        ArrayMesh out = ArrayMesh.fromQuads(positions, quadIndices);
        out.computeNormals();

        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", GeometryBundle.ofMesh(out));
    }

    private static final class CatmullClarkResult {
        final float[] positions;
        final int[] quadIndices;

        CatmullClarkResult(float[] positions, int[] quadIndices) {
            this.positions = positions;
            this.quadIndices = quadIndices;
        }
    }

    private static final class DenseQuadMesh {
        final float[] positions;
        final int[] quadIndices;
        final int vertexCount;

        DenseQuadMesh(float[] positions, int[] quadIndices, int vertexCount) {
            this.positions = positions;
            this.quadIndices = quadIndices;
            this.vertexCount = vertexCount;
        }
    }

    private static DenseQuadMesh extractDenseQuadMesh(MeshTopology mesh) {
        if (mesh instanceof ArrayMesh am) {
            if (am.getVertsPerFace() != 4) {
                throw new IllegalArgumentException("subdivision_surface: ArrayMesh must be all quads");
            }
            return new DenseQuadMesh(am.copyPositions(), am.copyFaceIndices(), am.vertexCount());
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return extractDenseQuads(hem);
        }
        throw new IllegalArgumentException("subdivision_surface: unsupported mesh type " + mesh.getClass().getName());
    }

    private static DenseQuadMesh extractDenseQuads(HalfEdgeMesh mesh) {
        int nv = mesh.vertexCount();
        int maxVid = 0;
        for (int i = 0; i < nv; i++) {
            maxVid = Math.max(maxVid, mesh.vertexIdAt(i));
        }
        int[] oldToDense = new int[maxVid + 1];
        Arrays.fill(oldToDense, MeshTopology.NONE);
        float[] pos = new float[nv * 3];
        Vector3f p = new Vector3f();
        for (int i = 0; i < nv; i++) {
            int vid = mesh.vertexIdAt(i);
            oldToDense[vid] = i;
            mesh.vertexPosition(vid, p);
            pos[i * 3] = p.x;
            pos[i * 3 + 1] = p.y;
            pos[i * 3 + 2] = p.z;
        }
        int nf = mesh.faceCount();
        int[] quads = new int[nf * 4];
        for (int fi = 0; fi < nf; fi++) {
            int f = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(f) != 4) {
                throw new IllegalArgumentException("subdivision_surface: all faces must be quads");
            }
            for (int k = 0; k < 4; k++) {
                quads[fi * 4 + k] = oldToDense[mesh.faceVertexAt(f, k)];
            }
        }
        return new DenseQuadMesh(pos, quads, nv);
    }

    private static int nextVertex(int[] quads, int he) {
        return quads[(he & ~3) | ((he + 1) & 3)];
    }

    private static CatmullClarkResult applyCatmullClarkLevel(float[] positions, int[] quadIndices, int nv, int nf) {
        QuadMeshTopologyHelper topo = QuadMeshTopologyHelper.build(quadIndices, 4, nv, nf);
        int ne = topo.edgeCount;

        final float[] faceCenter = new float[nf * 3];
        parallelRange(nf, fi -> {
            float sx = 0f;
            float sy = 0f;
            float sz = 0f;
            for (int k = 0; k < 4; k++) {
                int v = quadIndices[fi * 4 + k];
                int o = v * 3;
                sx += positions[o];
                sy += positions[o + 1];
                sz += positions[o + 2];
            }
            float inv = 0.25f;
            int fo = fi * 3;
            faceCenter[fo] = sx * inv;
            faceCenter[fo + 1] = sy * inv;
            faceCenter[fo + 2] = sz * inv;
        });

        final float[] edgePos = new float[ne * 3];
        parallelRange(ne, i -> {
            int he = topo.edgeHalfEdge[i];
            int tw = topo.halfEdgeTwin[he];
            int va = quadIndices[he];
            int vb = nextVertex(quadIndices, he);
            int o1 = va * 3;
            int o2 = vb * 3;
            float x1 = positions[o1];
            float y1 = positions[o1 + 1];
            float z1 = positions[o1 + 2];
            float x2 = positions[o2];
            float y2 = positions[o2 + 1];
            float z2 = positions[o2 + 2];

            int f1 = he / 4;
            int f2 = tw == MeshTopology.NONE ? MeshTopology.NONE : tw / 4;

            int eo = i * 3;
            if (f1 != MeshTopology.NONE && f2 != MeshTopology.NONE) {
                int fc1 = f1 * 3;
                int fc2 = f2 * 3;
                edgePos[eo] = (x1 + x2 + faceCenter[fc1] + faceCenter[fc2]) * 0.25f;
                edgePos[eo + 1] = (y1 + y2 + faceCenter[fc1 + 1] + faceCenter[fc2 + 1]) * 0.25f;
                edgePos[eo + 2] = (z1 + z2 + faceCenter[fc1 + 2] + faceCenter[fc2 + 2]) * 0.25f;
            } else {
                edgePos[eo] = (x1 + x2) * 0.5f;
                edgePos[eo + 1] = (y1 + y2) * 0.5f;
                edgePos[eo + 2] = (z1 + z2) * 0.5f;
            }
        });

        final float[] vtxPos = new float[nv * 3];
        final float[] oldPos = positions;
        parallelRange(nv, i -> {
            int v = i;
            int vo = i * 3;
            float ox = oldPos[vo];
            float oy = oldPos[vo + 1];
            float oz = oldPos[vo + 2];

            boolean boundary = false;
            for (int j = topo.vertexEdgeOffsets[v]; j < topo.vertexEdgeOffsets[v + 1]; j++) {
                int e = topo.vertexEdges[j];
                int he = topo.edgeHalfEdge[e];
                if (topo.halfEdgeTwin[he] == MeshTopology.NONE) {
                    boundary = true;
                    break;
                }
            }
            if (boundary) {
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
                return;
            }

            int n = topo.vertexFaceOffsets[v + 1] - topo.vertexFaceOffsets[v];
            if (n == 0) {
                vtxPos[vo] = ox;
                vtxPos[vo + 1] = oy;
                vtxPos[vo + 2] = oz;
                return;
            }

            float fx = 0f;
            float fy = 0f;
            float fz = 0f;
            for (int j = topo.vertexFaceOffsets[v]; j < topo.vertexFaceOffsets[v + 1]; j++) {
                int faceId = topo.vertexFaces[j];
                int fo = faceId * 3;
                fx += faceCenter[fo];
                fy += faceCenter[fo + 1];
                fz += faceCenter[fo + 2];
            }
            float invN = 1.0f / n;
            fx *= invN;
            fy *= invN;
            fz *= invN;

            float rx = 0f;
            float ry = 0f;
            float rz = 0f;
            for (int j = topo.vertexEdgeOffsets[v]; j < topo.vertexEdgeOffsets[v + 1]; j++) {
                int edge = topo.vertexEdges[j];
                int he = topo.edgeHalfEdge[edge];
                int a = quadIndices[he];
                int b = nextVertex(quadIndices, he);
                int ao = a * 3;
                int bo = b * 3;
                rx += (oldPos[ao] + oldPos[bo]) * 0.5f;
                ry += (oldPos[ao + 1] + oldPos[bo + 1]) * 0.5f;
                rz += (oldPos[ao + 2] + oldPos[bo + 2]) * 0.5f;
            }
            rx *= invN;
            ry *= invN;
            rz *= invN;

            vtxPos[vo] = (fx + 2f * rx + (n - 3) * ox) * invN;
            vtxPos[vo + 1] = (fy + 2f * ry + (n - 3) * oy) * invN;
            vtxPos[vo + 2] = (fz + 2f * rz + (n - 3) * oz) * invN;
        });

        int totalNewFaces = nf * 4;
        float[] newPositions = new float[(nf + ne + nv) * 3];
        int pi = 0;
        for (int i = 0; i < nf; i++) {
            int fo = i * 3;
            newPositions[pi++] = faceCenter[fo];
            newPositions[pi++] = faceCenter[fo + 1];
            newPositions[pi++] = faceCenter[fo + 2];
        }
        for (int i = 0; i < ne; i++) {
            int eo = i * 3;
            newPositions[pi++] = edgePos[eo];
            newPositions[pi++] = edgePos[eo + 1];
            newPositions[pi++] = edgePos[eo + 2];
        }
        for (int i = 0; i < nv; i++) {
            int vo = i * 3;
            newPositions[pi++] = vtxPos[vo];
            newPositions[pi++] = vtxPos[vo + 1];
            newPositions[pi++] = vtxPos[vo + 2];
        }

        int[] newQuads = new int[totalNewFaces * 4];
        int qi = 0;
        for (int fi = 0; fi < nf; fi++) {
            int fp = fi;
            for (int k = 0; k < 4; k++) {
                int he = fi * 4 + k;
                int prevHe = fi * 4 + (k + 3) % 4;
                int vx = quadIndices[he];
                int vp = nf + ne + vx;
                int eOut = topo.halfEdgeEdge[he];
                int eIn = topo.halfEdgeEdge[prevHe];
                int epOut = nf + eOut;
                int epIn = nf + eIn;

                newQuads[qi++] = vp;
                newQuads[qi++] = epOut;
                newQuads[qi++] = fp;
                newQuads[qi++] = epIn;
            }
        }

        return new CatmullClarkResult(newPositions, newQuads);
    }

    private static void parallelRange(int n, IntConsumer body) {
        for (int i = 0; i < n; i++) {
            body.accept(i);
        }
    }
}

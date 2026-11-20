package ixdar.nodegraph.nodes.patch;

import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

import ixdar.geometry.mesh.data.patches.CoonsHandleBuilder;
/**
 * Geometry-preserving loop cut for bezier Coons cages: edges aligned with the
 * given axis are split by exact de Casteljau subdivision so new vertices land on
 * the original curves; cross-edge handles come from the Coons cross-section
 * formula.
 *
 * <p>Cages without handle slots must use {@code loop_cut}.
 */
@MeshNodeAnnotation(id = "coons_loop_cut", category = NodeCategory.PATCH_SURFACE)
public class CoonsLoopCutNode implements MeshNode {
    public static final String X = "X";
    public static final float MIN_EDGE_LENGTH = 1e-8f;
    public static final float AXIS_ALIGNMENT_THRESHOLD = 0.5f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort AXIS = new InputPort("axis", PortType.STRING, X);
    public static final InputPort CUTS = new InputPort("cuts", PortType.INT, 1, 1f, 8f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Inserts geometry-preserving loop cuts along an axis using exact de Casteljau subdivision, so new vertices land on the original Bezier curves. Input MUST carry bezier handle slots (use assign_bezier_handles upstream); for unhandled cages use loop_cut instead.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle; MUST carry bezier handle slots from assign_bezier_handles. Unhandled input passes through with a warning.",
                AXIS.name, "Cuts are placed PERPENDICULAR to this axis. Accepted: X, Y, Z.",
                CUTS.name, "Number of new edge loops to insert (1..8)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, AXIS, CUTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        if (!CoonsHandleBuilder.hasHandles(base)) {
            System.err.println("[coons_loop_cut] WARNING: input lacks bezier handles; passing through unchanged. Use assign_bezier_handles upstream, or use loop_cut for straight midpoint cuts.");
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }
        String axis = ctx.getInput(AXIS.name, String.class);
        if (axis == null) axis = X;
        Number cutsNum = ctx.getInput(CUTS.name, Number.class);
        int cuts = cutsNum == null ? 1 : Math.max(1, cutsNum.intValue());
        ctx.setOutput(GEOMETRY.name, loopCut(base, axis, cuts));
    }

    /**
     * Curve-preserving loop cut: entry point for callers that already have a
     * {@link GeometryBundle} with bezier handle slots. Used by {@code loop_cut}
     * to dispatch when the input cage carries bezier metadata.
     *
     * @param base bundle carrying mesh and bezier handle slots; passed through
     *        when the mesh is null or has no faces
     * @param axis cut axis label ({@code "X"}, {@code "Y"}, or {@code "Z"});
     *        cuts are placed perpendicular to this axis
     * @param cuts number of new edge loops to insert
     * @return new bundle with split mesh and rebuilt bezier handle slots
     */
    public static GeometryBundle loopCut(GeometryBundle base, String axis, int cuts) {
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            return base;
        }
        return doLoopCut(base, axis, cuts);
    }

    private static GeometryBundle doLoopCut(GeometryBundle base, String axis, int cuts) {
        MeshTopology mesh = base.mesh();

        Vector3f axisVec = switch (axis.toUpperCase()) {
            case "Y" -> new Vector3f(0, 1, 0);
            case "Z" -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };

        PackedPoints startHandles = CoonsHandleBuilder.readHandleSlot(
                base, AssignBezierHandlesNode.SLOT_HANDLES_START, mesh);
        PackedPoints endHandles = CoonsHandleBuilder.readHandleSlot(base, AssignBezierHandlesNode.SLOT_HANDLES_END, mesh);

        int maxEid = CoonsHandleBuilder.maxEdgeId(mesh);
        boolean[] edgeSplit = new boolean[maxEid + 1];
        Vector3f tmpA = new Vector3f(), tmpB = new Vector3f(), edgeDir = new Vector3f();
        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            mesh.vertexPosition(mesh.halfEdgeVertex(he), tmpA);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(he), tmpB);
            edgeDir.set(tmpB).sub(tmpA);
            float len = edgeDir.length();
            if (len > MIN_EDGE_LENGTH) {
                edgeDir.mul(1f / len);
                if (Math.abs(edgeDir.dot(axisVec)) > AXIS_ALIGNMENT_THRESHOLD) {
                    edgeSplit[eid] = true;
                }
            }
        }

        int origCount = mesh.vertexCount();
        PackedPoints positions = mesh.packedPositions(null);
        Map<Integer, Integer> oldToNew = new HashMap<>();
        for (int i = 0; i < origCount; i++) {
            oldToNew.put(mesh.vertexIdAt(i), i);
        }

        Map<Integer, int[]> splitMids = new HashMap<>();
        Map<Long, Vector3f> dh = new HashMap<>();

        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            if (edgeSplit[eid]) continue;
            int he = mesh.edgeHalfEdge(eid);
            int ca = mesh.halfEdgeVertex(he), cb = mesh.halfEdgeEndVertex(he);
            int na = oldToNew.get(ca), nb = oldToNew.get(cb);
            dh.put(CoonsHandleBuilder.dirPack(na, nb), startHandles.at(eid));
            dh.put(CoonsHandleBuilder.dirPack(nb, na), endHandles.at(eid));
        }

        for (int ei = 0; ei < mesh.edgeCount(); ei++) {
            int eid = mesh.edgeIdAt(ei);
            if (!edgeSplit[eid]) continue;

            int he = mesh.edgeHalfEdge(eid);
            int ca = mesh.halfEdgeVertex(he), cb = mesh.halfEdgeEndVertex(he);
            int nCa = oldToNew.get(ca), nCb = oldToNew.get(cb);

            mesh.vertexPosition(ca, tmpA);
            mesh.vertexPosition(cb, tmpB);
            Vector3f rP0 = new Vector3f(tmpA);
            Vector3f rP1 = startHandles.at(eid).add(tmpA);
            Vector3f rP2 = endHandles.at(eid).add(tmpB);
            Vector3f rP3 = new Vector3f(tmpB);

            int[] mids = new int[cuts];
            int prevVid = nCa;

            for (int k = 0; k < cuts; k++) {
                float t = 1f / (cuts + 1 - k);

                CoonsHandleBuilder.SplitResult sr = CoonsHandleBuilder.split(rP0, rP1, rP2, rP3, t);

                int sv = positions.append(sr.splitPoint);
                mids[k] = sv;

                dh.put(CoonsHandleBuilder.dirPack(prevVid, sv), new Vector3f(sr.leftCp1).sub(rP0));
                dh.put(CoonsHandleBuilder.dirPack(sv, prevVid), new Vector3f(sr.leftCp2).sub(sr.splitPoint));

                rP0.set(sr.splitPoint);
                rP1.set(sr.rightCp1);
                rP2.set(sr.rightCp2);
                prevVid = sv;
            }

            dh.put(CoonsHandleBuilder.dirPack(prevVid, nCb), new Vector3f(rP1).sub(rP0));
            dh.put(CoonsHandleBuilder.dirPack(nCb, prevVid), new Vector3f(rP2).sub(rP3));

            splitMids.put(eid, mids);
        }

        ArrayList<int[]> outFaces = new ArrayList<>();

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            if (fc != 4) {
                int[] fv = new int[fc];
                for (int k = 0; k < fc; k++)
                    fv[k] = oldToNew.get(mesh.faceVertexAt(fid, k));
                outFaces.add(fv);
                continue;
            }

            int v0 = mesh.faceVertexAt(fid, 0);
            int v1 = mesh.faceVertexAt(fid, 1);
            int v2 = mesh.faceVertexAt(fid, 2);
            int v3 = mesh.faceVertexAt(fid, 3);
            int e0 = mesh.faceEdgeAt(fid, 0); // v0→v1
            int e1 = mesh.faceEdgeAt(fid, 1); // v1→v2
            int e2 = mesh.faceEdgeAt(fid, 2); // v2→v3
            int e3 = mesh.faceEdgeAt(fid, 3); // v3→v0

            boolean s0 = edgeSplit[e0], s1 = edgeSplit[e1];
            boolean s2 = edgeSplit[e2], s3 = edgeSplit[e3];

            int nv0 = oldToNew.get(v0), nv1 = oldToNew.get(v1);
            int nv2 = oldToNew.get(v2), nv3 = oldToNew.get(v3);

            if (s0 && s2 && !s1 && !s3) {
                int[] botMids = getMidsInDirection(mesh, e0, v0, splitMids);
                int[] topMids = getMidsInDirection(mesh, e2, v3, splitMids);

                for (int k = 0; k <= cuts; k++) {
                    int bl = k == 0 ? nv0 : botMids[k - 1];
                    int br = k == cuts ? nv1 : botMids[k];
                    int tr = k == cuts ? nv2 : topMids[k];
                    int tl = k == 0 ? nv3 : topMids[k - 1];
                    outFaces.add(new int[]{bl, br, tr, tl});
                }

                computeCrossHandles(mesh, startHandles, endHandles, e3, v0, v3, e1, v1, v2,
                        e0, v0, v1, e2, v3, v2,
                        botMids, topMids, cuts, positions, dh);

            } else if (!s0 && !s2 && s1 && s3) {
                int[] rightMids = getMidsInDirection(mesh, e1, v1, splitMids);
                int[] leftMids = getMidsInDirection(mesh, e3, v0, splitMids);

                for (int k = 0; k <= cuts; k++) {
                    int bl = k == 0 ? nv0 : leftMids[k - 1];
                    int br = k == 0 ? nv1 : rightMids[k - 1];
                    int tr = k == cuts ? nv2 : rightMids[k];
                    int tl = k == cuts ? nv3 : leftMids[k];
                    outFaces.add(new int[]{bl, br, tr, tl});
                }

                computeCrossHandles(mesh, startHandles, endHandles, e0, v0, v1, e2, v3, v2,
                        e3, v0, v3, e1, v1, v2,
                        leftMids, rightMids, cuts, positions, dh);

            } else {
                outFaces.add(new int[]{nv0, nv1, nv2, nv3});
            }
        }

        int[] faceIdx = new int[outFaces.size() * 4];
        int w = 0;
        for (int[] q : outFaces) {
            faceIdx[w++] = q[0];
            faceIdx[w++] = q[1];
            faceIdx[w++] = q[2];
            faceIdx[w++] = q[3];
        }

        HalfEdgeMesh outMesh = HalfEdgeMesh.bulkAllocate(positions, faceIdx, 4);

        PackedPoints[] handles = CoonsHandleBuilder.flushDirectedHandles(outMesh, dh);

        outMesh.computeNormals();

        HashMap<String, Object> nextSlots = new HashMap<>(base.slots());
        nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles[0]);
        nextSlots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles[1]);
        return new GeometryBundle(outMesh, Map.copyOf(nextSlots));
    }


    /**
     * Returns split mid vertices for an edge going in the direction starting from
     * {@code fromVid}. If the canonical direction is reversed, the array is flipped.
     *
     * @param mesh source topology used to resolve the edge's canonical direction
     * @param eid edge whose mid-vertex array is being requested
     * @param fromVid endpoint that should appear first in the returned array
     * @param splitMids edge id to canonical-order mid-vertex array
     * @return mid-vertex ids ordered {@code fromVid -> other endpoint}; empty array if {@code eid} wasn't split
     */
    private static int[] getMidsInDirection(MeshTopology mesh, int eid, int fromVid,
            Map<Integer, int[]> splitMids) {
        int[] mids = splitMids.get(eid);
        if (mids == null) return new int[0];
        int he = mesh.edgeHalfEdge(eid);
        int ca = mesh.halfEdgeVertex(he);
        if (fromVid == ca) return mids;
        int[] rev = new int[mids.length];
        for (int i = 0; i < mids.length; i++)
            rev[i] = mids[mids.length - 1 - i];
        return rev;
    }

    /**
     * Computes cross-edge bezier handles using the linear-blend Coons cross-section
     * formula. For each cut k, the cross-edge connects mids0[k] to mids1[k].
     *
     * @param perpEid0   perpendicular edge 0 (connects split0.start to split1.start corners)
     * @param perpFrom0  start vertex of perp edge 0
     * @param perpTo0    end vertex of perp edge 0
     * @param perpEid1   perpendicular edge 1 (connects split0.end to split1.end corners)
     * @param perpFrom1  start vertex of perp edge 1
     * @param perpTo1    end vertex of perp edge 1
     * @param splitEid0  split edge 0
     * @param splitFrom0 start vertex of split edge 0
     * @param splitTo0   end vertex of split edge 0
     * @param splitEid1  opposite split edge 1
     * @param splitFrom1 start vertex of split edge 1
     * @param splitTo1   end vertex of split edge 1
     * @param mids0      split vertices on split edge 0 (from splitFrom0 toward splitTo0)
     * @param mids1      split vertices on split edge 1 (from splitFrom1 toward splitTo1)
     * @param mesh       input mesh providing canonical edge directions and vertex positions
     * @param hStart     start-handle slot data on the input mesh
     * @param hEnd       end-handle slot data on the input mesh
     * @param cuts       number of cuts (length of {@code mids0} and {@code mids1})
     * @param positions  output vertex positions, the original vertices then the split vertices
     * @param dh        directed-handle map; receives one entry per direction per cut
     */
    private static void computeCrossHandles(
            MeshTopology mesh, PackedPoints hStart, PackedPoints hEnd,
            int perpEid0, int perpFrom0, int perpTo0,
            int perpEid1, int perpFrom1, int perpTo1,
            int splitEid0, int splitFrom0, int splitTo0,
            int splitEid1, int splitFrom1, int splitTo1,
            int[] mids0, int[] mids1,
            int cuts, PackedPoints positions, Map<Long, Vector3f> dh) {

        Vector3f[] perpCp0 = CoonsHandleBuilder.getEdgeControlPoints(mesh, hStart, hEnd, perpEid0, perpFrom0);
        Vector3f[] perpCp1 = CoonsHandleBuilder.getEdgeControlPoints(mesh, hStart, hEnd, perpEid1, perpFrom1);

        Vector3f splitStart0 = getPos(mesh, splitFrom0);
        Vector3f splitEnd0 = getPos(mesh, splitTo0);
        Vector3f splitStart1 = getPos(mesh, splitFrom1);
        Vector3f splitEnd1 = getPos(mesh, splitTo1);

        for (int k = 0; k < cuts; k++) {
            float t = (float) (k + 1) / (cuts + 1);

            Vector3f m0 = positions.at(mids0[k]);
            Vector3f m1 = positions.at(mids1[k]);

            Vector3f c0 = new Vector3f(m0).sub(new Vector3f(splitStart0).lerp(splitEnd0, t));
            Vector3f c1 = new Vector3f(m1).sub(new Vector3f(splitStart1).lerp(splitEnd1, t));

            Vector3f crossP1 = new Vector3f(perpCp0[1]).lerp(perpCp1[1], t)
                    .add(new Vector3f(c0).mul(2f / 3f))
                    .add(new Vector3f(c1).mul(1f / 3f));
            Vector3f crossP2 = new Vector3f(perpCp0[2]).lerp(perpCp1[2], t)
                    .add(new Vector3f(c0).mul(1f / 3f))
                    .add(new Vector3f(c1).mul(2f / 3f));

            dh.put(CoonsHandleBuilder.dirPack(mids0[k], mids1[k]), crossP1.sub(m0));
            dh.put(CoonsHandleBuilder.dirPack(mids1[k], mids0[k]), crossP2.sub(m1));
        }
    }

    private static Vector3f getPos(MeshTopology mesh, int vid) {
        return mesh.vertexPosition(vid);
    }


}

package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Assemble a {@link MotorcycleGraph.Result} into a T-mesh of nodes, arcs and
 * (best-effort) patches.
 *
 * <p>v1 splits each motorcycle trace at every {@link TNode.NodeKind#INTERSECTION}
 * node it passes through — including those created by <em>later</em>
 * motorcycles that crashed into it.  The resulting {@link TArc}s connect
 * consecutive nodes along a single trace.
 *
 * <p>Patch enumeration is a best-effort planar-dual walk that handles simple
 * cases (one cycle per parametric region) but does not robustly cover meshes
 * whose seamless parametrization has flipped triangles.  Tests assert per-arc
 * invariants rather than per-patch counts, matching the v1 caveats from the
 * upstream PATCH-48 ticket.
 */
public final class TMesh {
    public static final float NUM_0 = 0f;
    public static final double NUM_1e_9 = 1e-9;
    public static final float NUM_1e_3 = 1e-3f;

    private final List<TNode> nodes;
    private final List<TArc> arcs;
    private final List<TPatch> patches;
    private final List<LayoutConstraint> layoutConstraints;

    private TMesh(List<TNode> nodes, List<TArc> arcs, List<TPatch> patches,
                  List<LayoutConstraint> layoutConstraints) {
        this.nodes = nodes;
        this.arcs = arcs;
        this.patches = patches;
        this.layoutConstraints = layoutConstraints;
    }

    /**
     * All T-mesh nodes in id order.
     *
     * @return all T-mesh nodes (singularities, intersections, boundary nodes) in id order
     */
    public List<TNode> nodes() { return nodes; }
    /**
     * All T-mesh arcs between consecutive nodes.
     *
     * @return all T-mesh arcs connecting consecutive nodes along motorcycle traces
     */
    public List<TArc> arcs() { return arcs; }
    /**
     * Enumerated T-patches.
     *
     * @return enumerated T-patches (best-effort planar-dual face walk; see class javadoc)
     */
    public List<TPatch> patches() { return patches; }
    /**
     * Layout-deviation constraints generated from motorcycle crashes.
     *
     * @return Lyon §4.3 Eq.(4) layout-deviation constraints generated from offending crashes
     */
    public List<LayoutConstraint> layoutConstraints() { return layoutConstraints; }

    /**
     * Test-only factory: assemble a TMesh from explicit component lists.
     *
     * @param nodes  pre-built node list
     * @param arcs   pre-built arc list
     * @param patches pre-built patch list
     * @return a TMesh wrapping the given components with an empty layout-constraint list
     */
    public static TMesh fromComponents(List<TNode> nodes, List<TArc> arcs,
                                        List<TPatch> patches) {
        return new TMesh(nodes, arcs, patches, Collections.emptyList());
    }

    /**
     * PATCH-92: mesh-aware overload — passes mesh and singVertexToNode
     *  through to {@link TPatchEnumerator} so the planar-dual face walk
     *  uses fan-based sorting at multi-frame nodes (singularities).
     *
     * @param graph  motorcycle-graph result whose traces and crashes drive arc construction
     * @param param  seamless parameterization carrying per-face uv data
     * @param mesh   underlying triangle mesh used for fan-based sorting at singularity nodes
     * @return assembled TMesh with mesh-aware patch enumeration
     */
    public static TMesh build(MotorcycleGraph.Result graph,
                              SeamlessParameterization param,
                              ArrayMesh mesh) {
        return buildImpl(graph, param, mesh);
    }

    /**
     * Mesh-agnostic build: falls back to the simple four-cycle planar-dual walk
     * (no mesh-fan sorting at singularities).
     *
     * @param graph motorcycle-graph result whose traces and crashes drive arc construction
     * @param param seamless parameterization carrying per-face uv data
     * @return assembled TMesh with patches enumerated by simple cycle search
     */
    public static TMesh build(MotorcycleGraph.Result graph,
                              SeamlessParameterization param) {
        return buildImpl(graph, param, null);
    }

    private static TMesh buildImpl(MotorcycleGraph.Result graph,
                              SeamlessParameterization param,
                              ArrayMesh mesh) {
        List<TNode> nodes = new ArrayList<>(graph.nodes());
        List<TArc> arcs = new ArrayList<>();
        // PATCH-87: per-motorcycle list of TArc IDs in walk order, indexed
        // by motorcycle id. Used to compute S_ij arc sets for Eq.(4) layout
        // constraints. arcsByMotorcycle.get(mId) gives [tarc0, tarc1, ...]
        // representing the splits of motorcycle mId from singularity outward.
        HashMap<Integer, ArrayList<Integer>> arcsByMotorcycle =
                new HashMap<>();
        // For each motorcycle, the END node of each TArc — used to identify
        // which prefix of arcs reaches a given intersection node.
        HashMap<Integer, ArrayList<Integer>> endNodesByMotorcycle =
                new HashMap<>();

        // PATCH-60: bucket crashes by victim motorcycle so each motorcycle's
        // trace can be split into multiple TArcs at every crash point —
        // mirroring metriko's split_segment in motorcycle.h. When motorcycle
        // B crashes into A's trace at step k, A's trace is divided at the
        // crash point: an arc up to step k, an arc from step k onward.
        HashMap<Integer, List<MotorcycleGraph.Crash>> crashesByVictim =
                new HashMap<>();
        for (MotorcycleGraph.Crash c : graph.crashes()) {
            crashesByVictim.computeIfAbsent(c.victimMotorcycleId(), k -> new ArrayList<>())
                    .add(c);
        }

        for (Motorcycle m : graph.traces()) {
            if (m.trace().isEmpty()) continue;
            // PATCH-68: prefer the singVertex→nodeId map from the graph
            // (avoids per-face uv-match drift on multi-port launches).
            // Falls back to the legacy uv-match for older callers.
            // PATCH-89: synthetic BOUNDARY motorcycles have singVertexId =
            // BOUNDARY_MOTORCYCLE_VID; look them up by first-step UV match
            // against BOUNDARY-kind nodes instead.
            int singularityNode;
            if (m.singularityVertexId() == MotorcycleGraph.BOUNDARY_MOTORCYCLE_VID) {
                singularityNode = findBoundaryStartNode(nodes, m);
            } else {
                Integer mapped = graph.singVertexToNode() == null ? null
                        : graph.singVertexToNode().get(m.singularityVertexId());
                singularityNode = mapped != null
                        ? mapped
                        : findSingularityNode(nodes, m, param);
            }
            int endNode = m.finalNodeId();
            // direction in {0,1,2,3} = {+u, +v, -u, -v} — measure |Δu| for
            // u-axis arcs (0,2) and |Δv| for v-axis arcs (1,3).
            boolean uAxis = (m.direction() & 1) == 0;

            ArrayList<Integer> mArcs =
                    arcsByMotorcycle.computeIfAbsent(m.id(), k -> new ArrayList<>());
            ArrayList<Integer> mEndNodes =
                    endNodesByMotorcycle.computeIfAbsent(m.id(), k -> new ArrayList<>());

            List<MotorcycleGraph.Crash> myCrashes =
                    crashesByVictim.get(m.id());
            if (myCrashes == null || myCrashes.isEmpty()) {
                ArrayList<int[]> faceCrossings = new ArrayList<>();
                ArrayList<float[]> stepUvs = new ArrayList<>();
                float parametricLength = NUM_0;
                for (Motorcycle.Step s : m.trace()) {
                    faceCrossings.add(new int[]{s.meshFaceId(), s.exitEdgeIndex()});
                    stepUvs.add(new float[]{s.uIn(), s.vIn(), s.uOut(), s.vOut()});
                    parametricLength += uAxis
                            ? Math.abs(s.uOut() - s.uIn())
                            : Math.abs(s.vOut() - s.vIn());
                }
                int newArcId = arcs.size();
                arcs.add(new TArc(newArcId, singularityNode, endNode,
                        faceCrossings, stepUvs, m.direction(), parametricLength));
                mArcs.add(newArcId);
                mEndNodes.add(endNode);
                continue;
            }

            // Sort crashes by (stepIndex, distance-from-step-start) so we
            // can walk the trace and emit one TArc per (cut → next-cut)
            // window. Cut 0 = singularity start, cut N+1 = trace end.
            myCrashes.sort((a, b) -> {
                int dStep = Integer.compare(a.victimStepIndex(), b.victimStepIndex());
                if (dStep != 0) return dStep;
                Motorcycle.Step st = m.trace().get(a.victimStepIndex());
                float distA = uAxis
                        ? Math.abs(a.victimCrashU() - st.uIn())
                        : Math.abs(a.victimCrashV() - st.vIn());
                float distB = uAxis
                        ? Math.abs(b.victimCrashU() - st.uIn())
                        : Math.abs(b.victimCrashV() - st.vIn());
                return Float.compare(distA, distB);
            });

            int startNodeForArc = singularityNode;
            int crashIdx = 0;
            ArrayList<int[]> faceCrossings = new ArrayList<>();
            ArrayList<float[]> stepUvs = new ArrayList<>();
            float parametricLength = NUM_0;
            int totalSteps = m.trace().size();
            for (int stepIdx = 0; stepIdx < totalSteps; stepIdx++) {
                Motorcycle.Step step = m.trace().get(stepIdx);
                float stepStartU = step.uIn(), stepStartV = step.vIn();

                // Drain crashes that fall on the current step.
                while (crashIdx < myCrashes.size()
                        && myCrashes.get(crashIdx).victimStepIndex() == stepIdx) {
                    MotorcycleGraph.Crash c = myCrashes.get(crashIdx);
                    parametricLength += uAxis
                            ? Math.abs(c.victimCrashU() - stepStartU)
                            : Math.abs(c.victimCrashV() - stepStartV);
                    faceCrossings.add(new int[]{step.meshFaceId(), -1});
                    stepUvs.add(new float[]{stepStartU, stepStartV,
                            c.victimCrashU(), c.victimCrashV()});
                    int newArcId = arcs.size();
                    arcs.add(new TArc(newArcId, startNodeForArc,
                            c.intersectionNodeId(),
                            faceCrossings, stepUvs, m.direction(), parametricLength));
                    mArcs.add(newArcId);
                    mEndNodes.add(c.intersectionNodeId());
                    startNodeForArc = c.intersectionNodeId();
                    faceCrossings = new ArrayList<>();
                    stepUvs = new ArrayList<>();
                    parametricLength = NUM_0;
                    stepStartU = c.victimCrashU();
                    stepStartV = c.victimCrashV();
                    crashIdx++;
                }
                faceCrossings.add(new int[]{step.meshFaceId(), step.exitEdgeIndex()});
                stepUvs.add(new float[]{stepStartU, stepStartV, step.uOut(), step.vOut()});
                parametricLength += uAxis
                        ? Math.abs(step.uOut() - stepStartU)
                        : Math.abs(step.vOut() - stepStartV);
            }
            // Trailing arc (from last crash to trace end).
            if (!faceCrossings.isEmpty()) {
                int newArcId = arcs.size();
                arcs.add(new TArc(newArcId, startNodeForArc, endNode,
                        faceCrossings, stepUvs, m.direction(), parametricLength));
                mArcs.add(newArcId);
                mEndNodes.add(endNode);
            }
        }

        // PATCH-87 §4.3: build Eq.(4) layout constraints. For each "offending"
        // crash (|α_ij| above some user-α), the arcs of the crasher motorcycle
        // from its start to the intersection node form S_ij; the constraint
        // is Σ q_a ≥ 1 over those arcs.
        ArrayList<LayoutConstraint> layoutConstraints = new ArrayList<>();
        double layoutAlpha = MotorcycleGraph.defaultAlpha();
        for (MotorcycleGraph.Crash c : graph.crashes()) {
            if (c.absAlphaIj() <= layoutAlpha + NUM_1e_9) continue;   // not offending
            if (c.crasherMotorcycleId() < 0) continue;
            ArrayList<Integer> mArcs = arcsByMotorcycle.get(c.crasherMotorcycleId());
            ArrayList<Integer> mEnds = endNodesByMotorcycle.get(c.crasherMotorcycleId());
            if (mArcs == null || mEnds == null) continue;
            // Find prefix of mArcs ending AT (or before) the intersection node.
            int prefixEnd = -1;
            for (int i = 0; i < mEnds.size(); i++) {
                if (mEnds.get(i).intValue() == c.intersectionNodeId()) {
                    prefixEnd = i;
                    break;
                }
            }
            if (prefixEnd < 0) continue;
            int[] sIjArcs = new int[prefixEnd + 1];
            for (int i = 0; i <= prefixEnd; i++) sIjArcs[i] = mArcs.get(i);
            layoutConstraints.add(new LayoutConstraint(sIjArcs, c.absAlphaIj()));
        }

        // Patch enumeration — planar-dual face walk. PATCH-92 passes mesh +
        // singVertexToNode through so the walk uses mesh-fan-based sorting
        // at multi-frame singularity nodes (incompatible cross-frame angles
        // otherwise produce wrong CCW order and giant cycles).
        List<TPatch> patches = (mesh != null)
                ? TPatchEnumerator.enumerate(nodes, arcs, mesh, graph.singVertexToNode())
                : enumerateFourCycles(nodes, arcs);

        return new TMesh(nodes, arcs, patches, layoutConstraints);
    }

    /**
     * PATCH-89: locate the BOUNDARY-kind start node for a synthetic
     *  boundary motorcycle by matching its first-step (uIn, vIn) within
     *  the same face.
     *
     * @param nodes candidate node list (only BOUNDARY/SINGULARITY kinds are considered)
     * @param m     synthetic boundary motorcycle whose first step pins the start uv
     * @return id of the matching boundary node, or {@code -1} if no match within tolerance
     */
    private static int findBoundaryStartNode(List<TNode> nodes, Motorcycle m) {
        if (m.trace().isEmpty()) return -1;
        Motorcycle.Step first = m.trace().get(0);
        for (TNode n : nodes) {
            if (n.kind() != TNode.NodeKind.BOUNDARY
                    && n.kind() != TNode.NodeKind.SINGULARITY) continue;
            if (n.meshFaceId() != first.meshFaceId()) continue;
            if (Math.abs(n.u() - first.uIn()) >= NUM_1e_3) continue;
            if (Math.abs(n.v() - first.vIn()) >= NUM_1e_3) continue;
            return n.id();
        }
        return -1;
    }

    private static int findSingularityNode(List<TNode> nodes, Motorcycle m,
                                           SeamlessParameterization param) {
        if (m.trace().isEmpty()) return -1;
        Motorcycle.Step first = m.trace().get(0);
        // The singularity node sits at (uIn, vIn) in face first.meshFaceId().
        for (TNode n : nodes) {
            if (n.kind() == TNode.NodeKind.SINGULARITY
                    && n.meshFaceId() == first.meshFaceId()
                    && Math.abs(n.u() - first.uIn()) < NUM_1e_3
                    && Math.abs(n.v() - first.vIn()) < NUM_1e_3) {
                return n.id();
            }
        }
        return -1;
    }

    /**
     * Return TPatches via planar-graph face enumeration (PATCH-68).
     *
     * @param nodes T-mesh node list
     * @param arcs  T-mesh arc list
     * @return enumerated patches from the simple planar-dual walk
     */
    private static List<TPatch> enumerateFourCycles(List<TNode> nodes, List<TArc> arcs) {
        return TPatchEnumerator.enumerate(nodes, arcs);
    }

    /**
     * Lyon §4.3 Eq.(4) layout-deviation constraint. For an offending crash
     * (|α_ij| &gt; α), the constraint says: sum of q over arcs in {@code arcs}
     * must be ≥ 1, where {@code arcs} is the S_ij arc set — arcs of the
     * crasher motorcycle from its singularity start to the intersection node.
     */
    public record LayoutConstraint(int[] arcIds, double absAlphaIj) {}
}

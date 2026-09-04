package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.network.ArcNetworkNode;
import ixdar.geometry.mesh.nodes.network.NetworkArc;
import ixdar.geometry.mesh.nodes.network.NetworkNode;
import ixdar.geometry.mesh.nodes.network.NetworkPatch;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The network authoring nodes on small hand-built networks over a flat
 * grid carrier: patches trace with the interior left of the walk and restate
 * their boundary arcs' flanks from the walk direction, and every geometric
 * ambiguity — a tied nearest vertex, a tied shortest path, an unmatchable or
 * underdetermined corner tuple — throws instead of picking by id.
 */
class NetworkTracerTest {

    /** Grid tiles per axis; vertices sit on integer coordinates in x and z. */
    private static final int TILES = 4;

    @Test
    void quadPatchTracesInteriorLeftAndSetsLeftFlanks() {
        ArcNetwork net = grid();
        int a = node(net, -1, -1);
        int b = node(net, 1, -1);
        int c = node(net, 1, 1);
        int d = node(net, -1, 1);
        int ab = arc(net, a, b);
        int bc = arc(net, b, c);
        int cd = arc(net, c, d);
        int da = arc(net, d, a);

        int patch = patch(net, a, b, c, d);

        EmbeddedPatch built = net.patches.get(patch);
        assertEquals(List.of(List.of(ab), List.of(bc), List.of(cd), List.of(da)),
                built.sideArcIds, "each side carries its one authored arc, in walk order");
        assertEquals(List.of(List.of(a, b), List.of(b, c), List.of(c, d), List.of(d, a)),
                built.sideNodeIds, "each side walks corner to corner");
        for (int arcId : new int[] { ab, bc, cd, da }) {
            assertEquals(patch, net.arcs.get(arcId).leftPatchId,
                    "a forward-walked boundary arc carries the patch on its left");
            assertEquals(ArcNetwork.NONE, net.arcs.get(arcId).rightPatchId,
                    "the unauthored exterior stays unflanked");
        }
    }

    @Test
    void unmatchableCornersThrowNoMatch() {
        ArcNetwork net = grid();
        int a = node(net, -1, -1);
        int b = node(net, 1, -1);
        int c = node(net, 1, 1);
        int d = node(net, -1, 1);
        arc(net, a, b);
        arc(net, b, c);
        arc(net, c, d);
        arc(net, d, a);

        IllegalStateException noMatch = assertThrows(IllegalStateException.class,
                () -> patch(net, a, c, b, d),
                "corners out of cyclic order fit no traced face");
        assertTrue(noMatch.getMessage().contains("no traced face admits a patch with corners"),
                "the throw names the corner mismatch: " + noMatch.getMessage());
    }

    @Test
    void tJunctionSideCarriesTwoArcs() {
        ArcNetwork net = grid();
        int a = node(net, -1, -1);
        int b = node(net, 1, -1);
        int c = node(net, 1, 1);
        int d = node(net, -1, 1);
        int m = node(net, 0, -1);
        int am = arc(net, a, m);
        int mb = arc(net, m, b);
        int bc = arc(net, b, c);
        int cd = arc(net, c, d);
        int da = arc(net, d, a);

        int patch = patch(net, a, b, c, d);

        EmbeddedPatch built = net.patches.get(patch);
        assertEquals(List.of(List.of(am, mb), List.of(bc), List.of(cd), List.of(da)),
                built.sideArcIds, "the split side carries both arcs, in walk order");
        assertEquals(List.of(a, m, b), built.sideNodeIds.get(0),
                "the split side walks through the T-junction node");
        assertEquals(patch, net.arcs.get(am).leftPatchId,
                "the first half of the split side is flanked like any boundary arc");
        assertEquals(patch, net.arcs.get(mb).leftPatchId,
                "the second half of the split side is flanked like any boundary arc");
    }

    @Test
    void loopCellNeedsSideCountsAndFlanksByWalkDirection() {
        ArcNetwork net = grid();
        int v = node(net, 0, 0);
        int loop = arc(net, v, v, new Vector3Value(0f, 0f, 1f), new Vector3Value(1f, 0f, 1f));

        IllegalStateException ambiguous = assertThrows(IllegalStateException.class,
                () -> patchWithCounts(net, v, v, v, v, 1, 0, 0, 0),
                "a bare loop's two one-dart faces are indistinguishable");
        assertTrue(ambiguous.getMessage().contains("add side counts to disambiguate"),
                "the throw asks for side counts: " + ambiguous.getMessage());

        int spurEnd = node(net, -1, 0);
        int spur = arc(net, v, spurEnd);
        int inside = patchWithCounts(net, v, v, v, v, 1, 0, 0, 0);

        EmbeddedPatch built = net.patches.get(inside);
        assertEquals(List.of(List.of(loop), List.of(), List.of(), List.of()),
                built.sideArcIds, "the one-sided cell is the loop alone");
        assertEquals(List.of(List.of(v, v), List.of(v), List.of(v), List.of(v)),
                built.sideNodeIds, "every side starts and ends on the loop node");
        assertEquals(inside, net.arcs.get(loop).leftPatchId,
                "the loop walked forward carries the cell on its left");
        assertEquals(ArcNetwork.NONE, net.arcs.get(loop).rightPatchId,
                "the loop's outside flank stays unset until that patch is authored");
        assertEquals(ArcNetwork.NONE, net.arcs.get(spur).leftPatchId,
                "the spur bounds no authored patch yet");
    }

    @Test
    void pinchedOuterCellIsDisambiguatedBySideCounts() {
        ArcNetwork net = grid();
        int v = node(net, 0, 0);
        int loop = arc(net, v, v, new Vector3Value(0f, 0f, 1f), new Vector3Value(1f, 0f, 1f));
        int spurEnd = node(net, -1, 0);
        int spur = arc(net, v, spurEnd);
        patchWithCounts(net, v, v, v, v, 1, 0, 0, 0);

        IllegalStateException ambiguous = assertThrows(IllegalStateException.class,
                () -> patch(net, v, v, spurEnd, v),
                "the pinched outer face admits several corner splits");
        assertTrue(ambiguous.getMessage().contains("add side counts to disambiguate"),
                "the throw asks for side counts: " + ambiguous.getMessage());

        int outer = patchWithCounts(net, v, v, spurEnd, v, 1, 1, 1, 0);

        EmbeddedPatch built = net.patches.get(outer);
        assertEquals(List.of(List.of(loop), List.of(spur), List.of(spur), List.of()),
                built.sideArcIds, "the outer cell walks the loop backward and the spur out and back");
        assertEquals(outer, net.arcs.get(loop).rightPatchId,
                "the loop walked backward carries the outer cell on its right");
        assertEquals(outer, net.arcs.get(spur).leftPatchId,
                "the out-and-back spur carries the outer cell on both flanks");
        assertEquals(outer, net.arcs.get(spur).rightPatchId,
                "the out-and-back spur carries the outer cell on both flanks");
    }

    @Test
    void tiedShortestPathThrows() {
        ArcNetwork net = grid();
        int from = node(net, 0, 1);
        int to = node(net, 1, 0);

        IllegalStateException tie = assertThrows(IllegalStateException.class,
                () -> arc(net, from, to),
                "the two grid routes around the missing anti-diagonal tie");
        assertTrue(tie.getMessage().contains("add a via waypoint"),
                "the throw asks for a via: " + tie.getMessage());
    }

    @Test
    void tiedNearestVertexThrows() {
        ArcNetwork net = grid();

        IllegalStateException tie = assertThrows(IllegalStateException.class,
                () -> nodeAt(net, new Vector3Value(0.5f, 0f, 0f)),
                "a point midway between two vertices has no nearest pick");
        assertTrue(tie.getMessage().contains("move the point"),
                "the throw asks for a moved point: " + tie.getMessage());
    }

    /**
     * A fresh network over a triangulated flat grid, vertices on integer x and z.
     *
     * @return the empty network
     */
    private static ArcNetwork grid() {
        MapNodeContext grid = run(new GridMeshNode(),
                new String[] { "u_tiles", "v_tiles", "triangulate" },
                new Object[] { TILES, TILES, true });
        GeometryBundle carrier = grid.getOutput("mesh", GeometryBundle.class);
        MapNodeContext ctx = run(new ArcNetworkNode(), new String[] { "mesh" },
                new Object[] { carrier });
        return ctx.getOutput("net", ArcNetwork.class);
    }

    /**
     * Authors a node on the vertex at integer grid coordinates.
     *
     * @param net network being authored
     * @param x   vertex x coordinate
     * @param z   vertex z coordinate
     * @return the new node's id
     */
    private static int node(ArcNetwork net, float x, float z) {
        return nodeAt(net, new Vector3Value(x, 0f, z));
    }

    private static int nodeAt(ArcNetwork net, Vector3Value point) {
        MapNodeContext ctx = run(new NetworkNode(),
                new String[] { "net", "point" }, new Object[] { net, point });
        return ctx.getOutput("id", Integer.class);
    }

    /**
     * Authors an arc between two nodes through optional via waypoints.
     *
     * @param net  network being authored
     * @param from node the arc runs from
     * @param to   node the arc runs to
     * @param vias waypoints, in travel order
     * @return the new arc's id
     */
    private static int arc(ArcNetwork net, int from, int to, Vector3Value... vias) {
        String[] names = new String[3 + vias.length];
        Object[] values = new Object[3 + vias.length];
        names[0] = "net";
        values[0] = net;
        names[1] = "from";
        values[1] = from;
        names[2] = "to";
        values[2] = to;
        for (int index = 0; index < vias.length; index++) {
            names[3 + index] = "via" + (index + 1);
            values[3 + index] = vias[index];
        }
        MapNodeContext ctx = run(new NetworkArc(), names, values);
        return ctx.getOutput("id", Integer.class);
    }

    private static int patch(ArcNetwork net, int a, int b, int c, int d) {
        MapNodeContext ctx = run(new NetworkPatch(),
                new String[] { "net", "a", "b", "c", "d" },
                new Object[] { net, a, b, c, d });
        return ctx.getOutput("id", Integer.class);
    }

    private static int patchWithCounts(ArcNetwork net, int a, int b, int c, int d,
            int firstSide, int secondSide, int thirdSide, int fourthSide) {
        MapNodeContext ctx = run(new NetworkPatch(),
                new String[] { "net", "a", "b", "c", "d", "first_side", "second_side",
                        "third_side", "fourth_side" },
                new Object[] { net, a, b, c, d, firstSide, secondSide, thirdSide,
                        fourthSide });
        return ctx.getOutput("id", Integer.class);
    }

    private static MapNodeContext run(MeshNode node, String[] names, Object[] values) {
        MapNodeContext ctx = new MapNodeContext(node);
        for (int index = 0; index < names.length; index++) {
            ctx.setInput(names[index], values[index]);
        }
        node.evaluate(ctx);
        return ctx;
    }
}

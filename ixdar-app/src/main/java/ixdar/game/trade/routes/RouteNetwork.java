package ixdar.game.trade.routes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector2f;

import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.TradeMap;
import ixdar.game.trade.routes.RouteOperation.Kind;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.tools.EditHistory;

/**
 * The player's loops as a knot hierarchy over the map's places. An edit at layer L acts only inside the
 * knot at that depth holding the clicked place; edits replay from an operation list.
 */
public class RouteNetwork {
    public static final String REFUSED_CROSSING = "that would cross one of your loops";
    public static final String REFUSED_SAME_LOOP = "both places are already on that loop";
    public static final String REFUSED_NOT_ON_LOOP = "pick a place on one of your loops";
    public static final String REFUSED_ALREADY_ON_LOOP = "that place is already on a loop; pipe the two loops";
    public static final String REFUSED_COLLAPSE = "pick places in two different knots of one network at this layer";
    public static final String REFUSED_UNCONNECTED = "start a loop at one of your bases";

    public final TradeMap map;
    public final RouteTable table;
    public final Company owner;
    public final EditHistory<RouteState> history = new EditHistory<>();
    /** Why the last edit was refused, empty after one that was kept. */
    public String lastRefusal = "";

    private final ArrayList<RouteOperation> operations = new ArrayList<>();
    private Knot[] knots = new Knot[0];

    /**
     * An empty network for a company over a level's places.
     *
     * @param map   the level's terrain and places
     * @param table the legs between every pair of places
     * @param owner the company whose loops these are
     */
    public RouteNetwork(TradeMap map, RouteTable table, Company owner) {
        this.map = map;
        this.table = table;
        this.owner = owner;
        restore(new RouteState(List.of()));
    }

    /**
     * A place's own knot, the leaf of the hierarchy.
     *
     * @param place a place
     * @return its singleton knot
     */
    public Knot knotOf(Place place) {
        return knots[place.index];
    }

    /**
     * The knot at a layer that holds a place: the whole network at layer 0, one of its children at 1.
     * A layer deeper than the place's hierarchy gives its deepest knot that is not the place alone.
     *
     * @param place a place on a loop
     * @param layer the layer
     * @return the knot, or the place's singleton when it is on no loop
     */
    public Knot knotAt(Place place, int layer) {
        Knot leaf = knots[place.index];
        Knot unit = leaf.top();
        for (int depth = 0; depth < layer; depth++) {
            Knot child = leaf.childOf(unit);
            if (child == leaf) {
                break;
            }
            unit = child;
        }
        return unit;
    }

    /**
     * The deepest layer any of the player's knots reaches.
     *
     * @return 0 for flat loops, more for piped networks
     */
    public int deepestLayer() {
        int deepest = 0;
        for (Knot leaf : knots) {
            deepest = Math.max(deepest, leaf.depth() - 1);
        }
        return deepest;
    }

    /**
     * The loop a place is on.
     *
     * @param place a place
     * @return its loop, or null when it is on none
     */
    public Loop loopOf(Place place) {
        for (Loop loop : owner.loops) {
            for (Place stop : loop.stops) {
                if (stop == place) {
                    return loop;
                }
            }
        }
        return null;
    }

    /**
     * Pipe two places: two lone places start an out-and-back loop when one is a base, a lone place and a
     * loop grow the loop, and two loops join by cutting one leg of each inside their knots at the layer.
     *
     * @param a     the first place clicked
     * @param b     the second place clicked
     * @param layer the layer to act at
     * @return true when the network changed; otherwise {@link #lastRefusal} says why
     */
    public boolean pipe(Place a, Place b, int layer) {
        boolean aOnLoop = knots[a.index].matchCount > 0;
        boolean bOnLoop = knots[b.index].matchCount > 0;
        if (!aOnLoop && !bOnLoop) {
            boolean fromBase = a instanceof City cityA && cityA.facility(owner) == Facility.BASE
                    || b instanceof City cityB && cityB.facility(owner) == Facility.BASE;
            if (!fromBase) {
                lastRefusal = REFUSED_UNCONNECTED;
                return false;
            }
            return apply("Pipe", new RouteOperation(Kind.PIPE_NEW, layer, a.index, b.index));
        }
        if (aOnLoop != bOnLoop) {
            return grow(aOnLoop ? a : b, aOnLoop ? b : a, layer);
        }
        if (knots[a.index].top() == knots[b.index].top()) {
            lastRefusal = REFUSED_SAME_LOOP;
            return false;
        }
        RouteOperation best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (Segment cutA : segmentsInside(knotAt(a, layer))) {
            for (Segment cutB : segmentsInside(knotAt(b, layer))) {
                Place firstA = placeOf(cutA.first), lastA = placeOf(cutA.last);
                Place firstB = placeOf(cutB.first), lastB = placeOf(cutB.last);
                int delta = table.cost(firstA, firstB) + table.cost(lastA, lastB) - table.cost(firstA, lastA)
                        - table.cost(firstB, lastB);
                if (delta < bestDelta && !crossesLoops(firstA, firstB, cutA, cutB)
                        && !crossesLoops(lastA, lastB, cutA, cutB)) {
                    bestDelta = delta;
                    best = new RouteOperation(Kind.JOIN, layer, firstA.index, lastA.index, firstB.index, lastB.index);
                }
            }
        }
        if (best == null) {
            lastRefusal = REFUSED_CROSSING;
            return false;
        }
        return apply("Join", best);
    }

    /**
     * Grow the knot at a layer holding {@code loopPlace} by a lone place, inserted at that knot's
     * cheapest leg whose new legs cross none of the player's loops.
     *
     * @param loopPlace a place on the loop
     * @param lone      the lone place to add
     * @param layer     the layer to act at
     * @return true when the loop grew; otherwise {@link #lastRefusal} says why
     */
    public boolean grow(Place loopPlace, Place lone, int layer) {
        if (knots[loopPlace.index].matchCount == 0) {
            lastRefusal = REFUSED_NOT_ON_LOOP;
            return false;
        }
        if (knots[lone.index].matchCount > 0) {
            lastRefusal = REFUSED_ALREADY_ON_LOOP;
            return false;
        }
        Segment cut = cheapestGrowCut(loopPlace, lone, layer);
        if (cut == null) {
            lastRefusal = REFUSED_CROSSING;
            return false;
        }
        return apply("Grow",
                new RouteOperation(Kind.GROW, layer, lone.index, placeOf(cut.first).index, placeOf(cut.last).index));
    }

    /**
     * Collapse the chain of knots between two places inside the knot at a layer holding both: one pipe
     * leg of each link is dropped and the chain's ends are joined.
     *
     * @param start a place in the knot the chain starts at
     * @param end   a place in the knot it ends at
     * @param layer the layer whose knot holds the chain
     * @return true when the knot changed; otherwise {@link #lastRefusal} says why
     */
    public boolean collapse(Place start, Place end, int layer) {
        Knot parent = knotAt(start, layer);
        if (knots[start.index].matchCount == 0 || parent != knotAt(end, layer) || parent.isSingleton()
                || knots[start.index].childOf(parent) == knots[end.index].childOf(parent)) {
            lastRefusal = REFUSED_COLLAPSE;
            return false;
        }
        return apply("Collapse", new RouteOperation(Kind.COLLAPSE, layer, start.index, end.index));
    }

    /**
     * Every lone place the knot at a layer holding {@code loopPlace} could grow to, with the cells it
     * adds and the loop's stops once grown.
     *
     * @param loopPlace a place on the loop
     * @param layer     the layer to act at
     * @return the targets, empty when the place is on no loop
     */
    public List<GrowTarget> growTargets(Place loopPlace, int layer) {
        ArrayList<GrowTarget> targets = new ArrayList<>();
        Loop loop = loopOf(loopPlace);
        if (loop == null) {
            return targets;
        }
        for (Place lone : map.places) {
            if (knots[lone.index].matchCount > 0) {
                continue;
            }
            Segment cut = cheapestGrowCut(loopPlace, lone, layer);
            if (cut == null) {
                continue;
            }
            Place first = placeOf(cut.first), last = placeOf(cut.last);
            Place[] grown = new Place[loop.stops.length + 1];
            int write = 0;
            for (int k = 0; k < loop.stops.length; k++) {
                grown[write++] = loop.stops[k];
                Place next = loop.stops[(k + 1) % loop.stops.length];
                boolean cutHere = (loop.stops[k] == first && next == last) || (loop.stops[k] == last && next == first);
                if (cutHere && write == k + 1) {
                    grown[write++] = lone;
                }
            }
            if (write < grown.length) {
                grown[write] = lone;
            }
            targets.add(new GrowTarget(lone,
                    table.cost(first, lone) + table.cost(lone, last) - table.cost(first, last), grown));
        }
        return targets;
    }

    /**
     * Undo the last route edit by restoring the snapshot before it.
     *
     * @return true when there was one to undo
     */
    public boolean undo() {
        RouteState before = history.undo();
        if (before != null) {
            restore(before);
        }
        return before != null;
    }

    /**
     * Redo the last undone route edit.
     *
     * @return true when there was one to redo
     */
    public boolean redo() {
        RouteState after = history.redo();
        if (after != null) {
            restore(after);
        }
        return after != null;
    }

    /**
     * Apply a new operation and keep it only when every place on a loop still walks round a whole loop
     * and no two legs cross; a kept edit goes on the undo history.
     *
     * @param name      the edit's name for the history
     * @param operation the operation
     * @return true when kept
     */
    private boolean apply(String name, RouteOperation operation) {
        RouteState before = snapshot();
        operations.add(operation);
        boolean valid;
        try {
            perform(operation);
            valid = rebuildLoops();
            for (int i = 0; valid && i < owner.loops.size(); i++) {
                Loop loop = owner.loops.get(i);
                for (int k = 0; valid && k < loop.stops.length; k++) {
                    valid = !crossesLoops(loop.stops[k], loop.stops[(k + 1) % loop.stops.length], null, null);
                }
            }
        } catch (RuntimeException failure) {
            valid = false;
        }
        if (!valid) {
            restore(before);
            lastRefusal = REFUSED_CROSSING;
            return false;
        }
        lastRefusal = "";
        history.push(name, before, snapshot());
        return true;
    }

    /**
     * The network as it stands, for undo.
     *
     * @return the operations
     */
    private RouteState snapshot() {
        return new RouteState(List.copyOf(operations));
    }

    /**
     * Rebuild the network from a snapshot: one fresh singleton knot per place, a distance matrix of leg
     * costs so the knots price edges in grid cells, then the operation list replayed.
     *
     * @param state the operations to restore
     */
    private void restore(RouteState state) {
        operations.clear();
        operations.addAll(state.operations());
        Shell shell = new Shell();
        knots = new Knot[map.places.size()];
        ArrayList<PointND> points = new ArrayList<>();
        for (Place place : map.places) {
            Vector2f at = map.grid.dualPoints().get(place.cell);
            PointND.Float point = new PointND.Float(at.x, at.y);
            point.setID(place.index);
            shell.add(point);
            points.add(point);
        }
        for (int k = 0; k < points.size(); k++) {
            knots[k] = new Knot(points.get(k), shell);
        }
        shell.distanceMatrix = new DistanceMatrix(shell.toPointSet());
        double[][] matrix = shell.distanceMatrix.getMatrix();
        ArrayList<PointND> ordered = shell.distanceMatrix.getPoints();
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = 0; j < ordered.size(); j++) {
                matrix[i][j] = table.cost(map.places.get(ordered.get(i).getID()),
                        map.places.get(ordered.get(j).getID()));
            }
        }
        for (RouteOperation operation : operations) {
            perform(operation);
        }
        rebuildLoops();
    }

    /**
     * Carry out one operation on the knots, then drop dead segments from every knot's lists so each
     * knot lists only the legs still travelled.
     *
     * @param operation the operation
     */
    private void perform(RouteOperation operation) {
        int[] at = operation.places();
        switch (operation.kind()) {
        case PIPE_NEW -> Knot.pipeSimple(knots[at[0]], knots[at[1]]);
        case GROW -> {
            Segment cut = segmentBetween(knots[at[1]], knots[at[2]]);
            HashSet<Knot> aboveFirst = new HashSet<>();
            for (Knot k = cut.first; k != null; k = k.topGroupKnot) {
                aboveFirst.add(k);
            }
            Knot owningKnot = cut.last;
            while (!aboveFirst.contains(owningKnot)) {
                owningKnot = owningKnot.topGroupKnot;
            }
            owningKnot.grow(knots[at[0]], cut);
        }
        case JOIN -> {
            Segment cutA = segmentBetween(knots[at[0]], knots[at[1]]);
            Segment cutB = segmentBetween(knots[at[2]], knots[at[3]]);
            Knot.pipe(cutA.first.top(), cutB.first.top(), cutA, cutB);
        }
        case COLLAPSE -> {
            Knot parent = knotAt(map.places.get(at[0]), operation.layer());
            parent.collapse(knots[at[0]].childOf(parent), knots[at[1]].childOf(parent));
        }
        default -> throw new IllegalArgumentException("unknown route operation " + operation.kind());
        }
        HashSet<Knot> touched = new HashSet<>();
        for (Knot leaf : knots) {
            for (Knot k = leaf.topGroupKnot; k != null && touched.add(k); k = k.topGroupKnot) {
                k.manifoldSegments.removeIf(s -> s.first.s1 != s && s.first.s2 != s);
                k.sortedSegments.removeIf(s -> s.first.s1 != s && s.first.s2 != s);
            }
        }
    }

    /**
     * Rebuild the company's loop list from the knots: each loop's places in travel order starting at a
     * base, running one wagon per base on it.
     *
     * @return false when some place on a loop does not walk round a whole loop
     */
    private boolean rebuildLoops() {
        owner.loops.clear();
        HashSet<Place> seen = new HashSet<>();
        boolean whole = true;
        for (Place start : map.places) {
            Knot first = knots[start.index];
            if (first.matchCount == 0 || seen.contains(start)) {
                continue;
            }
            ArrayList<Place> order = new ArrayList<>();
            Knot current = first;
            Segment arrivedBy = null;
            do {
                order.add(placeOf(current));
                Segment leave = current.s1 != arrivedBy && current.s1 != null ? current.s1 : current.s2;
                if (leave == null || order.size() > map.places.size()) {
                    order = null;
                    break;
                }
                arrivedBy = leave;
                current = leave.getOther(current);
            } while (current != first);
            if (order == null) {
                whole = false;
                continue;
            }
            int rotation = 0;
            for (int k = 0; k < order.size(); k++) {
                if (order.get(k) instanceof City city && city.facility(owner) == Facility.BASE) {
                    rotation = k;
                    break;
                }
            }
            Collections.rotate(order, -rotation);
            seen.addAll(order);
            owner.loops.add(new Loop(owner, order.toArray(new Place[0])));
        }
        for (Knot leaf : knots) {
            whole &= leaf.matchCount == 0 || seen.contains(placeOf(leaf));
        }
        return whole;
    }

    /**
     * The legs still travelled inside a knot: both ends among its places.
     *
     * @param knot a knot of the hierarchy
     * @return its live segments, each once
     */
    private ArrayList<Segment> segmentsInside(Knot knot) {
        ArrayList<Segment> segments = new ArrayList<>();
        if (knot.isSingleton()) {
            return segments;
        }
        HashSet<Knot> inside = new HashSet<>(knot.knotPointsFlattened);
        for (Knot leaf : knot.knotPointsFlattened) {
            for (Segment s : new Segment[] { leaf.s1, leaf.s2 }) {
                if (s != null && inside.contains(s.getOther(leaf)) && segments.stream().noneMatch(known -> known == s)) {
                    segments.add(s);
                }
            }
        }
        return segments;
    }

    /**
     * The cheapest leg of the knot at a layer holding {@code loopPlace} to insert {@code lone} into
     * without crossing a loop.
     *
     * @param loopPlace a place on the loop
     * @param lone      the place to insert
     * @param layer     the layer to act at
     * @return the segment to cut, or null when every insertion crosses
     */
    private Segment cheapestGrowCut(Place loopPlace, Place lone, int layer) {
        Segment best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (Segment cut : segmentsInside(knotAt(loopPlace, layer))) {
            Place first = placeOf(cut.first), last = placeOf(cut.last);
            int delta = table.cost(first, lone) + table.cost(lone, last) - table.cost(first, last);
            if (delta < bestDelta && !crossesLoops(first, lone, cut, null) && !crossesLoops(lone, last, cut, null)) {
                bestDelta = delta;
                best = cut;
            }
        }
        return best;
    }

    /**
     * Whether a leg between two places would properly cross a leg of the player's loops, ignoring legs
     * that share a place with it and the segments about to be cut.
     *
     * @param a    one end of the leg
     * @param b    the other end
     * @param cutA a segment being removed, or null
     * @param cutB another segment being removed, or null
     * @return true when the leg crosses
     */
    private boolean crossesLoops(Place a, Place b, Segment cutA, Segment cutB) {
        Vector2f pa = map.grid.dualPoints().get(a.cell), pb = map.grid.dualPoints().get(b.cell);
        for (Loop loop : owner.loops) {
            for (int k = 0; k < loop.stops.length; k++) {
                Place u = loop.stops[k], v = loop.stops[(k + 1) % loop.stops.length];
                boolean cut = false;
                for (Segment s : new Segment[] { cutA, cutB }) {
                    cut |= s != null && ((placeOf(s.first) == u && placeOf(s.last) == v)
                            || (placeOf(s.first) == v && placeOf(s.last) == u));
                }
                if (cut || u == a || u == b || v == a || v == b) {
                    continue;
                }
                Vector2f pu = map.grid.dualPoints().get(u.cell), pv = map.grid.dualPoints().get(v.cell);
                float side1 = Math.signum((pb.x - pa.x) * (pu.y - pa.y) - (pb.y - pa.y) * (pu.x - pa.x));
                float side2 = Math.signum((pb.x - pa.x) * (pv.y - pa.y) - (pb.y - pa.y) * (pv.x - pa.x));
                float side3 = Math.signum((pv.x - pu.x) * (pa.y - pu.y) - (pv.y - pu.y) * (pa.x - pu.x));
                float side4 = Math.signum((pv.x - pu.x) * (pb.y - pu.y) - (pv.y - pu.y) * (pb.x - pu.x));
                if (side1 * side2 < 0 && side3 * side4 < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The live segment from one place's knot to the next, preferring that exact direction.
     *
     * @param first the segment's first knot
     * @param last  its last knot
     * @return the segment
     */
    private static Segment segmentBetween(Knot first, Knot last) {
        Segment either = null;
        for (Segment s : new Segment[] { first.s1, first.s2 }) {
            if (s != null && s.first == first && s.last == last) {
                return s;
            }
            if (s != null && s.getOther(first) == last) {
                either = s;
            }
        }
        if (either == null) {
            throw new IllegalStateException("no segment between " + first.p.getID() + " and " + last.p.getID());
        }
        return either;
    }

    /**
     * The place a singleton knot stands for.
     *
     * @param leaf a singleton knot
     * @return its place
     */
    private Place placeOf(Knot leaf) {
        return map.places.get(leaf.p.getID());
    }
}

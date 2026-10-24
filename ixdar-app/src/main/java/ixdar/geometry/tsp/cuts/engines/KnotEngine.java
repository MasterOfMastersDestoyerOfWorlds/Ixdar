package ixdar.geometry.tsp.cuts.engines;

import java.util.ArrayList;
import java.util.Arrays;

import ixdar.geometry.tsp.MultipleCyclesFoundException;
import ixdar.geometry.tsp.cuts.CutMatch;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.numeric.graph.UnionFind;

public class KnotEngine {
    public ArrayList<Knot> unvisited;
    public ArrayList<Knot> knots;

    /** Parent knot id per knot id in the {@link UnionFind} forest of knot groups. */
    public int[] groupParent = new int[0];

    /** Rank per knot id, which decides the surviving root, and so the group id, of a merge. */
    public int[] groupRank = new int[0];

    int halfKnotCount = 0;
    int sameKnotPointCount = 0;

    private Shell shell;

    /**
     * Build an engine that grows knots within {@code shell}'s point set.
     *
     * @param shell shell whose {@code pointMap} seeds the unvisited knot list
     */
    public KnotEngine(Shell shell) {
        this.shell = shell;
        unvisited = new ArrayList<Knot>();
    }

    /**
     * Add a knot to the forest as its own group, growing the arrays to cover its id. A reused id
     * keeps its rank.
     *
     * @param knot knot whose id becomes its own parent
     */
    public void addGroup(Knot knot) {
        groupParent = UnionFind.grow(groupParent, knot.id);
        groupRank = Arrays.copyOf(groupRank, groupParent.length);
        groupParent[knot.id] = knot.id;
    }

    /**
     * Iteratively grow knots from singleton points, running up to {@code layers}
     * passes of {@link #findKnots} until at most one knot remains.
     *
     * @param layers maximum number of merge passes to perform
     * @param sortedSegments segments ordered for use by the per-pass merge
     * @throws MultipleCyclesFoundException if a merge introduces more than one cycle
     * @return the surviving (still-growable) knots after the last pass
     */
    public ArrayList<Knot> createKnots(int layers, ArrayList<Segment> sortedSegments)
            throws MultipleCyclesFoundException {

        unvisited = new ArrayList<Knot>();
        unvisited.addAll(shell.pointMap.values());
        int idx = 0;
        while (unvisited.size() > 1 && idx != layers) {
            unvisited = findKnots(sortedSegments, unvisited);
            idx++;
        }
        return unvisited;
    }

    /**
     * One merge pass: for every disjoint group, find the smallest-delta
     * {@link CutMatch} to a different group and join the mutually-best pairs,
     * either creating a new knot or growing an existing one by a singleton.
     *
     * @param sortedSegments segments used by the underlying delta computation
     * @param knots current list of growable knots; mutated in place
     * @throws MultipleCyclesFoundException if a merge introduces more than one cycle
     * @return the updated {@code knots} list with merged entries removed
     */
    public ArrayList<Knot> findKnots(ArrayList<Segment> sortedSegments, ArrayList<Knot> knots)
            throws MultipleCyclesFoundException {

        boolean updated = true;
        while (updated) {
            updated = false;
            int size = groupParent.length;
            CutMatch[] smallestMoveList = new CutMatch[size];
            int[] smallestMoveOtherGroup = new int[size];
            Knot[] knot1ByGroup = new Knot[size];
            Knot[] knot2ByGroup = new Knot[size];

            CutMatch smallestMove = new CutMatch(null, shell, null);
            smallestMove.delta = Double.MAX_VALUE;
            for (Knot k : knots) {
                for (Knot o : knots) {
                    int kGroup = UnionFind.find(groupParent, k.id);
                    int oGroup = UnionFind.find(groupParent, o.id);
                    if (!(kGroup == oGroup)) {
                        CutMatch move = k.getDeltaDistTo(o);
                        if (move != null) {
                            smallestMove = move;
                            if (smallestMoveList[kGroup] == null) {
                                smallestMoveList[kGroup] = move;
                                smallestMoveOtherGroup[kGroup] = oGroup;
                                knot1ByGroup[kGroup] = k;
                                knot2ByGroup[kGroup] = o;
                            } else if (move.delta < smallestMoveList[kGroup].delta) {
                                smallestMoveList[kGroup] = move;
                                smallestMoveOtherGroup[kGroup] = oGroup;
                                knot1ByGroup[kGroup] = k;
                                knot2ByGroup[kGroup] = o;
                            }
                            if (smallestMoveList[oGroup] == null) {
                                smallestMoveList[oGroup] = move;
                                smallestMoveOtherGroup[oGroup] = kGroup;
                                knot1ByGroup[oGroup] = k;
                                knot2ByGroup[oGroup] = o;
                            } else if (move.delta < smallestMoveList[oGroup].delta) {
                                smallestMoveList[oGroup] = move;
                                smallestMoveOtherGroup[oGroup] = kGroup;
                                knot1ByGroup[oGroup] = k;
                                knot2ByGroup[oGroup] = o;
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < size; i++) {
                if (smallestMoveList[i] == null) {
                    continue;
                }
                int otherGroup = smallestMoveOtherGroup[i];
                if (smallestMoveOtherGroup[otherGroup] != i) {
                    continue;
                }
                Knot k1 = knot1ByGroup[i];
                Knot k2 = knot2ByGroup[i];
                boolean isSingle1 = k1.isSingleton();
                boolean isSingle2 = k2.isSingleton();
                CutMatch smallestCutMatch = smallestMoveList[i];
                smallestMoveList[otherGroup] = null;
                if (isSingle1 && isSingle2) {
                    knots.add(new Knot(smallestCutMatch, k1, k2));
                } else if (isSingle1 || isSingle2) {
                    Knot p = isSingle1 ? k1 : k2;
                    Knot k = isSingle1 ? k2 : k1;
                    k.growByPoint(smallestCutMatch, p);
                } else {
                    knots.add(new Knot(smallestCutMatch, k1, k2));
                }
                if (k1.matchCount == k1.maxMatches) {
                    knots.remove(k1);
                }
                if (k2.matchCount == k2.maxMatches) {
                    knots.remove(k2);
                }
                updated = true;
            }
        }
        return knots;
    }

}

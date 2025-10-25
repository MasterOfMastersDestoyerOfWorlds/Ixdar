package ixdar.geometry.cuts.engines;

import java.util.ArrayList;

import ixdar.common.exceptions.MultipleCyclesFoundException;
import ixdar.geometry.cuts.CutMatch;
import ixdar.geometry.cuts.DisjointUnionSets;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

public class KnotEngine {

    private Shell shell;
    public ArrayList<Knot> unvisited;

    int halfKnotCount = 0;
    int sameKnotPointCount = 0;
    public ArrayList<Knot> knots;

    public KnotEngine(Shell shell) {
        this.shell = shell;
        unvisited = new ArrayList<Knot>();
    }

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

    public ArrayList<Knot> findKnots(ArrayList<Segment> sortedSegments, ArrayList<Knot> knots)
            throws MultipleCyclesFoundException {

        boolean updated = true;
        DisjointUnionSets unionSets = Knot.unionSet;
        while (updated) {
            updated = false;
            int size = unionSets.totalNumGroups();
            CutMatch[] smallestMoveList = new CutMatch[size];
            int[] smallestMoveOtherGroup = new int[size];
            Knot[] knot1ByGroup = new Knot[size];
            Knot[] knot2ByGroup = new Knot[size];

            CutMatch smallestMove = new CutMatch(null, shell, null);
            smallestMove.delta = Double.MAX_VALUE;
            for (Knot k : knots) {
                for (Knot o : knots) {
                    int kGroup = Knot.unionSet.find(k);
                    int oGroup = Knot.unionSet.find(o);
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

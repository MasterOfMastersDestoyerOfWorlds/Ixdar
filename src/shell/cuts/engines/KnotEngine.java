package shell.cuts.engines;

import java.util.ArrayList;

import shell.cuts.CutMatch;
import shell.cuts.DisjointUnionSets;
import shell.exceptions.MultipleCyclesFoundException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.shell.Shell;

public class KnotEngine {

    private Shell shell;
    public ArrayList<Knot> unvisited;

    int halfKnotCount = 0;
    int sameKnotPointCount = 0;
    private ArrayList<Knot> visited;
    public ArrayList<Knot> knots;

    public KnotEngine(Shell shell) {
        this.shell = shell;
        unvisited = new ArrayList<Knot>();
        visited = new ArrayList<Knot>();
    }

    public ArrayList<Knot> createKnots(int layers, ArrayList<Segment> sortedSegments)
            throws MultipleCyclesFoundException {

        visited = new ArrayList<Knot>();
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
        while (updated) {
            updated = false;
            CutMatch smallestMove = new CutMatch(null, shell, null);
            smallestMove.delta = Double.MAX_VALUE;
            Knot k1 = null;
            Knot k2 = null;
            for (Knot k : knots) {
                for (Knot o : knots) {
                    if (k.id != o.id) {
                        CutMatch move = k.getDeltaDistTo(o);
                        if (move != null && move.delta < smallestMove.delta) {
                            smallestMove = move;
                            k1 = k;
                            k2 = o;
                        }
                    }
                }
            }
            if (k1 != null) {
                knots.add(new Knot(smallestMove, k1, k2));
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

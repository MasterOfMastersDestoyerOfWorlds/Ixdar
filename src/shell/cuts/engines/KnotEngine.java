package shell.cuts.engines;

import java.util.ArrayList;

import shell.cuts.DisjointUnionSets;
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

    public ArrayList<Knot> createKnots(int layers, ArrayList<Segment> sortedSegments) {

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

    public ArrayList<Knot> findKnots(ArrayList<Segment> sortedSegments, ArrayList<Knot> knots) {
        DisjointUnionSets unionSet = new DisjointUnionSets(knots);
        int segmentNumber = 0;
        for (Segment s : sortedSegments) {
            Knot k1 = s.first.topKnot;
            Knot k2 = s.last.topKnot;
            if (k1.isFull() || k2.isFull() || k1.id == k2.id) {
                continue;
            }
            if ((k1.m1 != null && k1.m1.id == k2.id) || (k1.m2 != null && k1.m2.id == k2.id)) {
                continue;
            }
            try {
                k1.setMatch(k2, s);
                k2.setMatch(k1, s);
                int groupId = unionSet.union(k1, k2);
                int gUnmatched = unionSet.findUnmatched(groupId);
                if (gUnmatched <= 0) {
                    // found knot
                    ArrayList<Knot> runList = k1.getRunList();
                    Knot k = new Knot(runList, shell);
                    unionSet.addSet(k);
                    knots.add(k);
                }
                segmentNumber++;
            } catch(AssertionError e) {
                float z = 0;
            }
        }
        return knots;
    }

}

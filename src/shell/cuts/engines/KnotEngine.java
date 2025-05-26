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
            if (k1.id == k2.id) {
                continue;
            }
            boolean sameGroup = unionSet.sameGroup(k1, k2);
            boolean k1Full = k1.isFull();
            boolean k2Full = k2.isFull();
            if (k1Full && k2Full) {
                continue;
            }
            if (!sameGroup && (k1Full || k2Full)) {
                continue;
            }
            if ((k1.m1 != null && k1.m1.id == k2.id) || (k1.m2 != null && k1.m2.id == k2.id)) {
                continue;
            }
            try {
                // if we are making a knot the two ends are always in the same group.
                if (sameGroup) {
                    // found knot
                    Knot kEnd = k1Full? k2 : k1;
                    Knot kMid = k1Full? k1 : k2;
                    ArrayList<Knot> runList = kEnd.getRunList(kMid);
                    Knot k = new Knot(runList, shell);
                    if(kMid.m2 == null){
                        kEnd.setMatch(kMid, s);
                        kMid.setMatch(kEnd, s);
                    }else{
                        Knot temp = kMid.m2;
                        kEnd.setMatch(kMid, s);
                        kMid.setMatch(kEnd, s);
                        if(temp.m2 != null && temp.m2.id == kMid.id){
                            temp.m2 = k;
                            k.setMatch(temp, temp.s2);
                        }else if(temp.m1 != null && temp.m1.id == kMid.id){
                            temp.m1 = k;
                            k.setMatch(temp, temp.s1);
                        }
                    }
                    unionSet.addSet(k);
                    knots.add(k);
                } else {
                    k1.setMatch(k2, s);
                    k2.setMatch(k1, s);
                    unionSet.union(k1, k2);
                }
                segmentNumber++;
            } catch (AssertionError e) {
                float z = 0;
            }
        }
        return knots;
    }

}

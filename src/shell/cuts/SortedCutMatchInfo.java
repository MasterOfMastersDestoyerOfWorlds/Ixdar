package shell.cuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import shell.cuts.route.RouteMap;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

class SortedCutMatchInfo {
    ArrayList<CutMatchList> sortedCutMatchLists;
    HashMap<Long, ArrayList<CutMatchList>> sortedCutMatchListsBySegment;
    HashMap<Integer, ArrayList<CutMatchList>> sortedCutMatchListsByKnotPoint;
    public HashMap<Long, HashMap<Long, RouteMap>> routeMapBySegmentId;

    public SortedCutMatchInfo() {
        sortedCutMatchLists = new ArrayList<>();
        sortedCutMatchListsBySegment = new HashMap<>();
        sortedCutMatchListsByKnotPoint = new HashMap<>();
        routeMapBySegmentId = new HashMap<>();
    }

    public void add(CutMatchList cutMatch, Segment cutSegment, VirtualPoint knotPoint) {
        sortedCutMatchLists.add(cutMatch);
        ArrayList<CutMatchList> segmentList = sortedCutMatchListsBySegment.getOrDefault(cutSegment.id,
                new ArrayList<>());
        segmentList.add(cutMatch);
        sortedCutMatchListsBySegment.put(cutSegment.id, segmentList);

        ArrayList<CutMatchList> knotPointList = sortedCutMatchListsByKnotPoint.getOrDefault(knotPoint.id,
                new ArrayList<>());
        knotPointList.add(cutMatch);
        sortedCutMatchListsByKnotPoint.put(knotPoint.id, knotPointList);
    }

    public void sort() {
        Collections.sort(sortedCutMatchLists, new CutMatchList.CutMatchListComparator());
        for (ArrayList<CutMatchList> lst : sortedCutMatchListsBySegment.values()) {
            Collections.sort(lst, new CutMatchList.CutMatchListComparator());
        }
        for (ArrayList<CutMatchList> lst : sortedCutMatchListsByKnotPoint.values()) {
            Collections.sort(lst, new CutMatchList.CutMatchListComparator());
        }
    }
}
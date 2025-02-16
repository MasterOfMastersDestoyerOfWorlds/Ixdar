package shell.cuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import shell.Toggle;
import shell.cuts.route.RouteMap;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.main.Main;

public class SortedCutMatchInfo {
    public ArrayList<CutMatchList> sortedCutMatchLists;
    double minShortestDeltaBySegment;
    double maxShortestDeltaBySegment;
    public HashMap<Long, ArrayList<CutMatchList>> sortedCutMatchListsBySegment;
    public HashMap<Integer, ArrayList<CutMatchList>> sortedCutMatchListsByKnotPoint;
    public HashMap<Long, HashMap<Long, RouteMap>> routeMapBySegmentId;
    public Clockwork cw;

    public SortedCutMatchInfo() {
        sortedCutMatchLists = new ArrayList<>();
        sortedCutMatchListsBySegment = new HashMap<>();
        sortedCutMatchListsByKnotPoint = new HashMap<>();
        routeMapBySegmentId = new HashMap<>();
        minShortestDeltaBySegment = Double.MAX_VALUE;
        maxShortestDeltaBySegment = Double.MIN_VALUE;
        cw = null;
    }

    public void add(CutMatchList cutMatch, Segment cutSegment, VirtualPoint knotPoint) {
        sortedCutMatchLists.add(cutMatch);
        long orderedSegmentId = Segment.idTransformOrdered(cutSegment, knotPoint);
        ArrayList<CutMatchList> segmentList = sortedCutMatchListsBySegment.getOrDefault(orderedSegmentId,
                new ArrayList<>());
        segmentList.add(cutMatch);
        sortedCutMatchListsBySegment.put(orderedSegmentId, segmentList);

        ArrayList<CutMatchList> knotPointList = sortedCutMatchListsByKnotPoint.getOrDefault(knotPoint.id,
                new ArrayList<>());
        knotPointList.add(cutMatch);
        sortedCutMatchListsByKnotPoint.put(knotPoint.id, knotPointList);
    }

    public void sort() {
        Collections.sort(sortedCutMatchLists, new CutMatchList.CutMatchListComparator());
        for (ArrayList<CutMatchList> lst : sortedCutMatchListsBySegment.values()) {
            Collections.sort(lst, new CutMatchList.CutMatchListComparator());
            CutMatchList shortest = lst.get(0);
            if (shortest.delta < minShortestDeltaBySegment) {
                minShortestDeltaBySegment = shortest.delta;
            }
            if (shortest.delta > maxShortestDeltaBySegment) {
                maxShortestDeltaBySegment = shortest.delta;
            }
        }
        for (ArrayList<CutMatchList> lst : sortedCutMatchListsByKnotPoint.values()) {
            Collections.sort(lst, new CutMatchList.CutMatchListComparator());
        }
    }

    public static CutMatchList findCutMatchList(VirtualPoint startCP, VirtualPoint startKP, VirtualPoint displayCP,
            VirtualPoint displayKP, Knot displayKnot) {
        HashMap<Integer, SortedCutMatchInfo> cutMatchLookup = Main.shell.cutEngine.sortedCutMatchInfoLookup;
        SortedCutMatchInfo cutMatchInfo = cutMatchLookup.get(displayKnot.id);
        Long segmentId = Segment.idTransformOrdered(startCP.id, startKP.id);
        ArrayList<CutMatchList> cmls = cutMatchInfo.sortedCutMatchListsBySegment.get(segmentId);
        for (CutMatchList cml : cmls) {
            CutMatch cm = cml.getCutMatch();
            if (cm.c.upperCutPoint.id == displayCP.id && cm.c.upperKnotPoint.id == displayKP.id) {
                return cml;
            }
        }
        return null;
    }

    public static CutMatchList findCutMatchList(VirtualPoint displayCP, VirtualPoint displayKP) {
        HashMap<Integer, SortedCutMatchInfo> cutMatchLookup = Main.shell.cutEngine.sortedCutMatchInfoLookup;
        Knot displayKnot = null;
        for (Knot k : Main.knotsDisplayed) {
            if (k.contains(displayCP)) {
                displayKnot = k;
                break;
            }
        }
        if (displayKnot == null) {
            return null;
        }
        SortedCutMatchInfo cutMatchInfo = cutMatchLookup.get(displayKnot.id);
        Long segmentId = Segment.idTransformOrdered(displayCP.id, displayKP.id);
        return findCutMatchList(segmentId, cutMatchInfo);
    }

    public static CutMatchList findCutMatchList(Long segmentId, SortedCutMatchInfo cutMatchInfo) {
        if (cutMatchInfo == null) {
            return null;
        }
        ArrayList<CutMatchList> cmls = cutMatchInfo.sortedCutMatchListsBySegment.get(segmentId);
        if (cmls == null) {
            return null;
        }
        if (Toggle.KnotSurfaceViewSimpleCut.value) {
            return cmls.get(0);
        } else {
            CutMatchList retVal = null;
            for (CutMatchList cml : cmls) {
                CutMatch cm = cml.getCutMatch();
                if (cm.cutSegments.size() == 1) {
                    continue;
                }
                retVal = cml;
                break;
            }
            return retVal;
        }
    }

    public static CutMatchList findCutMatchList(Long startSegmentId, Long endSegmentId,
            SortedCutMatchInfo cutMatchInfo) {
        ArrayList<CutMatchList> cmls = cutMatchInfo.sortedCutMatchListsBySegment.get(startSegmentId);
        CutMatchList retVal = null;
        for (CutMatchList cml : cmls) {
            CutMatch cm = cml.getCutMatch();
            long id = Segment.idTransformOrdered(cm.c.upperCutSegment, cm.c.upperKnotPoint);
            if (endSegmentId == id) {
                retVal = cml;
                break;
            }
        }
        return retVal;
    }

    public double getMinShortestBySegment() {
        return minShortestDeltaBySegment;
    }

    public double getMaxShortestBySegment() {
        return maxShortestDeltaBySegment;
    }
}
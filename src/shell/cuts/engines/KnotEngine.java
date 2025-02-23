package shell.cuts.engines;

import java.util.ArrayList;

import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Run;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;
import shell.utils.RunListUtils;

public class KnotEngine {

    private Shell shell;
    public ArrayList<VirtualPoint> unvisited;

    int halfKnotCount = 0;
    int sameKnotPointCount = 0;
    private ArrayList<VirtualPoint> visited;

    public KnotEngine(Shell shell) {
        this.shell = shell;
        unvisited = new ArrayList<VirtualPoint>();
        visited = new ArrayList<VirtualPoint>();
    }

    public ArrayList<VirtualPoint> createKnots(int layers) {

        visited = new ArrayList<VirtualPoint>();
        unvisited = new ArrayList<VirtualPoint>();
        unvisited.addAll(shell.pointMap.values());
        int idx = 0;
        while (unvisited.size() > 1 && idx != layers) {
            unvisited = findKnots();
            idx++;
        }
        return unvisited;
    }

    public ArrayList<VirtualPoint> findKnots() {
        ArrayList<VirtualPoint> knots = new ArrayList<>();
        @SuppressWarnings("unchecked")
        ArrayList<VirtualPoint> toVisit = (ArrayList<VirtualPoint>) unvisited.clone();
        ArrayList<VirtualPoint> runList = new ArrayList<>();
        VirtualPoint mainPoint = toVisit.get(0);

        boolean endpointReached = false;
        VirtualPoint endPoint1 = null;
        VirtualPoint endPoint2 = null;
        while (toVisit.size() > 0 || runList.size() > 0) {
            toVisit.remove(mainPoint);
            if (mainPoint.id == 40) {
                float z = 0;
            }
            Segment potentialSegment1 = mainPoint.getPointer(1);
            Point pointer1 = (Point) potentialSegment1.getOtherKnot(mainPoint.topGroup);

            Segment potentialSegment11 = pointer1.topGroup.getPointer(1);
            Point pointer11 = (Point) potentialSegment11.getOtherKnot(pointer1.topGroup);

            Segment potentialSegment12 = pointer1.topGroup.getPointer(2);
            Point pointer12 = (Point) potentialSegment12.getOtherKnot(pointer1.topGroup);

            Segment potentialSegment2 = mainPoint.getPointer(2);
            Point pointer2 = (Point) potentialSegment2.getOtherKnot(mainPoint.topGroup);

            Segment potentialSegment21 = pointer2.topGroup.getPointer(1);
            Point pointer21 = (Point) potentialSegment21.getOtherKnot(pointer2.topGroup);

            Segment potentialSegment22 = pointer2.topGroup.getPointer(2);
            Point pointer22 = (Point) potentialSegment22.getOtherKnot(pointer2.topGroup);

            VirtualPoint vp1 = pointer1.topGroup;
            VirtualPoint vp11 = pointer11.topGroup;
            VirtualPoint vp12 = pointer12.topGroup;
            VirtualPoint vp2 = pointer2.topGroup;
            VirtualPoint vp21 = pointer21.topGroup;
            VirtualPoint vp22 = pointer22.topGroup;
            Point matchEndPoint = null;
            Point matchBasePoint = null;
            VirtualPoint matchPoint = null;

            Segment matchSegment = null;
            boolean inKnots1 = knots.contains(vp1);
            boolean inKnots2 = knots.contains(vp2);
            // need to check that we haven't already match the end of the run in run case
            if (mainPoint.equals(vp11) && potentialSegment1.equals(potentialSegment11) && !inKnots1
                    && vp1.numMatches < 2 && (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
                matchPoint = vp1;
                matchEndPoint = pointer1;
                matchBasePoint = pointer11;
                matchSegment = potentialSegment11;
            } else if (mainPoint.equals(vp12) && potentialSegment1.equals(potentialSegment12) && !inKnots1
                    && vp1.numMatches < 2 && (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
                matchPoint = vp1;
                matchEndPoint = pointer1;
                matchBasePoint = pointer12;
                matchSegment = potentialSegment12;
            } else if ((mainPoint.equals(vp21)) && potentialSegment2.equals(potentialSegment21) && !inKnots2
                    && vp2.numMatches < 2 && (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer2))) {
                matchPoint = vp2;
                matchEndPoint = pointer2;
                matchBasePoint = pointer21;
                matchSegment = potentialSegment21;
            } else if (mainPoint.equals(vp22) && potentialSegment2.equals(potentialSegment22) && !inKnots2
                    && vp2.numMatches < 2 && (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer2))) {
                matchPoint = vp2;
                matchEndPoint = pointer2;
                matchBasePoint = pointer22;
                matchSegment = potentialSegment22;
            }
            if (matchPoint != null) {
                if (mainPoint.numMatches == 0) {
                    mainPoint.setMatch1(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
                } else {
                    mainPoint.checkAndSwap2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
                }
                if (matchPoint.numMatches == 0) {
                    matchPoint.setMatch1(mainPoint, matchBasePoint, matchEndPoint, matchSegment);
                } else {
                    matchPoint.checkAndSwap2(mainPoint, matchBasePoint, matchEndPoint, matchSegment);
                }
                mainPoint.numMatches++;
                matchPoint.numMatches++;
                if (!runList.contains(mainPoint)) {
                    if (endpointReached) {
                        runList.add(0, mainPoint);
                    } else {
                        runList.add(mainPoint);
                    }
                }
                if (endpointReached) {
                    runList.add(0, matchPoint);
                } else {
                    runList.add(matchPoint);
                }
                boolean mainIsFull = false;
                if (mainPoint.numMatches == 2) {
                    unvisited.remove(mainPoint);
                    visited.add(mainPoint);
                    mainIsFull = true;
                }
                if (matchPoint.numMatches == 2) {
                    unvisited.remove(matchPoint);
                    visited.add(matchPoint);
                    if (mainIsFull) {
                        VirtualPoint first = runList.get(0);
                        VirtualPoint last = runList.get(runList.size() - 1);

                        if (runList.contains(first.match1) && runList.contains(first.match2)
                                && runList.contains(last.match1) && runList.contains(last.match2)) {
                            if (runList.get(0).equals(runList.get(runList.size() - 1))) {
                                runList.remove(runList.size() - 1);
                            }
                            Knot k = new Knot(runList, shell);
                            knots.add(k);
                            runList = new ArrayList<>();
                            if (toVisit.size() == 0) {
                                return knots;
                            }
                            mainPoint = toVisit.get(0);
                            endpointReached = false;
                            continue;
                        }

                    }
                }

                mainPoint = matchPoint;
            } else {
                if (endpointReached) {
                    if (runList.size() == 2 && toVisit.size() == 0 && runList.get(0).isKnot && runList.get(1).isKnot) {

                        Knot k = new Knot(runList, shell);
                        knots.add(k);
                        return knots;
                    }
                    endPoint2 = mainPoint;

                    boolean knotFlag = false;
                    runList = RunListUtils.flattenRunPoints(runList, false);
                    RunListUtils.fixRunList(runList, runList.size() - 1);
                    int runListSize = 0;
                    for (VirtualPoint vp : runList) {
                        vp.topGroup = vp;
                        vp.group = vp;
                        runListSize += vp.size();
                        for (VirtualPoint kp : vp.knotPointsFlattened) {
                            kp.topGroup = vp;
                        }
                    }
                    if (runListSize > 2) {
                        if (RunListUtils.containsID(runList, 30)) {
                            float z = 0;
                        }
                        for (int i = 0; i < runList.size() && runList.size() > 1; i++) {

                            VirtualPoint vp = runList.get(i);
                            Segment s1 = vp.getFirstUnmatched(runList);
                            VirtualPoint other = s1.getOtherKnot(vp).topGroup;
                            Segment s2 = other.getFirstUnmatched(runList);
                            if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

                                knotFlag = true;
                                makeHalfKnot(runList, vp, vp.match1);
                                i = -1;

                            } else if (vp.isKnot && vp.match1.shouldKnotConsume((Knot) vp)) {

                                knotFlag = true;
                                makeHalfKnot(runList, vp.match1, vp);
                                i = -1;

                            } else if (vp.isKnot && vp.match2 != null && vp.match2.shouldKnotConsume((Knot) vp)) {

                                knotFlag = true;
                                makeHalfKnot(runList, vp.match2, vp);
                                i = -1;

                            } else if (s1.equals(s2) && runList.contains(other)) {
                                if (other.match1.isKnot && other.shouldJoinKnot((Knot) other.match1)) {
                                    knotFlag = true;
                                    makeHalfKnot(runList, other, other.match1);
                                    i = -1;
                                } else if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

                                    knotFlag = true;
                                    makeHalfKnot(runList, vp, vp.match1);
                                    i = -1;
                                } else if (!vp.isKnot && !other.isKnot) {
                                    knotFlag = true;
                                    makeHalfKnot(runList, vp, other);
                                    i = -1;
                                } else if (vp.isKnot && other.shouldJoinKnot((Knot) vp)) {
                                    knotFlag = true;
                                    makeHalfKnot(runList, vp, other);
                                    i = -1;
                                } else if (other.isKnot && vp.shouldJoinKnot((Knot) other)) {
                                    knotFlag = true;
                                    makeHalfKnot(runList, vp, other);
                                    i = -1;
                                } else if (vp.isConnector(vp.match1, other)) {
                                    knotFlag = true;
                                    makeHalfKnot(runList, vp, other);
                                    i = -1;
                                }
                            } else if (vp.isKnot && runList.contains(other)
                                    && other.shouldKnotConsumeExclude((Knot) vp, runList)) {
                                knotFlag = true;
                                makeHalfKnot(runList, vp, other);
                                i = -1;
                            } else if (vp.isKnot && !runList.contains(other)) {
                                // TODO: Need to figure out what to do here, if the other's next best point is
                                // also in the runlist, form a knot!
                            } else if (runList.contains(other) && other.isKnot && vp.isConnector(vp.match1, other)) {
                                knotFlag = true;
                                makeHalfKnot(runList, vp, other);
                                i = -1;
                            }
                        }

                    }
                    if (knotFlag) {
                        for (VirtualPoint vp : runList) {
                            vp.reset();
                            knots.add(vp);
                        }

                        runList = new ArrayList<>();
                        if (toVisit.size() == 0) {
                            return knots;
                        }
                        mainPoint = toVisit.get(0);
                        endpointReached = false;
                        halfKnotCount++;

                        continue;
                    }

                    visited.add(endPoint1);
                    unvisited.remove(endPoint1);

                    visited.add(endPoint2);
                    unvisited.remove(endPoint2);

                    Run k = new Run(runList, shell);
                    knots.add(k);
                    runList = new ArrayList<>();
                    if (toVisit.size() == 0) {
                        return knots;
                    }
                    mainPoint = toVisit.get(0);
                    endpointReached = false;
                } else {

                    endPoint1 = mainPoint;
                    endpointReached = true;
                    if (runList.size() == 0) {
                        knots.add(mainPoint);
                        runList = new ArrayList<>();
                        if (toVisit.size() == 0) {
                            return knots;
                        }
                        mainPoint = toVisit.get(0);
                        endpointReached = false;
                        continue;
                    }
                    mainPoint = runList.get(0);
                }
            }
        }
        return knots;

    }

    public void makeHalfKnot(ArrayList<VirtualPoint> runList, VirtualPoint vp, VirtualPoint other) {
        int vpIdx = runList.indexOf(vp);
        int otherIdx = runList.indexOf(other);
        if (vpIdx > otherIdx) {
            VirtualPoint temp = other;
            other = vp;
            vp = temp;
            int tempi = otherIdx;
            otherIdx = vpIdx;
            vpIdx = tempi;
        }
        ArrayList<VirtualPoint> subList = new ArrayList<VirtualPoint>(runList.subList(vpIdx, otherIdx + 1));
        if (vpIdx == 0 && otherIdx == runList.size() - 1) {
            subList = runList;
        }
        VirtualPoint tempMatch;
        Point tempME;
        Point tempBP;
        Segment tempS;

        if (subList.contains(vp.match1)) {
            tempMatch = vp.match2;
            tempME = vp.match2endpoint;
            tempBP = vp.basePoint2;
            tempS = vp.s2;
            vp.setMatch2(null, null, null, null);
        } else {
            tempMatch = vp.match1;
            tempME = vp.match1endpoint;
            tempBP = vp.basePoint1;
            tempS = vp.s1;
            for (VirtualPoint vp1 : runList) {
                shell.buff.add(vp1.fullString());
            }
            vp.swap();
            vp.setMatch2(null, null, null, null);
        }
        VirtualPoint temp2Match;
        Point temp2ME;
        Point temp2BP;
        Segment temp2S;
        if (subList.contains(other.match1)) {
            temp2Match = other.match2;
            temp2ME = other.match2endpoint;
            temp2BP = other.basePoint2;
            temp2S = other.s2;
            other.setMatch2(null, null, null, null);
        } else {
            temp2Match = other.match1;
            temp2ME = other.match1endpoint;
            temp2BP = other.basePoint1;
            temp2S = other.s1;
            shell.buff.add(other);
            shell.buff.add(subList);
            for (VirtualPoint vp1 : runList) {
                shell.buff.add(vp1.fullString());
            }
            other.swap();
            other.setMatch2(null, null, null, null);
        }
        Knot k = new Knot(subList, shell);
        k.setMatch1(tempMatch, tempME, tempBP, tempS);
        if (tempMatch != null) {
            shell.buff.add(tempMatch.fullString());
            if (tempMatch.match1endpoint.equals(tempBP)) {
                tempMatch.match1 = k;
            } else {
                tempMatch.match2 = k;
            }
        }

        k.setMatch2(temp2Match, temp2ME, temp2BP, temp2S);
        if (temp2Match != null) {
            if (temp2Match.match1endpoint.equals(temp2BP)) {
                temp2Match.match1 = k;
            } else {
                temp2Match.match2 = k;
            }
        }
        runList.removeAll(subList);
        runList.add(vpIdx, k);
    }

}

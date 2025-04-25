package shell.cuts;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
import shell.cuts.engines.InternalPathEngine;
import shell.cuts.enums.RouteType;
import shell.cuts.route.Route;
import shell.cuts.route.RouteMap;
import shell.exceptions.BalancerException;
import shell.exceptions.FileParseException;
import shell.exceptions.SegmentBalanceException;
import shell.file.FileStringable;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class Manifold implements FileStringable {
    public boolean hasCutMatch = false;
    public int kp1 = -1, cp1 = -1, kp2 = -1, cp2 = -1;
    public boolean knotPointsConnected;
    public ArrayList<Pair<Integer, Integer>> cuts;
    public ArrayList<Pair<Integer, Integer>> matches;
    public CutMatchList cutMatchList;
    public boolean shorterPathFound = false;
    public Segment manifoldCutSegment1;
    public Segment manifoldCutSegment2;
    public Segment manifoldExSegment1;
    public Segment manifoldExSegment2;
    public Knot manifoldKnot;
    public CutMatchList originalCutMatch;
    public RouteMap routeMap;

    public Manifold(int kp1, int cp1, int kp2, int cp2, boolean connected) {
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        this.kp1 = kp1;
        this.cp1 = cp1;
        this.kp2 = kp2;
        this.cp2 = cp2;
        this.knotPointsConnected = connected;
    }

    @Override
    public String toFileString() {
        String res = "MANIFOLD " + kp1 + " " + cp1 + " " + kp2 + " " + cp2 + " " + (knotPointsConnected ? "C" : "DC")
                + " ";
        if (cutMatchList != null) {
            res += cutMatchList.toFileString();
        }
        return res;
    }

    @Override
    public String toString() {
        return "MANIFOLD_" + kp1 + "-" + cp1 + "_" + kp2 + "-" + cp2 + "_" + (knotPointsConnected ? "C" : "DC")

                + " calculated delta: " + (cutMatchList == null ? "NULL" : cutMatchList.delta)

                + " solution delta: " + (originalCutMatch == null ? "NULL" : originalCutMatch.delta);
    }

    public void calculateManifoldCutMatch(Shell shell, Knot manifoldKnot) throws SegmentBalanceException {
        VirtualPoint knotPoint1 = shell.pointMap.get(kp1);
        VirtualPoint cutPoint1 = shell.pointMap.get(cp1);
        VirtualPoint knotPoint2 = shell.pointMap.get(kp2);
        VirtualPoint cutPoint2 = shell.pointMap.get(cp2);
        manifoldCutSegment1 = manifoldKnot.getSegment(knotPoint1, cutPoint1);
        manifoldCutSegment2 = manifoldKnot.getSegment(knotPoint2, cutPoint2);
        manifoldExSegment1 = manifoldKnot.getSegment(knotPoint1, knotPoint1);
        manifoldExSegment2 = manifoldKnot.getSegment(knotPoint2, knotPoint2);

        VirtualPoint external1 = knotPoint1;
        VirtualPoint external2 = knotPoint2;

        BalanceMap manifoldBalanceMap = new BalanceMap(manifoldKnot, null);
        CutInfo c1;
        try {
            c1 = new CutInfo(shell, knotPoint1, cutPoint1, manifoldCutSegment1, external1,
                    knotPoint2,
                    cutPoint2, manifoldCutSegment2,
                    external2, manifoldKnot, manifoldBalanceMap, knotPointsConnected);

            manifoldBalanceMap.addCut(knotPoint1, cutPoint1);
            manifoldBalanceMap.addCut(knotPoint2, cutPoint2);
            manifoldBalanceMap.addDummyExternalMatch(knotPoint1);
            manifoldBalanceMap.addDummyExternalMatch(knotPoint2);
        } catch (BalancerException e) {
            throw e;
        }
        CutMatchDistanceMatrix d = new CutMatchDistanceMatrix(c1.knot);
        Pair<CutMatchList, RouteMap> result = InternalPathEngine
                .calculateInternalPathLength(c1, null, d);
        cutMatchList = result.getFirst();
        routeMap = result.getSecond();
        if (!hasCutMatch || (hasCutMatch && cutMatchList.delta < originalCutMatch.delta)) {
            shorterPathFound = true;
        }
    }

    public void loadCutMatch(Shell shell) throws SegmentBalanceException {
        if (!hasCutMatch) {
            return;
        }
        ArrayList<Segment> cutSegments = new ArrayList<>();
        for (Pair<Integer, Integer> p : cuts) {
            VirtualPoint vp = shell.pointMap.get(p.getFirst());
            long id = Segment.idTransform(p.getFirst(), p.getSecond());
            cutSegments.add(vp.segmentLookup.get(id));
        }

        ArrayList<Segment> matchSegments = new ArrayList<>();
        for (Pair<Integer, Integer> p : matches) {
            VirtualPoint vp = shell.pointMap.get(p.getFirst());
            long id = Segment.idTransform(p.getFirst(), p.getSecond());
            matchSegments.add(vp.segmentLookup.get(id));
        }
        originalCutMatch = new CutMatchList(shell, manifoldKnot);
        originalCutMatch.addLists(cutSegments, matchSegments, manifoldKnot, "InternalPathEngine");
    }

    public void parse(String[] cords) throws FileParseException {
        if (cords.length > 6 && cords[6].equals("CUTMATCH")) {
            hasCutMatch = true;
            ArrayList<Pair<Integer, Integer>> segmentList = null;
            for (int i = 7; i < cords.length; i++) {
                if (cords[i].equals("MATCHES")) {
                    segmentList = matches;
                    continue;
                }
                if (cords[i].equals("CUTS")) {
                    segmentList = cuts;
                    continue;
                }
                if (segmentList == null || i + 1 > cords.length) {
                    throw new FileParseException();
                }
                segmentList
                        .add(new Pair<Integer, Integer>(java.lang.Integer.parseInt(cords[i]),
                                java.lang.Integer.parseInt(cords[i + 1])));
                i++;
            }
        }
    }

    public Manifold copy() {
        Manifold result = new Manifold(kp1, cp1, kp2, cp2, knotPointsConnected);
        result.hasCutMatch = true;
        result.cuts = null;
        result.matches = null;
        result.cutMatchList = new CutMatchList(cutMatchList.shell, cutMatchList.superKnot);
        result.manifoldCutSegment1 = manifoldCutSegment1;
        result.manifoldCutSegment2 = manifoldCutSegment2;
        result.manifoldExSegment1 = manifoldExSegment1;
        result.manifoldExSegment2 = manifoldExSegment2;
        result.manifoldKnot = manifoldKnot;
        result.originalCutMatch = originalCutMatch;
        return result;
    }

    public static Pair<Manifold, Integer> findManifold(VirtualPoint startCP, VirtualPoint startKP,
            VirtualPoint endCP, VirtualPoint endKP, ArrayList<Manifold> manifolds) {
        Manifold found = null;
        int foundIndex = -1;
        for (int i = 0; i < manifolds.size(); i++) {
            Manifold m = manifolds.get(i);
            if (m.cp1 == startCP.id
                    && m.kp1 == startKP.id
                    && m.kp2 == endKP.id
                    && m.cp2 == endCP.id) {
                foundIndex = i;
                found = m;
                break;
            }
            if (m.cp2 == startCP.id
                    && m.kp2 == startKP.id
                    && m.kp1 == endKP.id
                    && m.cp1 == endCP.id) {
                foundIndex = i;
                found = m;
                break;
            }
        }
        return new Pair<Manifold, Integer>(found, foundIndex);
    }

    public Route getPrevC(VirtualPoint f) {
        return routeMap.get(f.id).prevC;
    }

    public Route getNextC(VirtualPoint f) {
        return routeMap.get(f.id).nextC;
    }

    public Route getPrevDC(VirtualPoint f) {
        return routeMap.get(f.id).prevDC;
    }

    public Route getNextDC(VirtualPoint f) {
        return routeMap.get(f.id).nextDC;
    }

    public Route getRouteDC(VirtualPoint f, RouteType rt) {
        if (rt.isConnected) {
            return routeMap.get(f.id).getRoute(rt.oppositeConnectionRoute);
        }
        return routeMap.get(f.id).getRoute(rt);
    }

    public Route getRouteC(VirtualPoint f, RouteType rt) {
        if (!rt.isConnected) {
            return routeMap.get(f.id).getRoute(rt.oppositeConnectionRoute);
        }
        return routeMap.get(f.id).getRoute(rt);
    }

    public Route getNeighborRouteC(VirtualPoint f, VirtualPoint neighbor) {
        if (routeMap.get(f.id).prevC.neighbor.id == neighbor.id) {
            return routeMap.get(f.id).prevC;
        } else {
            return routeMap.get(f.id).nextC;
        }
    }

    public Route getNeighborRouteDC(VirtualPoint f, VirtualPoint neighbor) {
        if (routeMap.get(f.id).prevDC.neighbor.id == neighbor.id) {
            return routeMap.get(f.id).prevDC;
        } else {
            return routeMap.get(f.id).nextDC;
        }
    }

    public Route getNeighborRoute(VirtualPoint f, VirtualPoint neighbor, boolean connected) {
        if (connected) {
            return getNeighborRouteC(f, neighbor);
        } else {
            return getNeighborRouteDC(f, neighbor);
        }
    }
}

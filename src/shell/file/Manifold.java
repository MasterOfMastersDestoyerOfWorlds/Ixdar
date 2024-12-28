package shell.file;

import java.util.ArrayList;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import shell.BalanceMap;
import shell.cuts.CutInfo;
import shell.cuts.CutMatchList;
import shell.exceptions.FileParseException;
import shell.exceptions.SegmentBalanceException;
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
        CutInfo c1 = new CutInfo(shell, knotPoint1, cutPoint1, manifoldCutSegment1, external1,
                knotPoint2,
                cutPoint2, manifoldCutSegment2,
                external2, manifoldKnot, null);
        SegmentBalanceException sbe12 = new SegmentBalanceException(shell, null, c1);
        BalanceMap manifoldBalanceMap = new BalanceMap(manifoldKnot, sbe12);
        manifoldBalanceMap.addCut(knotPoint1, cutPoint1);
        manifoldBalanceMap.addCut(knotPoint2, cutPoint2);
        c1.balanceMap = manifoldBalanceMap;
        cutMatchList = shell.cutEngine.internalPathEngine.calculateInternalPathLength(
                knotPoint1, cutPoint1, external1,
                knotPoint2, cutPoint2, external2, manifoldKnot, manifoldBalanceMap, c1,
                knotPointsConnected).getFirst();
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
            VirtualPoint vp = shell.pointMap.get(p.getLeft());
            long id = Segment.idTransform(p.getLeft(), p.getRight());
            cutSegments.add(vp.segmentLookup.get(id));
        }

        ArrayList<Segment> matchSegments = new ArrayList<>();
        for (Pair<Integer, Integer> p : matches) {
            VirtualPoint vp = shell.pointMap.get(p.getLeft());
            long id = Segment.idTransform(p.getLeft(), p.getRight());
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
                        .add(new ImmutablePair<Integer, Integer>(java.lang.Integer.parseInt(cords[i]),
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

}

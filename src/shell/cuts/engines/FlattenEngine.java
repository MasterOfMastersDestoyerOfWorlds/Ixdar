package shell.cuts.engines;

import java.util.ArrayList;
import java.util.HashMap;

import shell.exceptions.BalancerException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class FlattenEngine {

    public CutEngine ce;
    public Shell shell;

    public HashMap<Integer, Knot> flatKnots = new HashMap<>();
    public HashMap<Integer, Integer> knotToFlatKnot = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotToKnot = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsHeight = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsLayer = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsNumKnots = new HashMap<>();
    public ArrayList<Integer> flattenedKnots = new ArrayList<>();

    
    public static void resetMetrics() {
    }

    public FlattenEngine(Shell shell, CutEngine ce) {
        this.shell = shell;
        this.ce = ce;
    }

    public void setFlatKnot(int layerNum, Knot flatKnot, Knot k) {
        flatKnots.put(flatKnot.id, flatKnot);
        flatKnotsHeight.put(flatKnot.id, k.getHeight());
        flatKnotsLayer.put(flatKnot.id, layerNum);
        flatKnotsNumKnots.put(flatKnot.id, k.numKnots);
        knotToFlatKnot.put(flatKnot.id, flatKnot.id);
        knotToFlatKnot.put(k.id, flatKnot.id);
        flatKnotToKnot.put(flatKnot.id, k.id);
    }

    public Knot flattenKnots(Knot knot, VirtualPoint external1, VirtualPoint external2,
            ArrayList<VirtualPoint> knotList, int layerNum) throws SegmentBalanceException, BalancerException {

        ArrayList<VirtualPoint> flattenKnots = ce.cutKnot(knot.knotPoints, layerNum + 1);
        Knot knotNew = new Knot(flattenKnots, shell);
        knotNew.copyMatches(knot);
        if (!flattenedKnots.contains(knot.id) && !flatKnots.containsKey(knot.id)) {
            setFlatKnot(layerNum, knotNew, knot);
            flattenedKnots.add(knot.id);
            shell.updateSmallestCommonKnot(knotNew);
            shell.buff.add(flatKnots);
        }
        boolean makeExternal1 = external1.isKnot;

        boolean same = external1.equals(external2);
        boolean makeExternal2 = external2.isKnot && !same;

        Knot external1Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal1 = null;
        Knot external1New = null;
        if (makeExternal1) {

            external1Knot = (Knot) external1;
            flattenKnotsExternal1 = ce.cutKnot(external1Knot.knotPoints, layerNum + 1);
            external1New = new Knot(flattenKnotsExternal1, shell);

            if (!flattenedKnots.contains(external1Knot.id)) {
                setFlatKnot(layerNum, external1New, external1Knot);
                shell.updateSmallestCommonKnot(external1New);
                external1New.copyMatches(external1);
                flattenedKnots.add(external1Knot.id);
            }
        }
        Knot external2Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
        Knot external2New = null;
        if (makeExternal2) {

            external2Knot = (Knot) external2;
            flattenKnotsExternal2 = ce.cutKnot(external2Knot.knotPoints, layerNum + 1);
            external2New = new Knot(flattenKnotsExternal2, shell);
            external2New.copyMatches(external2);
            if (!flattenedKnots.contains(external2Knot.id)) {
                setFlatKnot(layerNum, external2New, external2Knot);
                shell.updateSmallestCommonKnot(external2New);
                flattenedKnots.add(external2Knot.id);
            }
        }

        if (external1.contains(knot.match1endpoint)) {
            if (makeExternal1) {
                knotNew.match1 = external1New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match1 = external1;
            }
        }
        if (external1.contains(knot.match2endpoint)) {
            if (makeExternal1) {
                knotNew.match2 = external1New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match2 = external1;
            }
        }
        if (external2.contains(knot.match1endpoint)) {
            if (makeExternal2) {
                knotNew.match1 = external2New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match1 = external2;
            }
        }
        if (external2.contains(knot.match2endpoint)) {
            if (makeExternal2) {
                knotNew.match2 = external2New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match2 = external2;
            }
        }

        if (knotNew.contains(external1.match1endpoint)) {
            if (makeExternal1) {

                external1New.match1 = knotNew;
            } else {
                external1.match1 = knotNew;
            }
        }
        if (knotNew.contains(external1.match2endpoint)) {
            if (makeExternal1) {
                external1New.match2 = knotNew;
            } else {
                external1.match2 = knotNew;
            }
        }

        if (knotNew.contains(external2.match1endpoint)) {
            if (makeExternal2) {
                external2New.match1 = knotNew;
            } else {
                external2.match1 = knotNew;
            }
        }
        if (knotNew.contains(external2.match2endpoint)) {
            if (makeExternal2) {
                external2New.match2 = knotNew;
            } else {
                external2.match2 = knotNew;
            }
        }
        if (makeExternal1 && external1New.contains(external2.match1endpoint)) {
            if (makeExternal2) {
                external2New.match1 = external1New;
            } else {
                external2.match1 = external1New;
            }
        }
        if (makeExternal1 && external1New.contains(external2.match2endpoint)) {
            if (makeExternal2) {
                external2New.match2 = external1New;
            } else {
                external2.match2 = external1New;
            }
        }
        if (makeExternal2 && external2New.contains(external1.match1endpoint)) {
            if (makeExternal1) {
                external1New.match1 = external2New;
            } else {
                external1.match1 = external2New;
            }
        }
        if (makeExternal2 && external2New.contains(external1.match2endpoint)) {
            if (makeExternal1) {
                external1New.match2 = external2New;
            } else {
                external1.match2 = external2New;
            }
        }
        if (makeExternal1) {
            if (external1New.contains(external1New.match1.match1endpoint)) {
                external1New.match1.match1 = external1New;
            }

            if (external1New.contains(external1New.match1.match2endpoint)) {
                external1New.match1.match2 = external1New;
            }
            if (external1New.contains(external1New.match2.match1endpoint)) {
                external1New.match2.match1 = external1New;
            }

            if (external1New.contains(external1New.match2.match2endpoint)) {
                external1New.match2.match2 = external1New;
            }
        }
        if (makeExternal2) {
            if (external2New.contains(external2New.match1.match1endpoint)) {
                external2New.match1.match1 = external2New;
            }

            if (external2New.contains(external2New.match1.match2endpoint)) {
                external2New.match1.match2 = external2New;
            }
            if (external2New.contains(external2New.match2.match1endpoint)) {
                external2New.match2.match1 = external2New;
            }

            if (external2New.contains(external2New.match2.match2endpoint)) {
                external2New.match2.match2 = external2New;
            }
        }

        if (makeExternal1) {
            int idx = knotList.indexOf(external1);
            knotList.add(idx, external1New);
            knotList.remove(external1);
        }

        if (makeExternal2) {
            int idx = knotList.indexOf(external2);
            knotList.add(idx, external2New);
            knotList.remove(external2);
        }
        int idx2 = knotList.indexOf(knot);
        knotList.add(idx2, knotNew);
        knotList.remove(knot);

        return knotNew;
    }

}
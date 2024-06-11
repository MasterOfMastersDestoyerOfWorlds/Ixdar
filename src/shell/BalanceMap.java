package shell;

import java.util.HashMap;

public class BalanceMap {
    Knot knot;
    HashMap<Integer, Integer> balance;
    HashMap<Integer, Integer> externalMatches;
    SegmentBalanceException sbe;

    public BalanceMap(Knot knot, SegmentBalanceException sbe) {
        this.knot = knot;
        balance = new HashMap<>();
        externalMatches = new HashMap<>();
        for (VirtualPoint vp : knot.knotPointsFlattened) {
            balance.put(vp.id, 2);
            externalMatches.put(vp.id, 0);
        }
        this.sbe = sbe;
    }

    public void addExternalMatch(VirtualPoint vp) throws BalancerException {
        int newBalance = externalMatches.get(vp.id) + 1;
        if (newBalance > 2) {
            throw new BalancerException(vp, sbe);
        }
        externalMatches.put(vp.id, newBalance);
    }

    public void addCut(VirtualPoint vp1, VirtualPoint vp2) throws BalancerException {
        int newBalance = balance.get(vp1.id) - 1;
        int newBalance2 = balance.get(vp2.id) - 1;
        if (newBalance < 0 || newBalance2 < 0) {
            throw new BalancerException(vp1, vp2, sbe);
        }
        balance.put(vp1.id, newBalance);
        balance.put(vp2.id, newBalance);
    }

    public void addInternalMatch(VirtualPoint vp1, VirtualPoint vp2) throws BalancerException {
        int newBalance = balance.get(vp1.id) + 1;
        int newBalance2 = balance.get(vp2.id) + 1;
        if (newBalance < 0 || newBalance2 < 0) {
            throw new BalancerException(sbe);
        }
        balance.put(vp1.id, newBalance);
        balance.put(vp2.id, newBalance);
    }

    public boolean canMatchTo(VirtualPoint vp) {
        if (externalMatches.get(vp.id) < 2) {
            return true;
        }
        return false;
    }

    public BalanceMap(BalanceMap bMap, Knot subKnot, SegmentBalanceException sbe) {
        knot = subKnot;
        balance = new HashMap<>();
        externalMatches = new HashMap<>();
        for (VirtualPoint vp : subKnot.knotPointsFlattened) {
            if (bMap.balance.containsKey(vp.id)) {
                balance.put(vp.id, 2 - bMap.externalMatches.get(vp.id));
                externalMatches.put(vp.id, bMap.externalMatches.get(vp.id));
            } else {
                balance.put(vp.id, 2);
                externalMatches.put(vp.id, 0);
            }
        }
        this.sbe = sbe;
    }

    @Override
    public String toString() {
        return "BalanceMap: [\nexts: " + externalMatches.toString() + "\n" + "bal:" + balance.toString() + "\n]";
    }

}

package shell.cuts;

import shell.exceptions.SegmentBalanceException;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class Clockwork {
    /**
     *
     */
    private final CutEngine cutEngine;
    CutInfo c;
    CutMatchList cml;
    CutMatch cm;
    Clockwork prevClockwork;
    boolean prevIsSet = false;
    Segment prevCut;
    VirtualPoint prevExternal;
    VirtualPoint prevKnotPoint;
    Double prevExternalDelta;
    Segment prevExternalSegment;
    Clockwork nextClockwork;
    boolean nextIsSet = false;
    Segment nextCut;
    VirtualPoint nextExternal;
    VirtualPoint nextKnotPoint;
    Double nextExternalDelta;
    Segment nextExternalSegment;

    public Clockwork(CutEngine cutEngine, CutMatchList cml, VirtualPoint next, VirtualPoint prev) {
        this.cutEngine = cutEngine;
        this.cml = cml;
        this.cm = cml.cutMatches.get(0);
        this.c = cm.c;
        if (next.contains(c.upperExternal)) {
            prevCut = c.lowerCutSegment;
            nextCut = c.upperCutSegment;
        } else {
            prevCut = c.upperCutSegment;
            nextCut = c.lowerCutSegment;
        }
        if (c.cutID == 1875) {
            float z = 0;
        }
        prevKnotPoint = c.getKnotPointFromCutSegment(prev, prevCut, null);
        nextKnotPoint = c.getKnotPointFromCutSegment(next, nextCut, prevKnotPoint);
        if (prevKnotPoint == null || nextKnotPoint == null) {
            float z = 0;
        }
    }

    public void flip() {
        VirtualPoint temp = nextExternal;
        nextExternal = prevExternal;
        prevExternal = temp;
        Segment tempS = nextExternalSegment;
        nextExternalSegment = prevExternalSegment;
        prevExternalSegment = tempS;
        if (nextExternalSegment != null) {
            nextExternalDelta = nextExternalSegment.distance;
        }
        if (prevExternalSegment != null) {
            prevExternalDelta = prevExternalSegment.distance;
        }
        Segment tempC = prevCut;
        prevCut = nextCut;
        nextCut = tempC;
        VirtualPoint tempKP = prevKnotPoint;
        prevKnotPoint = nextKnotPoint;
        nextKnotPoint = tempKP;
        Clockwork tempCW = prevClockwork;
        prevClockwork = nextClockwork;
        nextClockwork = tempCW;
    }

    public void swapExternals() {
        VirtualPoint temp = nextExternal;
        nextExternal = prevExternal;
        prevExternal = temp;
        nextExternalSegment = nextKnotPoint.getSegment(nextExternal);
        prevExternalSegment = prevKnotPoint.getSegment(prevExternal);
        nextExternalDelta = nextExternalSegment.distance;
        prevExternalDelta = prevExternalSegment.distance;
        Clockwork tempClockwork = nextClockwork;
        nextClockwork = prevClockwork;
        prevClockwork = tempClockwork;
    }

    public void swapExternals(Clockwork nextCw) {
        VirtualPoint temp = nextExternal;
        this.nextExternal = nextCw.prevExternal;
        nextCw.prevExternal = temp;
        this.nextExternalSegment = nextKnotPoint.getSegment(nextExternal);
        nextCw.prevExternalSegment = nextCw.prevKnotPoint.getSegment(nextCw.prevExternal);
        ;
        this.nextExternalDelta = nextExternalSegment.distance;
        nextCw.prevExternalDelta = prevExternalSegment.distance;
        if (this.nextExternalSegment.id == this.prevExternalSegment.id) {
            float z = 0;
        }
    }

    public void setPrevCwUpdateCML(Clockwork prevCw, CutMatchList cutMatchList, CutMatchList prevCutMatchList,
            VirtualPoint prevKnotPoint,
            VirtualPoint nextKnotPoint,
            VirtualPoint prevExternal) throws SegmentBalanceException {
        if (nextIsSet && nextKnotPoint.id == prevKnotPoint.id) {
            throw new SegmentBalanceException(this.cutEngine.shell, null, c);
        }
        this.prevClockwork = prevCw;
        this.prevExternal = prevExternal;
        this.prevKnotPoint = prevKnotPoint;
        this.nextKnotPoint = nextKnotPoint;
        Segment nextExternalSegment = nextKnotPoint.getSegment(this.nextExternal);
        this.nextExternalSegment = nextExternalSegment;
        if (nextClockwork != null) {
            Clockwork nextCw = this.nextClockwork;
            nextCw.prevExternal = nextKnotPoint;
            nextCw.prevExternalSegment = nextExternalSegment;
            this.nextExternalDelta = this.cost(nextCw, true);
            nextCw.prevExternalDelta = nextCw.cost(this, false);
        }
        this.cml = cutMatchList;
        this.cm = cutMatchList.cutMatches.get(0);
        this.c = this.cm.c;
        this.prevCut = this.cm.c.getCutSegmentFromKnotPoint(prevKnotPoint);
        this.nextCut = this.cm.c.getCutSegmentFromKnotPoint(nextKnotPoint);
        this.prevIsSet = true;
        this.prevExternalSegment = prevKnotPoint.getSegment(prevExternal);
        prevCw.cml = prevCutMatchList;
        prevCw.cm = prevCutMatchList.cutMatches.get(0);
        prevCw.c = prevCw.cm.c;
        prevCw.prevCut = prevCw.c.getCutSegmentFromKnotPoint(prevCw.prevKnotPoint);
        prevCw.nextClockwork = this;
        prevCw.nextKnotPoint = prevExternal;
        prevCw.nextExternal = prevKnotPoint;
        prevCw.nextIsSet = true;
        prevCw.nextCut = prevCw.cm.c.getCutSegmentFromKnotPoint(prevExternal);
        prevCw.nextExternalSegment = prevExternalSegment;
        this.prevExternalDelta = this.cost(prevCw, false);
        prevCw.nextExternalDelta = prevCw.cost(this, true);
    }

    public void setNextCwUpdateCML(Clockwork nextCw, CutMatchList cutMatchList, CutMatchList nextCutMatchList,
            VirtualPoint nextKnotPoint,
            VirtualPoint prevKnotPoint,
            VirtualPoint nextExternal) throws SegmentBalanceException {
        if (prevIsSet && prevKnotPoint.id == nextKnotPoint.id) {
            throw new SegmentBalanceException(this.cutEngine.shell, null, c);
        }
        this.nextClockwork = nextCw;
        this.nextExternal = nextExternal;
        this.nextKnotPoint = nextKnotPoint;
        this.prevKnotPoint = prevKnotPoint;
        Segment prevExternalSegment = prevKnotPoint.getSegment(this.prevExternal);
        this.prevExternalSegment = prevExternalSegment;
        if (prevClockwork != null) {
            Clockwork prevCw = this.prevClockwork;
            prevCw.nextExternal = prevKnotPoint;
            prevCw.nextExternalSegment = prevExternalSegment;
            this.prevExternalDelta = this.cost(prevCw, true);
            prevCw.nextExternalDelta = prevCw.cost(this, false);
        }
        this.cml = cutMatchList;
        this.cm = cutMatchList.cutMatches.get(0);
        this.c = this.cm.c;
        this.nextCut = this.cm.c.getCutSegmentFromKnotPoint(nextKnotPoint);
        this.prevCut = this.cm.c.getCutSegmentFromKnotPoint(prevKnotPoint);
        this.nextIsSet = true;
        this.nextExternalSegment = nextKnotPoint.getSegment(nextExternal);
        nextCw.cml = nextCutMatchList;
        nextCw.cm = nextCutMatchList.cutMatches.get(0);
        nextCw.c = nextCw.cm.c;
        nextCw.nextCut = nextCw.c.getCutSegmentFromKnotPoint(nextCw.nextKnotPoint);
        nextCw.prevClockwork = this;
        nextCw.prevKnotPoint = nextExternal;
        nextCw.prevExternal = nextKnotPoint;
        nextCw.prevIsSet = true;
        nextCw.prevCut = nextCw.cm.c.getCutSegmentFromKnotPoint(nextExternal);
        nextCw.prevExternalSegment = nextExternalSegment;
        this.nextExternalDelta = this.cost(nextCw, false);
        nextCw.prevExternalDelta = nextCw.cost(this, true);
    }

    public void setPrevPoint(VirtualPoint prev, VirtualPoint prevBasePoint, Segment prevCutSegment) {
        prevExternal = prev;
        prevKnotPoint = prevBasePoint;
        prevCut = prevCutSegment;
    }

    public void setNextPoint(VirtualPoint next, VirtualPoint nextBasePoint, Segment nextCutSegment) {
        nextExternal = next;
        nextKnotPoint = nextBasePoint;
        nextCut = nextCutSegment;
    }

    public void setNextCw(Clockwork nextCw, VirtualPoint nextKnotPoint, VirtualPoint nextExternal,
            Segment nextCutSegment) throws SegmentBalanceException {
        if (prevIsSet && prevKnotPoint.id == nextKnotPoint.id) {
            throw new SegmentBalanceException(this.cutEngine.shell, null, c);
        }
        nextClockwork = nextCw;
        this.nextExternal = nextExternal;
        this.nextKnotPoint = nextKnotPoint;
        nextCut = nextCutSegment;
        nextIsSet = true;
        nextExternalSegment = nextKnotPoint.getSegment(nextExternal);
        nextCw.prevClockwork = this;
        nextCw.prevKnotPoint = nextExternal;
        nextCw.prevExternal = nextKnotPoint;
        nextCw.prevIsSet = true;
        nextCw.prevCut = nextCw.cm.c.getCutSegmentFromKnotPoint(nextExternal);
        nextCw.prevExternalSegment = nextExternalSegment;
        this.nextExternalDelta = this.cost(nextCw, false);
        nextCw.prevExternalDelta = nextCw.cost(this, true);
    }

    public void setPrevCw(Clockwork prevCw, VirtualPoint prevKnotPoint, VirtualPoint prevMatchPoint,
            Segment prevCutSegment) throws SegmentBalanceException {
        if (nextIsSet && prevKnotPoint.id == nextKnotPoint.id) {
            throw new SegmentBalanceException(this.cutEngine.shell, null, c);
        }
        prevClockwork = prevCw;
        this.prevExternal = prevMatchPoint;
        this.prevKnotPoint = prevKnotPoint;
        prevCut = prevCutSegment;
        prevIsSet = true;
        prevExternalSegment = prevKnotPoint.getSegment(prevMatchPoint);
        prevCw.nextClockwork = this;
        prevCw.nextKnotPoint = prevMatchPoint;
        prevCw.nextExternal = prevKnotPoint;
        prevCw.nextIsSet = true;
        prevCw.nextCut = prevCw.cm.c.getCutSegmentFromKnotPoint(prevMatchPoint);
        prevCw.nextExternalSegment = prevExternalSegment;
        this.prevExternalDelta = this.cost(prevCw, true);
        prevCw.nextExternalDelta = prevCw.cost(this, false);
    }

    @Override
    public String toString() {
        return prevExternal + "<-" + prevKnotPoint + prevCut.toStringNoLabel() + ":" + nextCut.toStringNoLabel()
                + nextKnotPoint + "->" + nextExternal;
    }

    public double cost(Clockwork neighbor, boolean prev) {
        double cost = this.cml.internalDelta + neighbor.cml.internalDelta + this.nextExternalSegment.distance
                + this.prevExternalSegment.distance;
        return cost;
    }

    public static double cost(CutMatchList current, VirtualPoint currentVp, CutMatchList neighbor,
            VirtualPoint neighborVp, Segment externalPrev) {
        Segment externalSegment = currentVp.getSegment(neighborVp);
        double cost = current.internalDelta + neighbor.internalDelta + externalSegment.distance + externalPrev.distance;
        return cost;
    }
}
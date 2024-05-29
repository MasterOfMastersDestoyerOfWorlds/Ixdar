package shell;

import java.util.ArrayList;

public class Segment implements Comparable {
    VirtualPoint first;
    VirtualPoint last;
    double distance;

    public Segment(VirtualPoint first,
            VirtualPoint last,
            double distance) {
        this.first = first;
        this.last = last;
        this.distance = distance;

    }

    VirtualPoint getOther(VirtualPoint vp) {
        if (vp.equals(first)) {
            return last;
        }
        if (vp.equals(last)) {
            return first;
        }
        return null;
    }

    VirtualPoint getOtherKnot(VirtualPoint vp) {
        if (vp.isKnot) {
            Knot knot = (Knot) vp;
            VirtualPoint p = this.getKnotPoint(knot.knotPointsFlattened);
            return this.getOther(p);
        } else if (vp.isRun) {
            Run knot = (Run) vp;
            VirtualPoint p = this.getKnotPoint(knot.knotPointsFlattened);
            return this.getOther(p);
        } else {
            return this.getOther(vp);
        }
    }

    boolean contains(VirtualPoint vp) {
        return first.equals(vp) || last.equals(vp);
    }

    VirtualPoint getKnotPoint(ArrayList<VirtualPoint> knotPointsFlattened) {
        if (knotPointsFlattened.contains(first)) {
            return first;
        }
        if (knotPointsFlattened.contains(last)) {
            return last;
        }
        return null;
    }

    @Override
    public int compareTo(Object o) {
        if (o.getClass() == Segment.class) {
            Segment s = (Segment) o;
            if (s.distance < this.distance) {
                return 1;
            } else if (s.distance > this.distance) {
                return -1;
            } else {
                return 0;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "Segment[" + first.id + ":" + last.id + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj.getClass() != Segment.class) {
            return false;
        } else {
            Segment s2 = (Segment) obj;

            return (this.first.id == s2.first.id && this.last.id == s2.last.id)
                    || (this.first.id == s2.last.id && this.last.id == s2.first.id);
        }
    }

    public boolean partialOverlaps(Segment cutSegment2) {
        if ((cutSegment2.contains(first) && !cutSegment2.contains(last)) ||
                (cutSegment2.contains(last) && !cutSegment2.contains(first))) {
            return true;
        }
        return false;
    }

    public boolean intersects(Segment cutSegment2) {
        if (cutSegment2.contains(first) || cutSegment2.contains(last)) {
            return true;
        }
        return false;
    }
}
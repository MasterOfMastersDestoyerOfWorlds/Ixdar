package shell.knot;

import java.util.ArrayList;

public class Segment implements Comparable<Segment> {
    public VirtualPoint first;
    public VirtualPoint last;
    public double distance;

    public Segment(VirtualPoint first,
            VirtualPoint last,
            double distance) {
        this.first = first;
        this.last = last;
        this.distance = distance;

    }

    public VirtualPoint getOther(VirtualPoint vp) {
        if (vp.equals(first)) {
            return last;
        }
        if (vp.equals(last)) {
            return first;
        }
        return null;
    }

    public VirtualPoint getOtherKnot(VirtualPoint vp) {
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

    public boolean contains(VirtualPoint vp) {
        return first.equals(vp) || last.equals(vp);
    }

    public VirtualPoint getKnotPoint(ArrayList<VirtualPoint> knotPointsFlattened) {
        if (knotPointsFlattened.contains(first)) {
            return first;
        }
        if (knotPointsFlattened.contains(last)) {
            return last;
        }
        return null;
    }

    @Override
    public int compareTo(Segment o) {
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

    public VirtualPoint getOverlap(Segment other) {
        // TODO Auto-generated method stub
        if (other.contains(first)) {
            return first;
        } else if (other.contains(last)) {
            return last;
        }
        return null;
    }

    public VirtualPoint containsAny(ArrayList<VirtualPoint> neighbors) {
        for (VirtualPoint vp : neighbors) {
            if (this.contains(vp)) {
                return vp;
            }
        }
        return null;
    }

    public boolean isDegenerate() {
        return first.equals(last);
    }

    public boolean hasPoints(int i, int j) {
        if (first.id == i || first.id == j) {
            if (last.id == i || last.id == j) {
                return true;
            }
        }
        return false;
    }

    public static int getFirstOrderId(VirtualPoint firstInnerNeighbor, VirtualPoint k2) {
        int first = firstInnerNeighbor.id < k2.id ? firstInnerNeighbor.id : k2.id;
        return first;
    }

    public static int getLastOrderId(VirtualPoint firstInnerNeighbor, VirtualPoint k2) {
        return firstInnerNeighbor.id < k2.id ? k2.id : firstInnerNeighbor.id;
    }

    public static int getFirstOrderId(Segment s) {
        return getFirstOrderId(s.first, s.last);
    }

    public static int getLastOrderId(Segment s) {
        return getLastOrderId(s.first, s.last);
    }

    @Override
    public int hashCode() {
        return first.id * last.id; // or any other constant
    }

    public boolean hasPoint(Integer i) {
        if (first.id == i || last.id == i) {
                return true;
        }
        return false;
    }

    public VirtualPoint getPoint(Integer i) {
        if (first.id == i) {
            return first;
        }
        if (last.id == i) {
            return last;
        }
        return null;
    }
}
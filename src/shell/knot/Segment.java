package shell.knot;

import java.util.ArrayList;

import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;

import shell.objects.PointND;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.actions.Action;
import shell.ui.main.Main;

public class Segment implements Comparable<Segment> {
    public VirtualPoint first;
    public VirtualPoint last;
    public double distance;
    public long id;

    public Segment(VirtualPoint first, VirtualPoint last, double distance) {
        this.first = first;
        this.last = last;
        this.distance = distance;
        long a = first.id;
        long b = last.id;
        id = idTransform(a, b);
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
                if (s.id < this.id) {
                    return 1;
                } else if (s.id > this.id) {
                    return -1;
                }
                return 0;
            }
        }
        return -1;
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
            return this.id == s2.id;
            // return (this.first.id == s2.first.id && this.last.id == s2.last.id)
            // || (this.first.id == s2.last.id && this.last.id == s2.first.id);
        }
    }

    public boolean partialOverlaps(Segment cutSegment2) {
        if ((cutSegment2.contains(first) && !cutSegment2.contains(last))
                || (cutSegment2.contains(last) && !cutSegment2.contains(first))) {
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

    public static long idTransform(Segment s) {
        long a = s.first.id;
        long b = s.last.id;
        return a >= b ? a * a + a + b : b + a + b * b;
    }

    public static long idTransform(long a, long b) {
        return a >= b ? a * a + a + b : b + a + b * b;
    }

    public static long idTransformOrdered(long a, long b) {
        return a * a + a + b;
    }

    public double boundContains(double x, double y) {
        PointND p1 = ((Point) first).p;
        PointND p2 = ((Point) last).p;
        double x1 = p1.getScreenX();
        double y1 = p1.getScreenY();
        double x2 = p2.getScreenX();
        double y2 = p2.getScreenY();
        double dx = x2 - x1;
        double dy = y2 - y1;
        double normalX = -dy;
        double normalY = dx;
        Vector2D firstVec = new Vector2D(x1, y1);
        Vector2D lastVec = new Vector2D(x2, y2);
        Vector2D normalUnitVector = new Vector2D(normalX, normalY);
        normalUnitVector = normalUnitVector.normalize().scalarMultiply(distance).scalarMultiply(0.2);
        Vector2D tL = normalUnitVector.add(firstVec);
        Vector2D bL = firstVec.subtract(normalUnitVector);
        Vector2D tR = normalUnitVector.add(lastVec);
        Vector2D bR = lastVec.subtract(normalUnitVector);
        Vector2D pointVector = new Vector2D(x, y);

        if ((x - tL.getX()) * (tL.getY() - bL.getY()) + (y - tL.getY()) * (bL.getX() - tL.getX()) > 0
                && (x - bL.getX()) * (bL.getY() - bR.getY()) + (y - bL.getY()) * (bR.getX() - bL.getX()) > 0
                && (x - bR.getX()) * (bR.getY() - tR.getY()) + (y - bR.getY()) * (tR.getX() - bR.getX()) > 0
                && (x - tR.getX()) * (tR.getY() - tL.getY()) + (y - tR.getY()) * (tL.getX() - tR.getX()) > 0) {
            double result = Math.abs(
                    (y2 - y1) * pointVector.getX() - ((x2 - x1) * pointVector.getY()) + x2 * y1 - y2 * x1) / distance;
            return result;
        }
        return -1;

    }

    public VirtualPoint closestPoint(double x, double y) {
        PointND p1 = ((Point) first).p;
        PointND p2 = ((Point) last).p;
        double x1 = p1.getScreenX();
        double y1 = p1.getScreenY();
        double x2 = p2.getScreenX();
        double y2 = p2.getScreenY();
        double distFirst = Math.sqrt((x1 - x) * (x1 - x) + (y1 - y) * (y1 - y));
        double distLast = Math.sqrt((x2 - x) * (x2 - x) + (y2 - y) * (y2 - y));
        if (distFirst < distLast) {
            return first;
        } else {
            return last;
        }

    }

    @Override
    public String toString() {
        return "Segment[" + first.id + ":" + last.id + "]";
    }

    public HyperString toHyperString(Color color, boolean labelAsSegment) {
        return toHyperString(color, labelAsSegment, false);
    }

    public HyperString toHyperString(Color color, boolean labelAsSegment, boolean labelDistance) {
        HyperString h = new HyperString();
        Action clickAction = () -> {
            Main.camera.zoomToSegment(this);
        };
        String str = "";

        if (labelAsSegment) {
            str += "Segment";
        }
        str += "[" + first.id + ":" + last.id + "]";
        if (labelDistance) {
            str += ", " + this.distance;
        }
        h.addHoverSegment(str, color, this, clickAction);
        return h;
    }

}
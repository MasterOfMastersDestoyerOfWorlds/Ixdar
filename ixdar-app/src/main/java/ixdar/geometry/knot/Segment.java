package ixdar.geometry.knot;

import java.util.ArrayList;

import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;
import org.joml.Vector2f;

import ixdar.geometry.point.Point2D;
import ixdar.geometry.point.PointND;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.actions.Action;
import ixdar.scenes.main.MainScene;

public class Segment extends SDFLine implements Comparable<Segment> {
    public Knot first;
    public Knot last;
    public double distance;
    public long id;

    public Segment(Knot first, Knot last, double distance) {
        this.first = first;
        this.last = last;
        this.distance = distance;
        long a = first.id;
        long b = last.id;
        id = idTransform(a, b);
    }

    public Segment(Knot knot1, Knot knot2, DistanceMatrix distanceMatrix) {
        this.first = knot1;
        this.last = knot2;
        this.distance = distanceMatrix.getDistance(knot1.p, knot2.p);
        long a = knot1.id;
        long b = knot2.id;
        id = idTransform(a, b);
    }

    public Knot getOther(Knot vp) {
        if (vp.equals(first)) {
            return last;
        }
        if (vp.equals(last)) {
            return first;
        }
        return null;
    }

    public Knot getOtherKnot(Knot vp) {
        if (!vp.isSingleton()) {
            Knot knot = (Knot) vp;
            Knot p = this.getKnotPoint(knot.knotPointsFlattened);
            return this.getOther(p);
        } else {
            return this.getOther(vp);
        }
    }

    public boolean contains(Knot vp) {
        return first.equals(vp) || last.equals(vp);
    }

    public boolean contains(Knot[] vp) {
        boolean contains = false;
        for (int i = 0; i < vp.length; i++) {
            if (first.equals(vp[i]) || last.equals(vp[i])) {
                contains = true;
            }
        }
        return contains;
    }

    public Knot getKnotPoint(ArrayList<Knot> knotPointsFlattened) {
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

    public Knot getOverlap(Segment other) {
        if (other.contains(first)) {
            return first;
        } else if (other.contains(last)) {
            return last;
        }
        return null;
    }

    public Knot containsAny(ArrayList<Knot> neighbors) {
        for (Knot vp : neighbors) {
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

    public static int getFirstOrderId(Knot firstInnerNeighbor, Knot k2) {
        int first = firstInnerNeighbor.id < k2.id ? firstInnerNeighbor.id : k2.id;
        return first;
    }

    public static int getLastOrderId(Knot firstInnerNeighbor, Knot k2) {
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

    public Knot getPoint(Integer i) {
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

    public static long idTransformOrdered(Segment s) {
        long a = s.first.id;
        long b = s.last.id;
        return (a + b) * (a + b + 1) / 2 + b;
    }

    public static long idTransformOrdered(Segment cutSegment, Knot knotPoint) {
        Knot cutPoint = cutSegment.getOther(knotPoint);
        long a = cutPoint.id;
        long b = knotPoint.id;
        return (a + b) * (a + b + 1) / 2 + b;
    }

    public static long idTransformOrdered(long a, long b) {
        return (a + b) * (a + b + 1) / 2 + b;
    }

    public double boundContains(double x, double y) {
        PointND p1 = (first).p;
        PointND p2 = (last).p;
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

    public Knot closestPoint(double x, double y) {
        PointND p1 = (first).p;
        PointND p2 = (last).p;
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

    public String toStringNoLabel() {
        return "[" + first.id + ":" + last.id + "]";
    }

    public HyperString toHyperString(Color color, boolean labelAsSegment) {
        return toHyperString(color, labelAsSegment, false);
    }

    public HyperString toHyperString(Color color, boolean labelAsSegment, boolean labelDistance) {
        HyperString h = new HyperString();
        Action clickAction = () -> {
            MainScene.camera.zoomToSegment(this);
        };
        String str = "";

        if (labelAsSegment) {
            str += "Segment";
        }
        str += "[" + first.id + ":" + last.id + "]";
        if (labelDistance) {
            str += ", " + String.format("%.2f", this.distance);
        }
        h.addHoverSegment(str, color, this, clickAction);
        return h;
    }

    public Vector2f getScreenSpaceVector(Knot k1) {
        Vector2f psV = getPointSpaceVector(k1);
        return new Vector2f(camera.pointTransformX(psV.x), camera.pointTransformY(psV.y));
    }

    public Vector2f toScreenSpace(Vector2f pointSpaceVector) {
        return new Vector2f(camera.pointTransformX(pointSpaceVector.x), camera.pointTransformY(pointSpaceVector.y));
    }

    public Vector2f getPointSpaceVector(Knot k1) {
        Point2D p1;
        float[] firstCoords = new float[2];

        if (!k1.isSingleton()) {
            p1 = (((Knot) k1).knotPoints.get(0)).p.toPoint2D();
        } else {
            p1 = (k1).p.toPoint2D();
        }
        firstCoords[0] = (float) p1.getX();
        firstCoords[1] = (float) p1.getY();
        return new Vector2f(firstCoords);
    }

    @Override
    public void setStroke(float lineWidth, boolean dashed, float dashLength, float dashRate, boolean roundCaps,
            boolean endCaps, Camera2D camera2d) {

        if (uAxis == null) {
            this.camera = camera2d;
            super.setEndpoints(camera2d, getScreenSpaceVector(first), getScreenSpaceVector(last));
            calculateQuad();
        }
        Vector2f uAxis = super.getUAxis();
        Vector2f vAxis = super.getVAxis();
        Vector2f basePoint = getPointSpaceVector(first);
        Vector2f dirPoint = getPointSpaceVector(last).sub(basePoint);
        Vector2f dashPoint = new Vector2f(dirPoint).normalize().mul(dashLength);

        Vector2f baseScreen = toScreenSpace(basePoint);
        Vector2f dashEndScreen = toScreenSpace(new Vector2f(basePoint).add(dashPoint));
        Vector2f screenDir = dashEndScreen.sub(baseScreen);

        float det = uAxis.x * vAxis.y - uAxis.y * vAxis.x;
        float u = (screenDir.x * vAxis.y - screenDir.y * vAxis.x) / det;
        float v = (uAxis.x * screenDir.y - uAxis.y * screenDir.x) / det;

        float texLength = (float) Math.sqrt(u * u + v * v) * widthToHeightRatio;
        super.setStroke(lineWidth, dashed, texLength, dashRate, roundCaps, endCaps);
    }
}
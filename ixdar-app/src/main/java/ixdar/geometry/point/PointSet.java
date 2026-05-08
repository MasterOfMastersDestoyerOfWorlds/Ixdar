package ixdar.geometry.point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;

/**
 * A set of all of the points in the current TSP problem.
 */
public class PointSet extends ArrayList<PointND> {
    public static final String POINTSET = "PointSet[";
    public static final String STR = "]";
    @SuppressWarnings("unused")
    private int getLargestDim() {
        int maxDim = 0;
        for (PointND p : this) {
            if (maxDim < p.getDim()) {
                maxDim = p.getDim();
            }
        }
        return maxDim;
    }

    /**
     * TODO: document {@code getByID}.
     *
     * @param ID TODO: describe
     * @return TODO: describe
     */
    public PointND getByID(int ID) {
        for (PointND p : this) {
            if (p.getID() == ID) {
                return p;
            }
        }
        return null;
    }

    /**
     * TODO: document {@code SumDistancesToPoint}.
     *
     * @param p TODO: describe
     * @param d TODO: describe
     * @return TODO: describe
     */
    public double SumDistancesToPoint(PointND p, DistanceMatrix d) {
        double sum = 0.0;
        for (PointND pt : this) {
            if (!pt.equals(p)) {
                sum += d.getDistance(pt, p);
            }
        }
        return sum;
    }

    @SuppressWarnings("unused")
    private void printShellsWithKeys(ArrayList<Shell> shells, HashMap<Integer, Integer> locs) {
        Set<Integer> set = new HashSet<Integer>();
        System.out.println("Shells {------------------------------------- ");
        set.addAll(locs.values());
        for (Integer i : set) {
            System.out.println(shells.get(i));
        }
        System.out.println("}---------------------------------------------");
    }

    @SuppressWarnings("unused")
    private ArrayList<Shell> getShellsWithKeys(ArrayList<Shell> shells, HashMap<Integer, Integer> locs) {
        Set<Integer> set = new HashSet<Integer>();
        ArrayList<Shell> retVal = new ArrayList<Shell>();
        set.addAll(locs.values());
        for (Integer i : set) {
            retVal.add(shells.get(i));
        }
        return retVal;
    }

    /**
     * Finds the anoid of the pointset ps.
     *
     * @param ps TODO: describe
     * @param centroid TODO: describe
     * @param d TODO: describe
     * @return the anoid
     */
    public static PointND findAnoid(PointSet ps, PointND centroid, DistanceMatrix d) {
        double maxDist = -1;
        PointND anoid = null;

        for (PointND p : ps) {
            double dist = d.getDistance(p, centroid);
            if (dist > maxDist) {
                maxDist = dist;
                anoid = p;
            }
        }
        return anoid;
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        String str = POINTSET;
        for (int i = 0; i < this.size(); i++) {
            if (this.get(i).getID() != -1) {
                str += this.get(i).getID();
            } else {
                str += this.get(i).toString();
            }
            if (i < this.size() - 1) {
                str += ", ";
            }
        }

        str += STR;

        return str;
    }

    /**
     * TODO: document {@code toStringCoords}.
     *
     * @return TODO: describe
     */
    public String toStringCoords() {
        String str = POINTSET;
        for (int i = 0; i < this.size(); i++) {

            str += this.get(i).toString();
            if (i < this.size() - 1) {
                str += ", \n";
            }
        }

        str += STR;

        return str;
    }

    /**
     * TODO: document {@code getMaxDim}.
     *
     * @return TODO: describe
     */
    public int getMaxDim() {
        int max = 0;
        for (PointND p : this) {
            if (p.getDim() > max) {
                max = p.getDim();
            }
        }
        return max;
    }

    /**
     * TODO: document {@code toArrayList}.
     *
     * @param shell TODO: describe
     * @return TODO: describe
     */
    public ArrayList<Knot> toArrayList(Shell shell) {
        ArrayList<Knot> list = new ArrayList<>();
        for (PointND p : this) {
            Knot vp = new Knot(p, shell);
            list.add(vp);
            shell.pointMap.put(p.getID(), vp);
        }
        return list;

    }

}

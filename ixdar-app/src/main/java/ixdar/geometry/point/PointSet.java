package ixdar.geometry.point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;

/**
 * A set of all of the points in the current TSP problem
 */
public class PointSet extends ArrayList<PointND> {
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

    public PointND getByID(int ID) {
        for (PointND p : this) {
            if (p.getID() == ID) {
                return p;
            }
        }
        return null;
    }

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
     * Finds the anoid of the pointset ps
     * 
     * @param ps
     * @param centroid
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

    @Override
    public String toString() {
        String str = "PointSet[";
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

        str += "]";

        return str;
    }

    public String toStringCoords() {
        String str = "PointSet[";
        for (int i = 0; i < this.size(); i++) {

            str += this.get(i).toString();
            if (i < this.size() - 1) {
                str += ", \n";
            }
        }

        str += "]";

        return str;
    }

    public int getMaxDim() {
        int max = 0;
        for (PointND p : this) {
            if (p.getDim() > max) {
                max = p.getDim();
            }
        }
        return max;
    }

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

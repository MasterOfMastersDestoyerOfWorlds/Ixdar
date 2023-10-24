package shell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * This class represents a Segment between two points in space Stores the start
 * and endpoints of the segment
 */
public class Segment {

	public PointND first;
    public PointND last;

	public Segment(PointND first, PointND last) {
		this.first = first;
		this.last = last;
	}

	/**
	 * Determines equality of segments based on the start and endpoints of the
	 * segment. Two segments are considered equal if they both have the same start
	 * and endpoints or the points are flipped
	 * 
	 * @param o
	 * @return true if the segments are equal and false if they are not
	 */
	@Override
	public boolean equals(Object o) {
		if (o instanceof Segment) {
			Segment other = (Segment) o;
			return ((other.first.equals(first) && other.last.equals(last))) || ((other.first.equals(last) && other.last.equals(first)));
		}else if (o instanceof PointDistanceWrapper) {
			return ((PointDistanceWrapper) o).s.equals(this);
		}
		return false;

	}

	/**
	 * Creates a string representation of the Segment object
	 * 
	 * @return "[first:last]"
	 */
	@Override
	public String toString() {
		String str = "Segment[";
		if (this.first.getID() != -1) {
			str += this.first.getID();
		} else {
			str += this.first.toString();
		}

		str += " : ";
		if (this.last.getID() != -1) {
			str += this.last.getID();
		} else {
			str += this.last.toString();
		}
		str+="]";
		

		return str;
	}

	/**
	 * Creates a unique hashcode for the Segment
	 * 
	 * @return first.hashCode() + last.hashCode()
	 */
	/*@Override
	public int hashCode() {
		return first.hashCode() + last.hashCode();
	}**/

	/*
	 * This code is no longer used but keeping it for history and potential future
	 * use
	 * 
	 * public boolean isOpposite(Segment s) { if(this.equals(s) && (s.first.getX()
	 * == last.getX() && s.last.getX() == first.getX() && s.first.getY() ==
	 * last.getY() && s.last.getY() == first.getY())){ return true; } return false;
	 * }
	 * 
	 * public boolean equalsWithPairity(Object o) { if(o instanceof Segment) {
	 * 
	 * Segment other = (Segment) o; return (other.first.getX() == first.getX() &&
	 * other.last.getX() == last.getX() && other.first.getY() == first.getY() &&
	 * other.last.getY() == last.getY()) ; } return false;
	 * 
	 * }
	 */
	public PointND commonPoint(Segment s) {
		if(s.first.equals(this.first)) {
			return s.first;
		}
		if(s.first.equals(this.last)) {
			return s.first;
		}
		if(s.last.equals(this.first)) {
			return s.last;
		}
		if(s.last.equals(this.last)) {
			return s.last;
		}
		return null;
	}

	public static HashSet<PointND> collectPoints(ArrayList<Segment> segments) {
		HashSet<PointND> set = new HashSet<PointND>();
		for(Segment s : segments){
			set.add(s.first);
			set.add(s.last);
		}
		return set;
	}
	public static HashSet<PointND> collectPoints(Segment ...segments) {
		HashSet<PointND> set = new HashSet<PointND>();
		for(Segment s : segments){
			set.add(s.first);
			set.add(s.last);
		}
		return set;
	}

	public static Segment findFourthSegment(Segment s1, Segment s2, Segment s3) {
		PointND p1 = s1.commonPoint(s3);
		PointND p2 = s1.commonPoint(s2);
		PointND p3 = s2.commonPoint(s3);
		HashSet<PointND> points = collectPoints(s1, s2, s3);
		points.remove(p1);
		points.remove(p2);
		points.remove(p3);
		if(points.size() == 2){
			PointND[] ps = new PointND[2];
			points.toArray(ps);
			return new Segment(ps[0], ps[1]);
		}
		return null;

	}
	
	public boolean contains(PointND p) {
		return this.first.equals(p) || this.last.equals(p);
	}

	public Integer getOther(Integer key) {
		if(this.first.getID() == key) {
			return this.last.getID();
		}
		return this.first.getID();
	}
	
	public PointND getOtherPoint(PointND key) {
		if(this.first.getID() == key.getID()) {
			return this.last;
		}
		return this.first;
	}

	public PointND getOtherPoint(Integer key) {
		if(this.first.getID() == key) {
			return this.last;
		}
		return this.first;
	}

	public static Segment findInList(ArrayList<Segment> segs, PointND p, ArrayList<PointND> excusionList){
		for (Segment segment : segs) {
			if(segment.contains(p) && !excusionList.contains(segment.getOtherPoint(p))){
				return segment;
			}
		}
		return null;
	}
	public ArrayList<Segment> permute(PointND endpoint1, PointND endpoint2){
		ArrayList<Segment> mergeSegments = new ArrayList<Segment>();

		Shell shell1 = new Shell(endpoint1, first, last, endpoint2);
		double length1 = shell1.getLengthEndpoints();
		System.out.println("shell1: " + shell1 + " length: " + length1);

		int perm = 1;
		double minlength = length1;

		Shell shell2 = new Shell(endpoint1, last, first, endpoint2);
		double length2 = shell2.getLengthEndpoints();
		System.out.println("shell2: " + shell2 + " length: " + length2);
		if (length2 < minlength) {
			perm = 2;
			minlength = length2;
		}

		if (perm == 1) {
			System.out.println("perm1");
			mergeSegments.add(new Segment(endpoint1, first));
			mergeSegments.add(new Segment(first, last));
			mergeSegments.add(new Segment(last, endpoint2));
			return mergeSegments;
		}
		else{
			System.out.println("perm2");
			mergeSegments.add(new Segment(endpoint1, last));
			mergeSegments.add(new Segment(first, last));
			mergeSegments.add(new Segment(first, endpoint2));
			return mergeSegments;
		}
	} 
}

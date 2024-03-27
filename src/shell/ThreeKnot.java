package shell;

import java.util.ArrayList;

/**
 * A class that represents the distances between all points in the pointset
 */
public class ThreeKnot extends ArrayList<Segment> {
	PointND p1;
	PointND p2;
	PointND p3;

	Bucket bucket;

	public ThreeKnot(Bucket b) {
		this.bucket = b;
	}

	@Override
	public boolean add(Segment s) {
		if (p1 == null) {
			p1 = s.first;
			p2 = s.last;
		} else if (p3 == null) {
			if (s.contains(p1)) {
				p3 = s.getOtherPoint(p1);
			} else {
				p3 = s.getOtherPoint(p2);
			}
		}
		return super.add(s);
	}

	public boolean hasEndpoint(PointND p){
		return p1.equals(p) || p2.equals(p) || p3.equals(p);
	}

	public ArrayList<Segment> permute(PointND endpoint1, PointND endpoint2) {
		ArrayList<Segment> mergeSegments = new ArrayList<Segment>();

		Shell shell1 = new Shell(endpoint1, p1, p2, p3, endpoint2);
		double length1 = shell1.getLengthEndpoints();
		System.out.println("shell1: " + shell1 + " length: " + length1);

		int perm = 1;
		double minlength = length1;

		Shell shell2 = new Shell(endpoint1, p2, p1, p3, endpoint2);
		double length2 = shell2.getLengthEndpoints();
		System.out.println("shell2: " + shell2 + " length: " + length2);
		if (length2 < minlength) {
			perm = 2;
			minlength = length2;
		}

		Shell shell3 = new Shell(endpoint1, p3, p2, p1, endpoint2);
		double length3 = shell3.getLengthEndpoints();
		System.out.println("shell3: " + shell3 + " length: " + length3);

		if (length3 < minlength) {
			perm = 3;
			minlength = length3;
		}

		Shell shell4 = new Shell(endpoint1, p3, p1, p2, endpoint2);
		double length4 = shell4.getLengthEndpoints();
		System.out.println("shell4: " + shell4 + " length: " + length4);

		if (length4 < minlength) {
			perm = 4;
			minlength = length4;
		}

		Shell shell5 = new Shell(endpoint1, p2, p3, p1, endpoint2);
		double length5 = shell5.getLengthEndpoints();
		System.out.println("shell5: " + shell5 + " length: " + length5);

		if (length5 < minlength) {
			perm = 5;
			minlength = length5;
		}

		Shell shell6 = new Shell(endpoint1, p1, p3, p2, endpoint2);
		double length6 = shell6.getLengthEndpoints();
		System.out.println("shell6: " + shell6 + " length: " + length6);

		if (length6 < minlength) {
			perm = 6;
			minlength = length6;
		}

		if (perm == 1) {
			System.out.println("perm1");
			mergeSegments.add(new Segment(endpoint1, p1));
			mergeSegments.add(new Segment(p1, p2));
			mergeSegments.add(new Segment(p2, p3));
			mergeSegments.add(new Segment(endpoint2, p3));
			return mergeSegments;
		}
		if (perm == 2) {
			System.out.println("perm2");
			mergeSegments.add(new Segment(endpoint1, p2));
			mergeSegments.add(new Segment(p1, p2));
			mergeSegments.add(new Segment(p1, p3));
			mergeSegments.add(new Segment(endpoint2, p3));
			return mergeSegments;
		}
		if (perm == 3) {
			System.out.println("perm3");
			mergeSegments.add(new Segment(endpoint1, p3));
			mergeSegments.add(new Segment(p3, p2));
			mergeSegments.add(new Segment(p2, p1));
			mergeSegments.add(new Segment(endpoint2, p1));
			return mergeSegments;
		}
		if (perm == 4) {
			System.out.println("perm4");
			mergeSegments.add(new Segment(endpoint1, p3));
			mergeSegments.add(new Segment(p1, p2));
			mergeSegments.add(new Segment(p1, p3));
			mergeSegments.add(new Segment(endpoint2, p2));
			return mergeSegments;
		}

		if (perm == 5) {
			System.out.println("perm5");
			mergeSegments.add(new Segment(endpoint1, p2));
			mergeSegments.add(new Segment(p3, p2));
			mergeSegments.add(new Segment(p1, p3));
			mergeSegments.add(new Segment(endpoint2, p1));
			return mergeSegments;
		} else {
			System.out.println("perm6");
			mergeSegments.add(new Segment(endpoint1, p1));
			mergeSegments.add(new Segment(p3, p2));
			mergeSegments.add(new Segment(p1, p3));
			mergeSegments.add(new Segment(endpoint2, p2));
			return mergeSegments;
		}
	}

	public String toString() {
		String str = "3-Knot :[ " + p1 + ", " + p2 + ", " + p3 + " ]\n" + super.toString() + "\n";
		return str;

	}

}

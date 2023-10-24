package shell;

import java.util.ArrayList;

/**
 * A class that represents the distances between all points in the pointset
 */
public class Group {
	boolean singleton;
	Shell s1;
	PointND match1;
	PointND outside1;
	Shell s2;
	PointND match2;
	PointND outside2;
	Segment match;
	Bucket b;

	public Group(Bucket b, Shell s1, Shell s2, Segment match) {
		if (s1.equals(s2)) {
			this.singleton = true;
		} else {
			this.singleton = false;
		}
		this.b = b;
		this.s1 = s1;
		this.s2 = s2;
		this.match = match;
		if(s1.contains(match.first)){
			match1 = match.first;
			match2 = match.last;
		}else{
			match1 = match.last;
			match2 = match.first;
		}
		outside1 = s1.getOppositeOutside(match1);
		outside2 = s2.getOppositeOutside(match2);
	}

	public Group(Bucket b, Shell s1) {
		this.singleton = true;
		this.b = b;
		this.s1 = s1;
	}

	public String toString() {
		if (!singleton) {
			String str = "Group :[ s1:" + s1 + ", match:"+match+", s2:" + s2 + " ]";
			return str;
		} else {
			String str = "Group :[ " + s1 + " ]";
			return str;
		}

	}

	public boolean contains(PointND p) {
		return this.s1.contains(p) || (this.s2 != null) && this.s2.contains(p);
	}

	public static boolean containsPoint(ArrayList<Group> groups, PointND p) {
		for (Group group : groups) {
			if (group.contains(p)) {
				return true;
			}
		}
		return false;
	}

	public static ArrayList<PointND> flattenToPoints(ArrayList<Group> groups) {
		ArrayList<PointND> points = new ArrayList<PointND>();
		for (Group group : groups) {
			points.add(group.s1.getFirst());
			if (group.s1.size() > 1) {
				points.add(group.s1.getLast());
			}
			if (group.s2 != null) {
				points.add(group.s2.getFirst());
				if (group.s2.size() > 1) {
					points.add(group.s2.getLast());
				}
			}
		}
		return points;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof Group) {
			Group other = (Group) o;
			return ((other.s1.equals(s1))) && other.s2.equals(s2) || ((other.s1.equals(s2) && other.s2.equals(s1)));
		}
		return false;

	}

}

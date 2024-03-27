package shell;

import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * A class that represents the distances between all points in the pointset
 */
public class SubBucket extends ArrayList<Segment> {
	public Shell shell;
	public Bucket bucket;
	public PointND p;

	public SubBucket(Shell shell, Bucket bucket, PointND p) {
		this.shell = shell;
		this.bucket = bucket;
		this.p = p;
	}

	public Segment getFirstNotIn(ArrayList<PointND> excludeList) {
		for (Segment s : this) {
			if (!excludeList.contains(s.getOtherPoint(p))) {
				return s;
			}
		}
		return null;
	}

	public Segment getSecondNotIn(ArrayList<PointND> excludeList) {
		int count = 0;
		for (Segment s : this) {
			if (!excludeList.contains(s.getOtherPoint(p))) {
				if (count == 0) {
					count++;
				} else {
					return s;
				}
			}
		}
		return null;
	}

	public Segment getOtherSegment(Segment s) {
		if (shell.size() == 1) {
			if (this.get(0).equals(s)) {
				return this.get(1);
			} else {
				return this.get(0);
			}
		} else {
			return bucket.get(shell.getOppositeOutside(p).getID()).get(0);
		}
	}

}

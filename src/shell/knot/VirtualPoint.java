
package shell.knot;

import java.util.ArrayList;
import java.util.HashMap;
import shell.shell.Shell;

public abstract class VirtualPoint {
	public int numMatches;
	public Point match1endpoint;
	public VirtualPoint match1;
	public Point basePoint1;
	public Segment s1;
	public Point match2endpoint;
	public VirtualPoint match2;
	public Point basePoint2;
	public ArrayList<VirtualPoint> externalVirtualPoints;
	public ArrayList<VirtualPoint> knotPointsFlattened;
	public ArrayList<Segment> sortedSegments;
	public HashMap<Long, Segment> segmentLookup;
	public Segment s2;
	public int id;
	public boolean isKnot;
	public boolean isRun;
	public VirtualPoint group;
	public VirtualPoint topGroup;
	VirtualPoint topGroupVirtualPoint;
	Shell shell;

	public Segment getPointer(int idx) {
		int count = idx;
		ArrayList<Segment> seenGroups = new ArrayList<Segment>();
		ArrayList<VirtualPoint> seenPoints = new ArrayList<VirtualPoint>();
		for (int i = 0; i < sortedSegments.size(); i++) {
			Segment s = sortedSegments.get(i);
			VirtualPoint knotPoint = s.getKnotPoint(knotPointsFlattened);
			boolean ep1 = false;
			if (this.isRun) {
				if (((Run) this).endpoint1.contains(knotPoint)) {
					ep1 = true;
				} else {
					ep1 = false;
				}
			}
			VirtualPoint basePoint = s.getOther(knotPoint);
			VirtualPoint vp = basePoint.group;
			if (vp.group != null) {
				vp = vp.group;
			}
			Segment potentialSegment = new Segment(basePoint, knotPoint, 0);
			if ((!vp.isRun || ((Run) vp).endpoint1.contains(basePoint) || ((Run) vp).endpoint2.contains(basePoint))
					&& (!seenGroups.contains(potentialSegment)) && (!seenPoints.contains(knotPoint))
					&& (!seenPoints.contains(basePoint))

					&& (!this.isRun || (ep1 && !seenPoints.contains(((Run) this).endpoint1))
							|| (!ep1 && !seenPoints.contains(((Run) this).endpoint2)))
					|| potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
				count--;
				if (count == 0) {
					return s;
				}
				seenGroups.add(potentialSegment);
				if (this.isKnot || this.isRun) {
					seenPoints.add(knotPoint);
					if (this.isRun) {
						Run r = (Run) this;
						if (r.endpoint1.contains(knotPoint)) {
							seenPoints.add(r.endpoint1);
						} else {
							seenPoints.add(r.endpoint2);
						}
					}
				}
				if (vp.isKnot || vp.isRun) {
					seenPoints.add(basePoint);
				}
			}
		}
		return null;
	}

	public Segment getFirstUnmatched(ArrayList<VirtualPoint> runList) {

		for (Segment s : sortedSegments) {
			VirtualPoint other = s.getOtherKnot(this);
			if ((match1 == null || !match1.contains(other))
					&& (match2 == null || !match2.contains(other))) {
				return s;
			}
		}
		return s1;
	}

	public boolean shouldJoinKnot(Knot k) {
		shell.buff.add(k.fullString());
		int desiredCount = k.size() * this.size();
		boolean oneOutFlag = false;
		for (Segment s : this.sortedSegments) {
			VirtualPoint vp = s.getOtherKnot(this);

			if (!k.contains(vp)) {
				if (!oneOutFlag) {
					oneOutFlag = true;
				} else {
					shell.buff.add("broke on this segment: " + s + " desired count: " + desiredCount
							+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
					return false;
				}
			}
			desiredCount--;
			if (desiredCount == 0) {
				return true;
			}
		}
		return true;
	}

	public boolean shouldKnotConsumeExclude(Knot k, ArrayList<VirtualPoint> runList) {
		ArrayList<VirtualPoint> exclude = new ArrayList<>(runList);
		exclude.remove(this);
		exclude.remove(k);
		shell.buff.add(k.fullString());
		int desiredCount = k.size() * this.size();
		HashMap<Integer, Integer> count = new HashMap<>();
		boolean oneOutFlag = false;
		for (Segment s : k.sortedSegments) {
			VirtualPoint vp = s.getOtherKnot(k);
			VirtualPoint knotVp = s.getOther(vp);
			boolean continueFlag = false;
			int val = count.getOrDefault(knotVp.id, 0);
			if (val >= k.size()) {
				continue;
			}
			for (VirtualPoint eVP : exclude) {
				if (eVP.contains(vp)) {
					continueFlag = true;
					break;
				}
			}
			if (continueFlag) {
				continue;
			}
			if (!this.contains(vp)) {
				if (!oneOutFlag) {
					oneOutFlag = true;
				} else {
					shell.buff.add("broke on this segment: " + s + " desired count: " + desiredCount
							+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
					return false;
				}
			}
			count.put(knotVp.id, val + 1);
			desiredCount--;
			if (desiredCount == 0) {
				return true;
			}
		}
		return true;
	}

	public boolean shouldKnotConsume(Knot k) {
		shell.buff.add(k.fullString());
		int desiredCount = k.size() * this.size();
		boolean oneOutFlag = false;
		HashMap<Integer, Integer> count = new HashMap<>();
		for (Segment s : k.sortedSegments) {
			VirtualPoint vp = s.getOtherKnot(k);
			VirtualPoint knotVp = s.getOther(vp);
			int val = count.getOrDefault(knotVp.id, 0);
			if (val >= k.size()) {
				continue;
			}
			if (!this.contains(vp)) {
				if (!oneOutFlag) {
					oneOutFlag = true;
				} else {
					shell.buff.add("broke on this segment: " + s + " desired count: " + desiredCount
							+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
					return false;
				}
			}
			count.put(knotVp.id, val + 1);
			desiredCount--;
			if (desiredCount == 0) {
				return true;
			}
		}
		return true;
	}

	public int size() {
		return knotPointsFlattened.size();
	}

	public Segment getClosestSegment(VirtualPoint vp, Segment excludeSegment) {
		VirtualPoint excludeThis = excludeSegment == null ? null : excludeSegment.getKnotPoint(knotPointsFlattened);
		VirtualPoint excludeOther = excludeSegment == null ? null
				: excludeSegment.getKnotPoint(vp.knotPointsFlattened);

		for (int i = 0; i < sortedSegments.size(); i++) {
			Segment s = sortedSegments.get(i);
			if (vp.isKnot) {
				Knot knot = (Knot) vp;
				if (s.getKnotPoint(knot.knotPointsFlattened) != null && (excludeSegment == null
						|| ((!(vp.isKnot || vp.isRun) || !s.contains(excludeOther))
								&& (!(this.isKnot || this.isRun) || !s.contains(excludeThis))))) {
					return s;
				}
			}
			if (vp.isRun) {
				Run run = (Run) vp;
				if (s.getKnotPoint(run.externalVirtualPoints) != null && (excludeSegment == null
						|| ((!(vp.isKnot || vp.isRun) || !s.contains(excludeOther))
								&& (!(this.isKnot || this.isRun) || !s.contains(excludeThis))))) {
					return s;
				}
			} else {
				if (s.contains(vp) && (!(this.isKnot || this.isRun) || !s.contains(excludeThis))) {
					return s;
				}
			}
		}

		@SuppressWarnings("unused")
		float zero = 1 / 0;
		return null;
	}

	public Segment getSegment(VirtualPoint vp) {
		long a = this.id;
		long b = vp.id;
		long id = a >= b ? a * a + a + b : b + a + b * b;
		Segment look = this.segmentLookup.get(id);
		return look;
	}

	public Point getNearestBasePoint(VirtualPoint vp) {
		throw new UnsupportedOperationException("Unimplemented method 'getPointer'");
	}

	public String fullString() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'fullString'");
	}

	public boolean contains(VirtualPoint vp) {
		throw new UnsupportedOperationException("Unimplemented method 'contains'");
	}

	public int getHeight() {
		if (this.isKnot) {
			Knot k = (Knot) this;
			int max = 1;
			for (VirtualPoint vp : k.knotPoints) {
				if (vp.isKnot) {
					int h = vp.getHeight() + 1;
					if (h > max) {
						max = h;
					}
				}
			}
			return max;
		} else {
			return 1;
		}
	}

	public void setMatch1(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {
		match1 = matchPoint;
		match1endpoint = matchEndPoint;
		basePoint1 = matchBasePoint;
		s1 = matchSegment;
		if (s2 != null && s1.distance > s2.distance) {
			this.swap();
		}
		this.checkValid();
	}

	public void setMatch1(VirtualPoint matchPoint) {

		match1 = matchPoint;
		if (matchPoint.match1.equals(this)) {
			match1endpoint = matchPoint.basePoint1;
			basePoint1 = matchPoint.match1endpoint;
			s1 = matchPoint.s1;
		} else if (matchPoint.match2 != null && matchPoint.match2.equals(this)) {
			match1endpoint = matchPoint.basePoint2;
			basePoint1 = matchPoint.match2endpoint;
			s1 = matchPoint.s2;
		}
		if (s2 != null && s1.distance > s2.distance) {
			this.swap();
		}
		this.checkValid();
	}

	public void setMatch2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {
		match2 = matchPoint;
		match2endpoint = matchEndPoint;
		basePoint2 = matchBasePoint;
		s2 = matchSegment;
		if (s1 == null || (s1 != null && s2 != null && s1.distance > s2.distance)) {
			this.swap();
		}
		if ((this.isKnot || this.isRun) && basePoint1 != null && basePoint2 != null
				&& basePoint1.equals(basePoint2)) {
			shell.buff.add("REEEEEEEEEEEEEEEEEEEEEEEE");
			// shell.buff.add(this.fullString());
			// Segment fixSeg = this.getClosestSegment(match2, s1);
			// VirtualPoint bp = fixSeg.getKnotPoint(knotPointsFlattened);
			// VirtualPoint me = fixSeg.getOther(bp);
			// this.setMatch2(match2, (Point) me, (Point) bp, fixSeg);
			// match2.setMatch(this.contains(match2.match1endpoint), this);
		}
		this.checkValid();
	}

	public void setMatch2(VirtualPoint matchPoint) {

		match2 = matchPoint;
		if (matchPoint.match1.equals(this)) {
			match2endpoint = matchPoint.basePoint1;
			basePoint2 = matchPoint.match1endpoint;
			s2 = matchPoint.s1;
		} else if (matchPoint.match2.equals(this)) {
			match2endpoint = matchPoint.basePoint2;
			basePoint2 = matchPoint.match2endpoint;
			s2 = matchPoint.s2;
		}
		if (s1 != null && s2 != null && s1.distance > s2.distance) {
			this.swap();
		}
		if (s1 == null && s2 != null) {
			this.swap();
		}
		this.checkValid();
	}

	public boolean hasMatch(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {

		if (match1 != null && match1.equals(matchPoint) && match1endpoint.equals(matchEndPoint)
				&& basePoint1.equals(matchBasePoint) && s1.equals(matchSegment)) {
			return true;
		}
		if (match2 != null && match2.equals(matchPoint) && match2endpoint.equals(matchEndPoint)
				&& basePoint2.equals(matchBasePoint) && s2.equals(matchSegment)) {
			return true;
		}
		return false;

	}

	public boolean hasMatch(VirtualPoint matchPoint) {

		if (match1 != null && match1.contains(matchPoint)) {
			return true;
		}
		if (match2 != null && match2.contains(matchPoint)) {
			return true;
		}
		return false;

	}

	@SuppressWarnings("unused")
	private void checkValid() {
		if (match1 == null && match2 != null) {
			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}

		if (s1 == null && s2 != null) {
			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}
		if (s2 != null && s1.distance > s2.distance) {
			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}
		if ((match1endpoint != null && basePoint1 == null) || (basePoint1 != null && match1endpoint == null)) {

			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}
		if ((match2endpoint != null && basePoint2 == null) || (basePoint2 != null && match2endpoint == null)) {

			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}

		if ((match1 != null && !match1.contains(match1endpoint))
				|| (match2 != null && !match2.contains(match2endpoint))) {

			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}

		if ((match1 == null && (match1endpoint != null || basePoint1 != null))
				|| (match2 == null && (match2endpoint != null || basePoint2 != null))) {

			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}
		if (match1endpoint != null && match2endpoint != null && (match1.isKnot || match1.isRun)
				&& (match2.isKnot || match2.isRun) && match1endpoint.equals(match2endpoint)) {

			shell.buff.add(this.fullString());
			float zero = 1 / 0;
		}
		if ((this.isKnot || this.isRun) && basePoint1 != null && basePoint2 != null
				&& basePoint1.equals(basePoint2)) {

			shell.buff.add(this.fullString());
			// float zero = 1 / 0;
		}
	}

	public void setMatch(boolean match1, VirtualPoint matchPoint) {
		if (match1) {
			this.setMatch1(matchPoint);
		} else {
			this.setMatch2(matchPoint);
		}
	}

	public void setMatch(boolean match1, VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {
		if (match1) {
			this.setMatch1(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
		} else {
			this.setMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
		}
	}

	public void matchAcross2(VirtualPoint matchPoint, Segment matchSegment, Segment otherSegment,
			Segment cutSegment) {

		VirtualPoint p1 = this;
		if (this.isKnot) {
			p1 = matchSegment.getKnotPoint(((Knot) this).knotPointsFlattened);
		}

		shell.buff.add(p1);
		this.setMatch((!this.match1.equals(this.match2) && this.match1.contains(matchSegment.getOtherKnot(this))) ||
				matchSegment.contains(this.match1endpoint)
				|| (this.match1.equals(this.match2) && otherSegment.contains(this.match2endpoint)),
				matchPoint,
				(Point) matchSegment.getOther(p1),
				(Point) p1,
				matchSegment);
		matchPoint.setMatch(
				cutSegment.contains(matchPoint.match1endpoint) && cutSegment.contains(matchPoint.basePoint1), this);

	}

	public void swapMatch2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {
		VirtualPoint tmp = match1;
		Point tmpMe = match1endpoint;
		Point tmpBp = basePoint1;
		Segment tmpSeg = s1;
		match1 = matchPoint;
		match1endpoint = matchEndPoint;
		basePoint1 = matchBasePoint;
		s1 = matchSegment;
		match2 = tmp;
		match2endpoint = tmpMe;
		basePoint2 = tmpBp;
		s2 = tmpSeg;
	}

	public void swap() {
		VirtualPoint tmp = match1;
		Point tmpMe = match1endpoint;
		Point tmpBp = basePoint1;
		Segment tmpSeg = s1;
		match1 = match2;
		match1endpoint = match2endpoint;
		basePoint1 = basePoint2;
		s1 = s2;
		match2 = tmp;
		match2endpoint = tmpMe;
		basePoint2 = tmpBp;
		s2 = tmpSeg;
	}

	public void checkAndSwap2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
			Segment matchSegment) {
		double d1 = shell.distanceMatrix.getDistance(this.basePoint1.p, this.match1endpoint.p);
		double d2 = shell.distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
		if (d1 > d2) {
			this.swapMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
		} else {
			this.setMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
		}
	}

	public void reset() {
		numMatches = 0;
		match1 = null;
		match1endpoint = null;
		basePoint1 = null;
		match2 = null;
		match2endpoint = null;
		basePoint2 = null;
		group = this;
		topGroup = this;
		s1 = null;
		s2 = null;
	}

	public void reset(VirtualPoint match) {
		if (match1 != null && match1.contains(match)) {
			numMatches--;
			match1 = null;
			match1endpoint = null;
			basePoint1 = null;
			s1 = null;
		}

		if (match2 != null && match2.contains(match)) {
			numMatches--;
			match2 = null;
			match2endpoint = null;
			basePoint2 = null;
			s2 = null;
		}
		if (match1 == null && match2 != null) {
			this.swap();
		}

	}

	public void copyMatches(VirtualPoint vp) {
		match1 = vp.match1;
		match1endpoint = vp.match1endpoint;
		basePoint1 = vp.basePoint1;
		s1 = vp.s1;
		match2 = vp.match2;
		match2endpoint = vp.match2endpoint;
		basePoint2 = vp.basePoint2;
		s2 = vp.s2;
	}

}
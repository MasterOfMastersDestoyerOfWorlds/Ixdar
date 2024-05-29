package shell;
import java.util.ArrayList;

public final class RunListUtils{
	public static ArrayList<VirtualPoint> flattenRunPoints(ArrayList<VirtualPoint> knotPoints, boolean knot) {
		ArrayList<VirtualPoint> flattenRunPoints = new ArrayList<>();
		boolean twoKnot = knotPoints.size() == 2 && knot;
		if (twoKnot) {
			if (knotPoints.get(0).isRun && knotPoints.get(1).isRun) {
				Run run1 = (Run) knotPoints.get(0);
				Run run2 = (Run) knotPoints.get(1);
				Segment s11 = run1.endpoint1.getClosestSegment(run2.endpoint1, null);
				Segment s12 = run1.endpoint2.getClosestSegment(run2.endpoint2, null);
				Double d1 = s11.distance + s12.distance;
				Segment s21 = run1.endpoint1.getClosestSegment(run2.endpoint2, null);
				Segment s22 = run1.endpoint2.getClosestSegment(run2.endpoint1, null);
				Double d2 = s21.distance + s22.distance;
				if (d1 < d2) {
					if (run1.endpoint1.contains(run1.knotPoints.get(0))) {
						flattenRunPoints.addAll(run1.knotPoints);
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					} else {
						for (VirtualPoint vp : run1.knotPoints) {
							flattenRunPoints.add(0, vp);
						}
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					}
				} else {
					if (run1.endpoint1.contains(run1.knotPoints.get(0))) {
						for (VirtualPoint vp : run1.knotPoints) {
							flattenRunPoints.add(0, vp);
						}
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					} else {
						flattenRunPoints.addAll(run1.knotPoints);
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					}
				}
				return flattenRunPoints;
			}
		}
		for (int i = 0; i < knotPoints.size(); i++) {
			VirtualPoint vp = knotPoints.get(i);
			if (i + 1 >= knotPoints.size() && !knot) {
				if (vp.isRun) {
					Run run = ((Run) vp);
					if (run.endpoint1.contains(run.basePoint1)) {
						for (VirtualPoint p : run.knotPoints) {
							flattenRunPoints.add(p);
						}
					} else {
						int end = flattenRunPoints.size();
						for (VirtualPoint p : run.knotPoints) {
							flattenRunPoints.add(end, p);
						}
					}
				} else {
					flattenRunPoints.add(vp);
				}
				break;
			}

			VirtualPoint vp2 = null;
			if (i + 1 >= knotPoints.size() && knot) {
				vp2 = knotPoints.get(0);
			} else {
				vp2 = knotPoints.get(i + 1);
			}
			if (vp.isRun) {
				Run run = ((Run) vp);
				if ((run.endpoint1.contains(run.basePoint1)
						&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
								|| vp2.contains(run.match1endpoint)))
						|| (!twoKnot
								&& (!run.endpoint1.contains(run.basePoint1) && run.endpoint1.contains(run.basePoint2)
										&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match2endpoint))
												|| vp2.contains(run.match2endpoint))))
						|| (twoKnot && i + 1 >= knotPoints.size() && (run.endpoint2.contains(run.basePoint1)
								&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
										|| vp2.contains(run.match1endpoint))))) {

					int end = flattenRunPoints.size();
					for (VirtualPoint p : run.knotPoints) {
						flattenRunPoints.add(end, p);
					}

				} else {
					for (VirtualPoint p : run.knotPoints) {
						flattenRunPoints.add(p);
					}
				}

			} else {
				flattenRunPoints.add(vp);
			}
		}
		return flattenRunPoints;
	}

	public static void fixRunList(ArrayList<VirtualPoint> flattenRunPoints, int end) {
		for (int i = 0; i < end; i++) {
			VirtualPoint vp = flattenRunPoints.get(i);
			VirtualPoint vp2 = null;
			if (i < flattenRunPoints.size() - 1) {
				vp2 = flattenRunPoints.get(i + 1);
			} else {
				vp2 = flattenRunPoints.get(0);
			}
			vp.reset();
			vp2.reset();
		}
		for (int i = 0; i < end; i++) {
			VirtualPoint vp = flattenRunPoints.get(i);
			VirtualPoint vp2 = null;
			if (i < flattenRunPoints.size() - 1) {
				vp2 = flattenRunPoints.get(i + 1);
			} else {
				vp2 = flattenRunPoints.get(0);
			}
			// buff.add("BEfore: ");
			// buff.add(vp.fullString());
			// buff.add(vp2.fullString());
			Segment s = vp.getClosestSegment(vp2, vp.s1);
			VirtualPoint bp1 = s.getKnotPoint(vp.externalVirtualPoints);
			VirtualPoint bp2 = s.getOther(bp1);
			if (vp2.basePoint1 != null && vp2.isKnot && vp2.basePoint1.equals(bp2)) {
				s = vp.getClosestSegment(vp2, vp2.s1);
				bp1 = s.getKnotPoint(vp.externalVirtualPoints);
				bp2 = s.getOther(bp1);
			}
			vp.setMatch(vp.match1 == null, vp2, (Point) bp2, (Point) bp1, s);
			vp2.setMatch(vp2.match1 == null, vp, (Point) bp1, (Point) bp2, s);
			vp.numMatches = 2;
			vp2.numMatches = 2;

			// buff.add("After:");
			// buff.add(vp.fullString());
			// buff.add(vp2.fullString());
			// buff.add();

		}
	}
}

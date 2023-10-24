package shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.commons.math3.util.Pair;

public class Bucket extends HashMap<Integer, SubBucket> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public int size() {
		System.out.println(this.keySet());
		return super.size();
	}

	public void add(Segment s, ArrayList<Shell> shells) {
		int firstId = s.first.getID();
		int lastId = s.last.getID();
		SubBucket firstList = this.getOrDefault(firstId, new SubBucket(getShellByID(shells, firstId), this, s.first));
		SubBucket lastList = this.getOrDefault(lastId, new SubBucket(getShellByID(shells, lastId), this, s.last));
		if (!firstList.contains(s)) {
			firstList.add(s);
		}
		if (!lastList.contains(s)) {
			lastList.add(s);
		}
		this.put(firstId, firstList);
		this.put(lastId, lastList);
	}

	public SubBucket get(PointND p) {
		return this.get(p.getID());
	}

	public void remove(Segment s) {
		int firstId = s.first.getID();
		int lastId = s.last.getID();
		this.get(firstId).remove(s);
		this.get(lastId).remove(s);
	}

	public Shell getShellByID(ArrayList<Shell> shells, int id) {
		for (Shell shell : shells) {
			if (shell.containsID(id)) {
				return shell;
			}
		}
		return null;
	}

	public ArrayList<Segment> getMatches(ArrayList<Shell> shells) {
		ArrayList<Segment> matches = new ArrayList<Segment>();
		for (Integer key : this.keySet()) {
			SubBucket subBucket = this.get(key);
			Segment s = this.get(key).get(0);
			if (!matches.contains(s)) {
				SubBucket otherList = this.get(s.getOther(key));
				Segment otherSeg = otherList.get(0);

				if (otherSeg.equals(s)) {
					matches.add(s);
				}
				if (otherList.shell.size() == 1) {
					otherSeg = otherList.get(1);
					if (otherSeg.equals(s)) {
						matches.add(s);
					}
				}
			}
			if (subBucket.shell.size() == 1) {
				s = this.get(key).get(1);

				if (!matches.contains(s)) {
					SubBucket otherList = this.get(s.getOther(key));
					Segment otherSeg = otherList.get(0);

					if (otherSeg.equals(s)) {
						matches.add(s);
					}
					if (otherList.shell.size() == 1) {
						otherSeg = otherList.get(1);
						if (otherSeg.equals(s)) {
							matches.add(s);
						}
					}
				}
			}

		}
		/*
		 * for (Integer key : this.keySet()) {
		 * Segment s = this.get(key).get(0);
		 * if (!matches.contains(s)) {
		 * ArrayList<Segment> other = this.get(s.getOther(key));
		 * PointND otherp = other.get(0).getOtherPoint(key);
		 * PointND p = other.get(0).getOtherPoint(otherp);
		 * if (other.get(0).equals(s)) {
		 * matches.add(s);
		 * }
		 * else if(!(singletons.contains(otherp) && singletons.contains(p)) &&
		 * singletons.contains(s.first) && singletons.contains(s.last)){
		 * matches.add(s);
		 * }
		 * }
		 * }
		 */

		return matches;

	}

	public ArrayList<Segment> getProspectiveMatches(ArrayList<Shell> shells) {
		ArrayList<Segment> matches = new ArrayList<Segment>();
		for (Integer key : this.keySet()) {
			SubBucket subBucket = this.get(key);
			Segment s = this.get(key).get(0);
			if (!matches.contains(s)) {
				matches.add(s);
			}
			if (subBucket.shell.size() == 1) {
				s = this.get(key).get(1);

				if (!matches.contains(s)) {
					matches.add(s);
				}
			}

		}

		return matches;

	}

	

	public void removeAll(Segment s) {
		removeAll(s.first);
		removeAll(s.last);

	}

	public void removeAll(PointND p) {
		int firstId = p.getID();
		this.remove(firstId);
		for (Integer key : this.keySet()) {
			this.get(key).removeIf(n -> (n.contains(p)));
		}
	}

	public void removeAllInternal(Shell s, ArrayList<Segment> matches) {
		for (PointND p1 : s) {
			for (PointND p2 : s) {
				if (!p1.equals(p2)) {
					if (this.containsKey(p1.getID())) {
						this.get(p1.getID()).removeIf(n -> (n.contains(p2)));
						matches.removeIf(n -> (n.contains(p2) && n.contains(p1)));
					}
				}
			}
		}
	}

	public Segment getNotInList(PointND p, ArrayList<Segment> segments, ArrayList<PointND> list) {
		Segment other;
		for (int i = 0; i < segments.size(); i++) {
			other = segments.get(i);
			if (!list.contains(other.getOtherPoint(p))) {
				return other;
			} else {
				// System.out.println("Segment is in List: " + other + " " + list);
			}
		}
		return null;
	}

	public ArrayList<Segment> getNBucketExcludeList(PointND p, ArrayList<Segment> segments,
			ArrayList<PointND> excludeList, int n) {
		ArrayList<Segment> bucket = new ArrayList<Segment>();
		int count = 0;
		for (int i = 0; i < segments.size(); i++) {
			Segment other = segments.get(i);
			if (!excludeList.contains(other.getOtherPoint(p))) {
				bucket.add(other);
				count++;
				if (count >= n) {
					break;
				}
			}
		}
		return bucket;
	}

	public ArrayList<Group> getNGroupsExcludeList(PointND p, ArrayList<Segment> segments,
			ArrayList<PointND> excludeList, ArrayList<Segment> matchList, int n) {
		ArrayList<Group> groups = new ArrayList<Group>();
		excludeList = (ArrayList<PointND>) excludeList.clone();

		System.out.println("P " + p + " matchList: " + matchList + " exclude list: " + excludeList);
		int totalGroups = 0;
		for (int i = 0; i < segments.size(); i++) {
			Segment s = segments.get(i);
			PointND otherp = s.getOtherPoint(p);
			if (!excludeList.contains(otherp)) {
				Segment match = Segment.findInList(matchList, otherp, excludeList);
				if (match != null) {
					Group g = new Group(this, this.get(match.first).shell, this.get(match.last).shell, match);
					if (!groups.contains(g)) {
						groups.add(g);
						System.out.println("MATHCHCHCHHCHCH: " + match + "otherp: " + otherp + " group : " + g);
						totalGroups++;
					}

				} else {
					PointND segPartner = this.get(otherp).shell.getOppositeOutside(otherp);
					match = Segment.findInList(matchList, segPartner, excludeList);
					if (match != null) {
						Group g = new Group(this, this.get(match.first).shell, this.get(match.last).shell, match);
						if (!groups.contains(g)) {
							System.out.println("MABALLS: " + match + " " + this.get(otherp).shell + " " + segPartner);
							groups.add(g);
							totalGroups++;
						}

					} else {
						groups.add(new Group(this, this.get(otherp).shell));
						totalGroups++;
					}
				}
			}
		}
		return groups;
	}

	private Segment getInList(PointND p, ArrayList<Segment> segments, ArrayList<PointND> list) {
		Segment other;
		for (int i = 0; i < segments.size(); i++) {
			other = segments.get(i);
			if (list.contains(other.getOtherPoint(p))) {
				return other;
			}
		}
		return null;
	}

	public Pair<PointND, ArrayList<Segment>> checkMatchExcludeEndpoints(PointND p, ArrayList<PointND> endpointlist,
			ArrayList<PointND> singletons) {
		ArrayList<PointND> endpoints = new ArrayList<PointND>(endpointlist);
		endpoints.removeIf((n) -> n != null && n.equals(p));
		int loc = p.getID();
		ArrayList<Segment> segments = this.get(loc);
		Segment s = getNotInList(p, segments, endpoints);
		PointND other = s.getOtherPoint(p);

		loc = other.getID();
		segments = new ArrayList<Segment>(this.get(loc));
		Segment otherSeg = getNotInList(other, segments, endpoints);
		// TODO maybe we want the first in the list of endpoints instead for the other
		System.out.println("point " + p.getID() + " Segment 1: " + s + "Segment 2:" + otherSeg);
		if (singletons.contains(other) && !s.equals(otherSeg)) {
			System.out.println(other + " reeee " + singletons);
			segments.remove(otherSeg);
			otherSeg = getNotInList(other, segments, endpoints);
		}

		ArrayList<Segment> segs = new ArrayList<Segment>();
		segs.add(s);
		segs.add(otherSeg);
		if (s.equals(otherSeg)) {
			return new Pair<PointND, ArrayList<Segment>>(s.getOtherPoint(p), segs);
		}
		return new Pair<PointND, ArrayList<Segment>>(null, segs);
	}

	public Segment getFirstBest(Shell shell) {
		PointND first = shell.getFirst();
		return this.get(first.getID()).get(0);
	}

	public Segment getLastBest(Shell shell) {
		PointND last = shell.getLast();
		if (shell.getLength() == 1) {
			return this.get(last.getID()).get(1);
		} else {
			return this.get(last.getID()).get(0);
		}
	}

	public ArrayList<Segment> checkPopMatches(Segment s) {
		int first = s.first.getID();
		int last = s.last.getID();
		Segment firstProspectiveMatch = this.get(first).get(1);
		Segment lastProspectiveMatch = this.get(last).get(1);

		ArrayList<Segment> matches = new ArrayList<Segment>();
		if (this.get(firstProspectiveMatch.getOther(first)).get(0).equals(firstProspectiveMatch)) {
			matches.add(firstProspectiveMatch);
		}
		if (this.get(lastProspectiveMatch.getOther(last)).get(0).equals(lastProspectiveMatch)) {
			matches.add(lastProspectiveMatch);
		}
		return matches;
	}

	public HashSet<Integer> getLeftovers(HashSet<PointND> collectPoints) {
		HashSet<Integer> set = new HashSet<Integer>(this.keySet());
		for (PointND n : collectPoints) {
			set.remove(n.getID());
		}
		return set;
	}

	public boolean checkMatch(PointND endpoint1, Segment sree) {
		PointND other = sree.getOtherPoint(endpoint1);
		SubBucket sb = this.get(other.getID());
		if (sb.shell.size() == 1) {
			return sb.get(0).equals(sree) || sb.get(1).equals(sree);
		}

		return this.get(other.getID()).get(0).equals(sree);

	}

	public boolean checkMatch(PointND endpoint1, Segment sree, ArrayList<PointND> excludeList) {
		PointND other = sree.getOtherPoint(endpoint1);
		excludeList.remove(endpoint1);
		SubBucket sb = this.get(other.getID());
		if (sb.shell.size() == 1) {
			ArrayList<Segment> bucket = getNBucketExcludeList(other, sb, excludeList, 2);
			System.out.println("Endpoint: " + endpoint1 + "Bucket : " + bucket);
			return bucket.get(0).equals(sree) || bucket.get(1).equals(sree);
		}
		Segment list = getNotInList(other, this.get(other.getID()), excludeList);
		System.out.println("Segment to Check: " + list);
		return list.equals(sree);

	}

	public boolean checkMatch(Shell s, ArrayList<PointND> targets) {
		PointND p1 = s.getFirst();
		PointND p2 = s.getOppositeOutside(p1);
		SubBucket sb = this.get(p1.getID());
		boolean match = false;
		if (sb.shell.size() == 1) {
			for (PointND target : targets) {
				if (sb.get(0).contains(target) || sb.get(1).contains(target)) {
					return true;
				}
			}
		}
		for (PointND target : targets) {
			if (this.get(p1.getID()).get(0).contains(target)) {
				return true;
			}
			if (this.get(p2.getID()).get(0).contains(target)) {
				return true;
			}
		}
		return false;

	}

	public int deepsize() {
		int sum = 0;
		for (int i = 0; i < this.size(); i++) {
			if (this.get(i) != null) {
				sum += this.get(i).size();
			}
		}
		return sum;
	}

	public Segment getFirstInList(PointND first, ArrayList<PointND> list) {
		ArrayList<Segment> row = this.get(first.getID());
		for (Segment s : row) {
			if (list.contains(s.getOtherPoint(first))) {
				return s;
			}
		}
		return null;
	}

	public boolean isTwoKnot(Segment s, ArrayList<Segment> matchlist) {
		int first = s.first.getID();
		int last = s.last.getID();
		Segment firstProspectiveMatch = this.get(first).getOtherSegment(s);
		int firstMatchID = firstProspectiveMatch.getOther(first);
		Segment lastProspectiveMatch = this.get(last).getOtherSegment(s);
		int lastMatchID = lastProspectiveMatch.getOther(last);
		SubBucket firstList = this.get(firstMatchID);
		SubBucket lastList = this.get(lastMatchID);
		if (firstList.shell.equals(lastList.shell)) {
			System.out.println(
					"\n-----------------------2Knot Found -------------------------------------------");
			System.out.println("Knot: " + s);
			System.out.println("Knot Found: " + s + " common point: " + firstProspectiveMatch.getOther(first)
					+ " first match " + firstProspectiveMatch + " last match " + lastProspectiveMatch);
			System.out.println(
					"\n---------------------------------------------------------------------------");
			return true;
		}
		return false;
	}

	@Override
	public String toString() {
		String str = "";
		for (SubBucket bucket : this.values()) {
			str += bucket.p.getID() + " " + (bucket.shell.size() > 1 ? "ep" : " s ") + " " + bucket.toString() + "~";
		}
		return str;
	}

	public ArrayList<Segment> permute(ArrayList<Shell> shells) {
		;
		ArrayList<Segment> mergeSegments = new ArrayList<Segment>();

		if (shells.size() == 2) {
			Shell shell1 = shells.get(0);
			PointND p11 = shell1.getFirst();
			PointND p12 = shell1.getLast();

			Shell shell2 = shells.get(1);
			PointND p21 = shell2.getFirst();
			PointND p22 = shell2.getLast();

			double length1 = p11.distance(p21) + p22.distance(p12);
			double length2 = p11.distance(p22) + p21.distance(p12);

			if (length1 < length2) {
				System.out.println("perm1");
				mergeSegments.add(new Segment(p11, p21));
				return mergeSegments;
			} else {
				System.out.println("perm2");
				mergeSegments.add(new Segment(p11, p22));
				return mergeSegments;
			}
		} else if (shells.size() == 3) {
			Shell shell1 = shells.get(0);
			PointND p11 = shell1.getFirst();
			PointND p12 = shell1.getLast();

			Shell shell2 = shells.get(1);
			PointND p21 = shell2.getFirst();
			PointND p22 = shell2.getLast();

			Shell shell3 = shells.get(2);
			PointND p31 = shell3.getFirst();
			PointND p32 = shell3.getLast();
			double length1 = p11.distance(p21) + p22.distance(p31) + p32.distance(p12);
			int perm = 1;
			double minlength = length1;
			double length2 = p11.distance(p22) + p21.distance(p31) + p32.distance(p12);
			if (length2 < minlength) {
				perm = 2;
				minlength = length2;
			}
			double length3 = p11.distance(p22) + p21.distance(p32) + p31.distance(p12);
			if (length3 < minlength) {
				perm = 3;
				minlength = length3;
			}
			double length4 = p12.distance(p21) + p22.distance(p31) + p32.distance(p11);
			if (length4 < minlength) {
				perm = 4;
				minlength = length4;
			}
			double length5 = p12.distance(p22) + p21.distance(p31) + p32.distance(p11);
			if (length5 < minlength) {
				perm = 5;
				minlength = length5;
			}
			double length6 = p12.distance(p22) + p21.distance(p32) + p31.distance(p11);
			if (length6 < minlength) {
				perm = 6;
				minlength = length6;
			}

			if (perm == 1) {
				System.out.println("perm1");
				mergeSegments.add(new Segment(p11, p21));
				mergeSegments.add(new Segment(p22, p31));
				return mergeSegments;
			}
			if (perm == 2) {
				System.out.println("perm2");
				mergeSegments.add(new Segment(p11, p22));
				mergeSegments.add(new Segment(p21, p31));
				return mergeSegments;
			}
			if (perm == 3) {
				System.out.println("perm3");
				mergeSegments.add(new Segment(p11, p21));
				mergeSegments.add(new Segment(p22, p32));
				return mergeSegments;
			}
			if (perm == 4) {
				System.out.println("perm4");
				mergeSegments.add(new Segment(p12, p21));
				mergeSegments.add(new Segment(p22, p31));
				return mergeSegments;
			}
			if (perm == 5) {
				System.out.println("perm5");
				mergeSegments.add(new Segment(p12, p22));
				mergeSegments.add(new Segment(p21, p31));
				return mergeSegments;
			}
			if (perm == 6) {
				System.out.println("perm6");
				mergeSegments.add(new Segment(p12, p21));
				mergeSegments.add(new Segment(p22, p32));
				return mergeSegments;
			}

		}

		return mergeSegments;
	}

	public ArrayList<Group> checkPointsToKnotFilter(ArrayList<Group> group1, ArrayList<PointND> endpoints) {
		ArrayList<Group> filtered = new ArrayList<>();
		System.out.println("reeee" + group1);
		for (Group g : group1) {
			if (g.s2 != null) {
				if (this.checkMatch(g.s1, endpoints)) {
					filtered.add(g);
				} else if (this.checkMatch(g.s2, endpoints)) {
					filtered.add(g);
				}
			}
		}

		System.out.println("filter: " + filtered);
		return filtered;
	}

}

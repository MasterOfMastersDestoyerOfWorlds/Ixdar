package shell;

import java.util.ArrayList;
import java.util.HashMap;

public class Bucket extends HashMap<Integer, ArrayList<Segment>> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	
	public void add(Segment s) {
		int firstId = s.first.getID();
		int lastId =s.last.getID();
		ArrayList<Segment> firstList = this.getOrDefault(firstId, new ArrayList<Segment>());
		ArrayList<Segment> lastList = this.getOrDefault(lastId, new ArrayList<Segment>());
		if(!firstList.contains(s)) {
			firstList.add(s);
		}
		if(!lastList.contains(s)) {
			lastList.add(s);
		}
		this.put(firstId, firstList);
		this.put(lastId, lastList);
	}
	
	public void remove(Segment s) {
		int firstId = s.first.getID();
		int lastId =s.last.getID();
		this.get(firstId).remove(s);
		this.get(lastId).remove(s);
	}
	
	public ArrayList<Segment> getMatches() {
		ArrayList<Segment> matches  = new ArrayList<Segment>();
		for(Integer key: this.keySet()) {
			Segment s = this.get(key).get(0);
			if(!matches.contains(s)) {
				Integer other = s.getOther(key);
				if(this.get(other).get(0).equals(s)) {
					matches.add(s);
				}
			}
		}
		return matches;
	}
	
	public void removeAll(Segment s) {
		int firstId = s.first.getID();
		int lastId =s.last.getID();
		this.remove(firstId);
		this.remove(lastId);
		for(Integer key: this.keySet()) {
			this.get(key).removeIf(n -> (n.contains(s.first) || n.contains(s.last)));
		}
		
	}
	
	private Segment getNotInList(PointND p, ArrayList<Segment> segments, ArrayList<PointND> list) {
		Segment other;
		for(int i = 0; i < segments.size(); i++) {
			other = segments.get(i);
			if(!list.contains(other.getOtherPoint(p))) {
				return other;
			}
		}
		return null;
	}

	public boolean checkMatchExcludeEndpoints(PointND p, ArrayList<PointND> endpoints) {
		int loc = p.getID();
		ArrayList<Segment> segments = this.get(loc);
		Segment s = getNotInList(p, segments, endpoints);
		PointND other = s.getOtherPoint(p);
		loc = other.getID();
		segments = this.get(loc);
		Segment otherSeg = getNotInList(other, segments, endpoints);
		if(s.equals(otherSeg)) {
			return true;
		}
		return false;
	}

}

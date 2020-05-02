package shellCopy;

import java.awt.geom.Point2D;

public class Segment {
	Point2D first, last;
	public Segment(Point2D first, Point2D last) {
		this.first = first;
		this.last = last;
	}
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof Segment) {

			Segment other = (Segment) o;
			return (other.first.getX() == first.getX() && other.last.getX() == last.getX() &&
					other.first.getY() == first.getY() && other.last.getY() == last.getY()) 
					
					||(other.first.getX() == last.getX() && other.last.getX() == first.getX() &&
					   other.first.getY() == last.getY() && other.last.getY() == first.getY()) ;
		}
		return false;

	}
	@Override
	public String toString() {
		return "[ " + first + " : " + last +" ]\n";
	}
	
	@Override
	public int hashCode() {
	    return first.hashCode() + last.hashCode();
	}
	public boolean isOpposite(Segment s) {
		if(this.equals(s) && (s.first.getX() == last.getX() && s.last.getX() == first.getX() &&
				   s.first.getY() == last.getY() && s.last.getY() == first.getY())){
			return true;
		}
		return false;
	}
}

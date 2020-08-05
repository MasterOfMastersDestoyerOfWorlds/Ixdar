package shell;

/**
 * This class represents a Segment between two points in space Stores the start
 * and endpoints of the segment
 */
public class Segment {

	PointND first, last;

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
			return (other.first.equals(first) && other.last.equals(last))
					|| (other.first.equals(last) && other.last.equals(first));
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
	@Override
	public int hashCode() {
		return first.hashCode() + last.hashCode();
	}

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
}

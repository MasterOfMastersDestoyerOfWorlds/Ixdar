package shell;

public class PointWrapper implements Comparable{
	Double angle;
	PointND p;
	boolean left;

	public PointWrapper(Double angle, PointND p, boolean left) {
		this.angle = angle;
		this.p = p;
		this.left = left;
	}


	@Override
	public int compareTo(Object o) {
		return this.angle.compareTo(((PointWrapper)o).angle);
	}
}

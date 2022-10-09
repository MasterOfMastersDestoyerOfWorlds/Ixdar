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
		Double cangle=((PointWrapper)o).angle;
		if(this.angle == cangle) {
			return 0;
		}
        /* For Ascending order*/
        return (int) (this.angle<cangle ? -1 : 1);
	}
}

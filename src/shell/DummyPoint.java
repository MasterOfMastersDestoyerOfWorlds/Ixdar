package shell;

public class DummyPoint extends PointND.Double{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	PointND start, end;
	double maxDist;
	
	public DummyPoint(PointND start, PointND end, double maxDist) {
		this.start = start;
		this.end = end;
		this.maxDist = maxDist;
	}
	
	/**
	 * Returns the square of the distance from this {@code PointND} to a specified
	 * point.
	 *
	 * @param p the coordinates of the specified point to be measured against this
	 *           {@code PointND}
	 * @return the square of the distance between this {@code PointND} and the
	 *         specified point.
	 * 
	 */
	@Override
	public double distanceSq(double... p) {
		return distanceSq(new PointND.Double(-1, p));
	}

	/**
	 * Returns the square of the distance from this {@code PointND} to a specified
	 * {@code PointND}.
	 *
	 * @param pt the specified point to be measured against this {@code PointND}
	 * @return the square of the distance between this {@code PointND} to a
	 *         specified {@code PointND}.
	 * 
	 */
	@Override
	public double distanceSq(PointND pt) {
		if(pt.equals(end) || pt.equals(start) || pt.equals(this)) {
			return 0;
		}
		return maxDist*maxDist;
	}

	/**
	 * Returns the distance from this {@code PointND} to a specified point.
	 *
	 * @param p the coordinates of the specified point to be measured against this
	 *           {@code PointND}
	 * @return the distance between this {@code PointND} and a specified point.
	 * 
	 */
	@Override
	public double distance(double... p) {

		return this.distance(new PointND.Double(-1, p));
	}

	/**
	 * Returns the distance from this {@code PointND} to a specified
	 * {@code PointND}.
	 *
	 * @param pt the specified point to be measured against this {@code PointND}
	 * @return the distance between this {@code PointND} and the specified
	 *         {@code PointND}.
	 * 
	 */
	@Override
	public double distance(PointND pt) {
		if(pt.equals(end) || pt.equals(start) || pt.equals(this)) {
			return 0;
		}
		return maxDist;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof DummyPoint) {
			DummyPoint pt = (DummyPoint) obj;

			if(pt.start.equals(start) && pt.end.equals(end)) {
				return true;
			}
		}
		return false;
	}
	
	@Override
	public String toString(){
		return "DummyPoint[\nstart: " + start + "\nend: " + end + "";
	}
	
	@Override
	public int getID() {
		return -2;
	}

}

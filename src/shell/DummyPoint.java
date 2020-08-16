package shell;

public class DummyPoint extends PointND.Double{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	PointND start, end;
	
	public DummyPoint(PointND start, PointND end) {
		this.start = start;
		this.end = end;
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
		if(pt.equals(end) || pt.equals(start)) {
			return 0;
		}
		return 100;
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

		return distance(new PointND.Double(-1, p));
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
		if(pt.equals(end) || pt.equals(start)) {
			return 0;
		}
		return 10;
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

}

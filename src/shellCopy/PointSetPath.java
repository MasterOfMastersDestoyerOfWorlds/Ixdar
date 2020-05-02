package shellCopy;

import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;

public class PointSetPath {
	PointSet ps;
	Path2D path;
	
	public PointSetPath(PointSet ps, Path2D path) {
		this.path = path;
		this.ps = ps;
	}
	
	public PointSet getPs() {
		return ps;
	}
	public void setPs(PointSet ps) {
		this.ps = ps;
	}
	public Path2D getPath() {
		return path;
	}
	public void setPath(GeneralPath path) {
		this.path = path;
	}

}

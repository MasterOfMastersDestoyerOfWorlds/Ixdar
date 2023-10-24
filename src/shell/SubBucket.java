package shell;
import java.util.ArrayList;

/**
 * A class that represents the distances between all points in the pointset
 */
public class SubBucket extends ArrayList<Segment> {
	public Shell shell;
	public Bucket bucket;
	public PointND p;
	public  SubBucket(Shell shell, Bucket bucket, PointND p){
		this.shell = shell;
		this.bucket = bucket;
		this.p = p;
	}

	public Segment getOtherSegment(Segment s){
		if(shell.size() == 1){
			if(this.get(0).equals(s)){
				return this.get(1);
			}else{
				return this.get(0);
			}
		}else{
			return bucket.get(shell.getOppositeOutside(p).getID()).get(0);
		}
	}
	

}

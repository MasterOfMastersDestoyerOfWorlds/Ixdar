package shell;

import org.junit.jupiter.api.*;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class Tests {

    /**
     * Tests that our solver solves the djibouti problem set correctly
     */
    @Test
    public void testDjibouti(){
        PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));

        Shell orgShell = retTup.ps.toShells();

        Shell pathShell = orgShell.collapseAllShells();

        assertTrue(pathShell.equals(retTup.tsp));
    }
    @Test
    public void testTriangulation(){
        PointSet ps = new PointSet();
        PointND start = new PointND.Double(0,-1), end = new PointND.Double(0,1);
        
        ps.add(start);
        
        ps.add(end);

        ps.add(new PointND.Double(0,0));
        

        
        DistanceMatrix m = new DistanceMatrix(ps);
        
         m = m.addDummyNode(start, end);
        
        double[][] distances = m.getMatrix();
        
        PointSet triangulated = m.toPointSet();
        
        DistanceMatrix triangulatedM = new DistanceMatrix(triangulated);
        
        double[][] triDistances = triangulatedM.getMatrix();
        assertTrue(triDistances.length == distances.length);
        
        for(int i = 0; i < distances.length; i ++) {
        	for(int j = 0; j < distances.length; j++) {
        		if(i != j) {
        			assertTrue((distances[i][j] + m.getMaxDist()*2) == Math.round(triDistances[i][j]));
        		}
        		else {
        			assertTrue(distances[i][j] == triDistances[i][j]);
        		}
        	}
        }
        
    }
    
    @Test
    public void testOptimizationBetweenEndpoints(){
    	Segment s = new Segment(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(12058.3333, 42195.5556));
    	Shell AB = new Shell();
    	AB.add(new PointND.Double(11715.8333, 41836.1111));
    	AB.add(new PointND.Double(11511.3889, 42106.3889));
    	Shell nothing = new Shell();
    	Shell result = Shell.solveBetweenEndpointsNew(s, AB, nothing);
    	Shell answer = new Shell();
    	answer.add(new PointND.Double(11438.3333, 42057.2222));
    	answer.add(new PointND.Double(11511.3889, 42106.3889));
    	answer.add(new PointND.Double(11715.8333, 41836.1111));
    	answer.add(new PointND.Double(12058.3333, 42195.5556));
    	System.out.println(result);
    	System.out.println(answer);
    	assertTrue(result.toString().contentEquals(answer.toString()));
        
    }


}

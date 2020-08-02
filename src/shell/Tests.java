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
        System.out.println("start :"  + m);
        
        double[][] distances = m.getMatrix();
        
        PointSet triangulated = m.toPointSet();
        
        DistanceMatrix triangulatedM = new DistanceMatrix(triangulated);
        
        double[][] triDistances = triangulatedM.getMatrix();
        
        System.out.println("end: " + triangulatedM);
        assertTrue(triDistances.length == distances.length);
        System.out.println(m.getMaxDist());
        
        for(int i = 0; i < distances.length; i ++) {
        	for(int j = 0; j < distances.length; i++) {
        		if(i != j) {
        			System.out.println( (distances[i][j] + m.getMaxDist()*2) + " " + triDistances[i][j]);
        			assertTrue((distances[i][j] + m.getMaxDist()*2) == Math.floor(triDistances[i][j]));
        		}
        		else {
        			assertTrue(distances[i][j] == triDistances[i][j]);
        		}
        	}
        }
        
    }


}

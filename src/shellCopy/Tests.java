package shellCopy;

import org.junit.jupiter.api.*;

import java.awt.geom.Point2D;
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
        PointSetPath retTup = Main.importFromFile(new File("./src/shellCopy/djbouti"));

        Shell orgShell = retTup.ps.toShells();

        Shell pathShell = orgShell.collapseAllShells();

        assertTrue(pathShell.equals(retTup.tsp));
    }

    /**
     * Tests that our solver solves the djibouti problem set correctly
     */
    @Test
    public void testDjiboutiAlec(){
        PointSetPath retTup = Main.importFromFile(new File("./src/shellCopy/djbouti"));

        Shell orgShell = retTup.ps.toShells();

        Shell alec = orgShell.collapseAllShellsAlec();
        Shell alec2 = orgShell.collapseAllShellsAlec2();
        Shell out = orgShell.collapseAllShellsOutRec();
        Shell out2 = orgShell.collapseAllShellsOutRec2();

        System.out.println("Alec Distance: " + getPathDist(alec));
        System.out.println("Alec2 Distance: " + getPathDist(alec2));
        System.out.println("Out Distance: " + getPathDist(out));
        System.out.println("Out2 Distance: " + getPathDist(out2));
        System.out.println("Tsp Distance: " + getPathDist(retTup.tsp));

        assertTrue(alec2.equals(retTup.tsp));
    }

    private static double getPathDist(Shell shell){
        double totalDist = 0;
        for(int i  = 0; i < shell.size(); i++){
            if(i == shell.size()-1){
                totalDist += shell.get(i).distance(shell.get(0));
            }else{
                totalDist += shell.get(i).distance(shell.get(i+1));
            }
        }
        return totalDist;
    }


}

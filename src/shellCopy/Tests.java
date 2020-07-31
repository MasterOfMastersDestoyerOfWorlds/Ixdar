package shellCopy;

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
        PointSetPath retTup = Main.importFromFile(new File("./src/shellCopy/djbouti"));

        Shell orgShell = retTup.ps.toShells();

        Shell pathShell = orgShell.collapseAllShells();

        assertTrue(pathShell.equals(retTup.tsp));
    }


}

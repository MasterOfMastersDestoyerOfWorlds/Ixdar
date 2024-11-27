package unit.subgraphs;

import org.junit.jupiter.api.Test;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class BoxTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_box_WH() {
		SubGraphs.testMethod("box_WH");
	}

	@Test
	public void test_box_WH_2x() {
		SubGraphs.testMethod("box_WH-2x");
	}
}

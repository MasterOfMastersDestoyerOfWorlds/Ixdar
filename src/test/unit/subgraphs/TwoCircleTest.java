package unit.subgraphs;

import org.junit.jupiter.api.Test;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class TwoCircleTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_twocircle_in_10() {
		SubGraphs.testMethod("twocircle_in_10");
	}

	@Test
	public void test_twocircle_in_10_arc() {
		SubGraphs.testMethod("twocircle_in_10_arc");

	}

	@Test
	public void test_twocircle_in_10_wh() {
		SubGraphs.testMethod("twocircle_in_10_wh");
	}

}

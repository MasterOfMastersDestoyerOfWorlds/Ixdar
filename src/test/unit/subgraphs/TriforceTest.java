package unit.subgraphs;

import org.junit.jupiter.api.Test;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class TriforceTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_triforce_flat() {
		SubGraphs.testMethod("triforce_flat");
	}

	@Test
	public void test_triforce_line() {
		SubGraphs.testMethod("triforce_line");

	}

	@Test
	public void test_triforce() {
		SubGraphs.testMethod("triforce");
	}

}

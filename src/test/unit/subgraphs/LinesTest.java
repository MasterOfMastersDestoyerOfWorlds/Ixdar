package unit.subgraphs;

import org.junit.jupiter.api.Test;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class LinesTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	@Test
	public void test_lines() {
		SubGraphs.testMethod("lines");
	}

	@Test
	public void test_lines_0_4() {
		SubGraphs.testMethod("lines_0-4");
	}

}

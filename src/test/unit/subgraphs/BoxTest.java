package unit.subgraphs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */

@Execution(ExecutionMode.CONCURRENT)
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

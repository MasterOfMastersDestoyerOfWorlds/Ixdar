package unit.subgraphs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;


/**
 * Tests to verify that our tsp solver works as expected
 */
@Execution(ExecutionMode.CONCURRENT)
public class CircleTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_circle_5() {
		SubGraphs.testMethod("circle_5");
	}

	@Test
	public void test_circle_in_5() {
		SubGraphs.testMethod("circle_in_5");
	}

	@Test
	public void test_circle_in_5_arc() {
		SubGraphs.testMethod("circle_in_5_arc");
	}

	@Test
	public void test_circle_10() {
		SubGraphs.testMethod("circle_10");
	}

}

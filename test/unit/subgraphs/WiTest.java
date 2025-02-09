package unit.subgraphs;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests to verify that our tsp solver works as expected
 */
@Execution(ExecutionMode.CONCURRENT)
public class WiTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_wi29() {
		SubGraphs.testMethod("wi29");
	}

	@Test
	public void test_wi29_5_25() {
		SubGraphs.testMethod("wi29_5-25");
	}

	@Test
	public void test_wi29_5_25x3() {
		SubGraphs.testMethod("wi29_5-25x3");
	}

	@Test
	public void test_wi29_5_28() {
		SubGraphs.testMethod("wi29_5-28");
	}

	@Test
	public void test_wi29_6_25() {
		SubGraphs.testMethod("wi29_6-25");
	}

	@Test
	public void test_wi29_6_25p20() {
		SubGraphs.testMethod("wi29_6-25p20");
	}

	@Test
	public void test_wi29_6_25p20p19() {
		SubGraphs.testMethod("wi29_6-25p20p19");
	}

	@Test
	public void test_wi29_6_21() {
		SubGraphs.testMethod("wi29_6-21");
	}

	@Test
	public void test_wi29_6_28() {
		SubGraphs.testMethod("wi29_6-28");
	}

	@Test
	public void test_wi29_9_25() {
		SubGraphs.testMethod("wi29_9-25");
	}

	@Test
	public void test_wi29_9_25p20() {
		SubGraphs.testMethod("wi29_9-25p20");
	}

}

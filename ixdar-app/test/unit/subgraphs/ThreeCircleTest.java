package unit.subgraphs;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
@Execution(ExecutionMode.CONCURRENT)
public class ThreeCircleTest {

	@Test
	public void test_threecircle_in_10() {
		SubGraphs.testMethod("threecircle_in_10");
	}

	@Test
	public void test_threecircle_in_10_close() {
		SubGraphs.testMethod("threecircle_in_10_close");
	}

}

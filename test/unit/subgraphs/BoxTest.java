package unit.subgraphs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
@Execution(ExecutionMode.CONCURRENT)
public class BoxTest {

	@Test
	public void test_box_bottleneck_2() {
		SubGraphs.testMethod("box_bottleneck-2");
	}

	@Test
	public void test_box_bottleneck() {
		SubGraphs.testMethod("box_bottleneck");
	}

	@Test
	public void test_box_WH_2x() {
		SubGraphs.testMethod("box_WH-2x");
	}

	@Test
	public void test_box_WH() {
		SubGraphs.testMethod("box_WH");
	}

}

package unit.subgraphs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
@Execution(ExecutionMode.CONCURRENT)
public class CrossTest {

	@Test
	public void test_cross() {
		SubGraphs.testMethod("cross");
	}

	@Test
	public void test_cross_big() {
		SubGraphs.testMethod("cross_big");
	}

}

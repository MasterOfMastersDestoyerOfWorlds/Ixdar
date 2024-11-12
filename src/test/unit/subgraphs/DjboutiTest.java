package unit.subgraphs;

import org.junit.jupiter.api.Test;

import unit.SubGraphs;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class DjboutiTest {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_djibouti() {
		SubGraphs.testMethod("djbouti");
	}

	@Test
	public void test_djibouti_1_34() {
		SubGraphs.testMethod("djbouti_1-34");
	}

	@Test
	public void test_djibouti_2_4() {
		SubGraphs.testMethod("djbouti_2-4");
	}

	@Test
	public void test_djibouti_2_7() {
		SubGraphs.testMethod("djbouti_2-7");
	}

	@Test
	public void test_djibouti_3_32() {
		SubGraphs.testMethod("djbouti_3-32");
	}

	@Test
	public void test_djibouti_4_8() {
		SubGraphs.testMethod("djbouti_4-8");
	}

	@Test
	public void test_djibouti_4_8WH_4_6() {
		SubGraphs.testMethod("djbouti_4-8WH4-6");
	}

	@Test
	public void test_djibouti_7_32() {
		SubGraphs.testMethod("djbouti_7-32");
	}

	@Test
	public void test_djibouti_8_14() {
		SubGraphs.testMethod("djbouti_8-14");
	}

	@Test
	public void test_djibouti_8_20() {
		SubGraphs.testMethod("djbouti_8-20");
	}

	@Test
	public void test_djibouti_8_24() {
		SubGraphs.testMethod("djbouti_8-24");
	}

	@Test
	public void test_djibouti_8_26() {
		SubGraphs.testMethod("djbouti_8-26");
	}

	@Test
	public void test_djibouti_8_32() {
		SubGraphs.testMethod("djbouti_8-32");
	}

	@Test
	public void test_djibouti_8_34() {
		SubGraphs.testMethod("djbouti_8-34");
	}

	@Test
	public void test_djibouti_8_34WH0_33() {
		SubGraphs.testMethod("djbouti_8-34WH0-33");
	}

	@Test
	public void test_djibouti_13_34() {
		SubGraphs.testMethod("djbouti_13-34");
	}

	@Test
	public void test_djibouti_14_31() {
		SubGraphs.testMethod("djbouti_14-31");
	}

	@Test
	public void test_djibouti_18_23() {
		SubGraphs.testMethod("djbouti_18-23");
	}

	@Test
	public void test_djibouti_18_23WH19_22() {
		SubGraphs.testMethod("djbouti_18-23WH19-22");
	}

	@Test
	public void test_djibouti_26_31() {
		SubGraphs.testMethod("djbouti_26-31");
	}

	@Test
	public void test_djibouti_26_32p2_3() {
		SubGraphs.testMethod("djbouti_26-32p2-3");
	}

}

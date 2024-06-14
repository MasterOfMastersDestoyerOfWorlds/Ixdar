package test;

import shell.BalancerException;
import shell.DistanceMatrix;
import shell.Main;
import shell.PointSet;
import shell.PointSetPath;
import shell.SegmentBalanceException;
import shell.Shell;
import java.io.File;
import java.util.Collections;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class SubGraphs {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_djibouti() {
		testMethod("djbouti");
	}

	@Test
	public void test_djibouti_1_34() {
		testMethod("djbouti_1-34");
	}

	@Test
	public void test_djibouti_2_4() {
		testMethod("djbouti_2-4");
	}

	@Test
	public void test_djibouti_2_7() {
		testMethod("djbouti_2-7");
	}

	@Test
	public void test_djibouti_3_32() {
		testMethod("djbouti_3-32");
	}

	@Test
	public void test_djibouti_7_32() {
		testMethod("djbouti_3-32");
	}

	@Test
	public void test_djibouti_8_14() {
		testMethod("djbouti_8-14");
	}

	@Test
	public void test_djibouti_8_20() {
		testMethod("djbouti_8-14");
	}

	@Test
	public void test_djibouti_8_24() {
		testMethod("djbouti_8-24");
	}

	@Test
	public void test_djibouti_8_26() {
		testMethod("djbouti_8-24");
	}

	@Test
	public void test_djibouti_4_8() {
		testMethod("djbouti_4-8");
	}

	@Test
	public void test_djibouti_8_32() {
		testMethod("djbouti_8-32");
	}

	@Test
	public void test_djibouti_8_34() {
		testMethod("djbouti_8-34");
	}

	@Test
	public void test_djibouti_13_34() {
		testMethod("djbouti_13-34");
	}

	@Test
	public void test_djibouti_14_31() {
		testMethod("djbouti_14-31");
	}

	@Test
	public void test_djibouti_18_23() {
		testMethod("djbouti_18-23");
	}

	@Test
	public void test_djibouti_18_23WH19_22() {
		testMethod("djbouti_18-23WH19-22");
	}

	@Test
	public void test_djibouti_8_34WH0_33() {
		testMethod("djbouti_8-34WH0-33");
	}

	@Test
	public void test_djibouti_26_31() {
		testMethod("djbouti_26-31");
	}

	@Test
	public void test_djibouti_26_32p2_3() {
		testMethod("djbouti_26-32p2-3");
	}

	@Test
	public void test_wi29() {
		testMethod("wi29");
	}

	@Test
	public void test_wi29_5_25() {
		testMethod("wi29_5-25");
	}

	@Test
	public void test_wi29_5_25x3() {
		testMethod("wi29_5-25x3");
	}

	@Test
	public void test_wi29_6_25() {
		testMethod("wi29_6-25");
	}

	@Test
	public void test_wi29_6_25p20() {
		testMethod("wi29_6-25p20");
	}

	@Test
	public void test_wi29_6_25p20p19() {
		testMethod("wi29_6-25p20p19");
	}

	@Test
	public void test_wi29_6_28() {
		testMethod("wi29_6-28");
	}

	@Test
	public void test_wi29_9_25() {
		testMethod("wi29_9-25");
	}

	@Test
	public void test_wi29_9_25p20() {
		testMethod("wi29_9-25p20");
	}

	public void testMethod(String fileName) {
		PointSetPath retTup = Main.importFromFile(new File("./src/test/solutions/" + fileName));
		PointSet ps = new PointSet();
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.ps.size(); i++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			AB.add(retTup.ps.get(i));
		}

		System.out.println("before   " + AB);
		Collections.shuffle(AB, new Random(2));
		System.out.println("shuffled " + AB);
		System.out.println("surrounding segment: " + answer.getFirst() + " " + answer.getLast());
		System.out.println(AB.size());
		System.out.println();

		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = null;

		try {
			result = AB.tspSolve(AB, d);

		} catch (SegmentBalanceException sbe) {
			boolean flag = false;
			assert (flag) : "" + sbe.toString();
		}

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		System.out.println("=========================");
		System.out.println("error: " + Math.abs(result.getLength() - answer.getLength()));

		assert (Math.abs(result.getLength() - answer.getLength()) < 0.1 || result.getLength() < answer.getLength())
				: "result: " +
						result + " \n Shell was length: " + result.getLength() + "\n answer: " +
						answer + "\n Supposed to be length: " + answer.getLength();
		System.out.println("reee");
	}

}

package unit;

import shell.DistanceMatrix;
import shell.exceptions.SegmentBalanceException;
import shell.file.FileManagement;
import shell.file.PointSetPath;
import shell.shell.Shell;

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
	public void test_lines() {
		testMethod("lines");
	}
	@Test
	public void test_lines_0_4() {
		testMethod("lines_0-4");
	}
	@Test
	public void test_box_WH() {
		testMethod("box_WH");
	}

	@Test
	public void test_box_WH_2x() {
		testMethod("box_WH-2x");
	}
	@Test
	public void test_circle_5() {
		testMethod("circle_5");
	}

	@Test
	public void test_circle_in_5() {
		testMethod("circle_in_5");
	}

	@Test
	public void test_circle_in_5_arc() {
		testMethod("circle_in_5_arc");
	}

	@Test
	public void test_circle_10() {
		testMethod("circle_10");
	}

	@Test
	public void test_twocircle_in_10() {
		testMethod("twocircle_in_10");
	}

	@Test
	public void test_twocircle_in_10_arc() {
		testMethod("twocircle_in_10_arc");

	}

	@Test
	public void test_twocircle_in_10_wh() {
		testMethod("twocircle_in_10_wh");
	}

	@Test
	public void test_threecircle_in_10() {
		testMethod("threecircle_in_10");
	}

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
	public void test_djibouti_4_8() {
		testMethod("djbouti_4-8");
	}

	@Test
	public void test_djibouti_4_8WH_4_6() {
		testMethod("djbouti_4-8WH4-6");
	}

	@Test
	public void test_djibouti_7_32() {
		testMethod("djbouti_7-32");
	}

	@Test
	public void test_djibouti_8_14() {
		testMethod("djbouti_8-14");
	}

	@Test
	public void test_djibouti_8_20() {
		testMethod("djbouti_8-20");
	}

	@Test
	public void test_djibouti_8_24() {
		testMethod("djbouti_8-24");
	}

	@Test
	public void test_djibouti_8_26() {
		testMethod("djbouti_8-26");
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
	public void test_djibouti_8_34WH0_33() {
		testMethod("djbouti_8-34WH0-33");
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
	public void test_wi29_5_28() {
		testMethod("wi29_5-28");
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
	public void test_wi29_6_21() {
		testMethod("wi29_6-21");
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
	
	@Test
	public void test_qa194_0_14() {
		testMethod("qa194_0-14");
	}
	@Test
	public void test_qa194_0_20() {
		testMethod("qa194_0-20");
	}
	@Test
	public void test_qa194_20_40() {
		testMethod("qa194_20-40");
	}
	@Test
	public void test_qa194_20_40WH() {
		testMethod("qa194_20-40WH");
	}
	@Test
	public void test_qa194_20_60() {
		testMethod("qa194_20-60");
	}
	@Test
	public void test_qa194_20_60WH() {
		testMethod("qa194_20-60WH");
	}
	@Test
	public void test_qa194_40_60() {
		testMethod("qa194_40-60");
	}
	@Test
	public void test_qa194_40_60WH() {
		testMethod("qa194_40-60WH");
	}
	@Test
	public void test_qa194_60_80() {
		testMethod("qa194_60-80");
	}
	@Test
	public void test_qa194_60_80WH() {
		testMethod("qa194_60-80WH");
	}
	@Test
	public void test_qa194_69_100() {
		testMethod("qa194_69-100");
	}	
	@Test
	public void test_qa194_87_100() {
		testMethod("qa194_87-100");
	}
	@Test
	public void test_qa194_100_120() {
		testMethod("qa194_100-120");
	}
	@Test
	public void test_qa194_100_120WH() {
		testMethod("qa194_100-120WH");
	}

	@Test
	public void test_qa194_120_140() {
		testMethod("qa194_120-140");
	}
	@Test
	public void test_qa194_120_140WH() {
		testMethod("qa194_120-140WH");
	}
	@Test
	public void test_qa194_120_160() {
		testMethod("qa194_120-160");
	}
	@Test
	public void test_qa194_120_160WH() {
		testMethod("qa194_120-160WH");
	}
	@Test
	public void test_qa194_140_160() {
		testMethod("qa194_140-160");
	}
	@Test
	public void test_qa194_140_160WH() {
		testMethod("qa194_140-160WH");
	}

	@Test
	public void test_qa194_160_180() {
		testMethod("qa194_160-180");
	}
	@Test
	public void test_qa194_160_180WH() {
		testMethod("qa194_160-180WH");
	}
	
	@Test
	public void test_qa194_180_6() {
		testMethod("qa194_180-6");
	}
	@Test
	public void test_qa194_180_6WH() {
		testMethod("qa194_180-6WH");
	}
	public void testMethod(String fileName) {
		PointSetPath retTup = FileManagement.importFromFile(FileManagement.getTestFile(fileName));
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.tsp.size(); i++) {
			answer.add(retTup.tsp.get(i));
			AB.add(retTup.tsp.get(i));
		}


		System.out.println("before   " + AB);
		Collections.shuffle(AB, new Random(2));
		System.out.println("shuffled " + AB);
		System.out.println("surrounding segment: " + answer.getFirst() + " " + answer.getLast());
		System.out.println(AB.size());
		System.out.println();

		DistanceMatrix d = retTup.d;
		if (retTup.d == null) {
			d = new DistanceMatrix(retTup.ps);
		}
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

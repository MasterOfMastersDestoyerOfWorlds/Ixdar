package test;


import shell.DistanceMatrix;
import shell.Main;
import shell.PointND;
import shell.PointSet;
import shell.PointSetPath;
import shell.Segment;
import shell.Shell;
import shell.Vectors;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import org.junit.Assert;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class Tests {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public boolean testDjiboutiN(int n, int rot) {

		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));
		PointSet ps = new PointSet();
		Shell answer = new Shell();

		System.out.println(n + " " + rot);

		Shell AB = new Shell();
		for (int i = rot; i < n + rot && i < retTup.ps.size(); i++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			if (i != n + rot - 1 && i != rot) {
				AB.add(retTup.ps.get(i));
			}
		}
		if (n + rot > retTup.ps.size()) {
			for (int i = 0; i < (n + rot) - retTup.ps.size(); i++) {
				ps.add(retTup.ps.get(i));
				answer.add(retTup.ps.get(i));
				if (i != (n + rot - 1) - retTup.ps.size()) {
					AB.add(retTup.ps.get(i));
				}
			}
		}

		System.out.println("before   " + AB);
		Collections.shuffle(AB);// , new Random(2));
		System.out.println("shuffled " + AB);
		Segment s = new Segment(answer.getFirst(), answer.getLast());
		System.out.println("surrounding segment: " + s);

		Shell nothing = new Shell();

		DistanceMatrix d = new DistanceMatrix(ps);

		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		System.out.println("=========================");

		assert (Math.abs(result.getLength() - answer.getLength()) < 1) : "result: " +
				result + " \n Shell was length: " + result.getLength() + "\n answer: " +
				answer + "\n Supposed to be length: " + answer.getLength();
		System.out.println("reee");

		return true;
	}

	@TestFactory
	public Collection<DynamicTest> djboutiDynamicTests() {

		Collection<DynamicTest> dynamicTests = new ArrayList<>();
		int n = 38;

		int[] a = new int[n];
		for (int i = 1; i < n; ++i) {
			a[i] = i + 1;
		}

		int[] b = new int[n];
		for (int i = 0; i < n; ++i) {
			b[i] = i;
		}

		for (int i = n - 1; i < n; i++) {

			int num = a[i];

			// create an test execution
			int loc = 9;
			for (int j = 8; j < loc; j++) {

				int rot = b[n - j];
				// create a test display name
				String testName = "Test djbouti size" + (i + 1) + " rot" + j;
				System.out.println(testName);
				// create dynamic test
				DynamicTest dTest = DynamicTest.dynamicTest(testName, () -> Assert.assertEquals(true, testDjiboutiN(n, rot)));
				// add the dynamic test to collection
				dynamicTests.add(dTest);
			}
		}
		System.out.println("green");
		return dynamicTests;
	}

	@TestFactory
	public Iterable<DynamicTest> simple() {
		return Arrays.asList(DynamicTest.dynamicTest("greateer test", () -> Assert.assertTrue(2 > 1)));
	}

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	// old: 10038.75729043869 in 3.828s
	// new: 10178.770192333182 in 20.461s
	// @Test
	public void testQatar() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/qa194"));
		DistanceMatrix d = new DistanceMatrix(retTup.ps);
		Shell orgShell = retTup.ps.toShells(d);

		Shell pathShell = orgShell.collapseAllShells(d);
		System.out.println(pathShell.getLength());
		Assert.assertTrue(pathShell.getLength() < 9400);
	}

	@Test
	public void fuck() {
		Assert.assertTrue("null", true);
	}

}

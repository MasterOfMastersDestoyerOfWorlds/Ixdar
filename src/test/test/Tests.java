package test;


import shell.DistanceMatrix;
import shell.FileManagement;
import shell.PointSet;
import shell.exceptions.BalancerException;
import shell.exceptions.SegmentBalanceException;
import shell.shell.Shell;
import shell.ui.PointSetPath;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

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
	 * @throws SegmentBalanceException 
	 * @throws BalancerException 
	 */

	
	@Test
	public void testDjiboutiNoRotation() throws SegmentBalanceException, BalancerException {

		PointSetPath retTup = FileManagement.importFromFile(new File("./src/shell/djbouti"));
		PointSet ps = new PointSet();
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.ps.size(); i++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			AB.add(retTup.ps.get(i));
		}

		beforePrintsAndShuffle(answer, AB);

		DistanceMatrix d = new DistanceMatrix(ps);
		//d.addDummyNode(answer.getFirst(), answer.getLast());
		Shell result = AB.tspSolve(AB, d);

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		System.out.println("=========================");
		System.out.println("error: "+Math.abs(result.getLength() - answer.getLength()) );

		assert (Math.abs(result.getLength() - answer.getLength()) < 0.1) : "result: " +
				result + " \n Shell was length: " + result.getLength() + "\n answer: " +
				answer + "\n Supposed to be length: " + answer.getLength();
		System.out.println("reee");
	}

	private void beforePrintsAndShuffle(Shell answer, Shell AB) {
		System.out.println("before   " + AB);
		Collections.shuffle(AB , new Random(2));
		System.out.println("shuffled " + AB);
		System.out.println("surrounding segment: " + answer.getFirst() + " " + answer.getLast());
		System.out.println(AB.size());
		System.out.println();
	}

	@Test
	public boolean testDjiboutiN(int n, int rot) throws SegmentBalanceException, BalancerException {

		PointSetPath retTup = FileManagement.importFromFile(new File("./src/shell/djbouti"));
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

		beforePrintsAndShuffle(AB, answer);

		DistanceMatrix d = new DistanceMatrix(ps);
		//d.addDummyNode(answer.getFirst(), answer.getLast());
		Shell result = AB.solveBetweenEndpoints(answer.getFirst(), answer.getLast(), AB, d);

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		System.out.println("=========================");
		System.out.println("error: "+Math.abs(result.getLength() - answer.getLength()) );

		assert (Math.abs(result.getLength() - answer.getLength()) < 0.1) : "result: " +
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

			@SuppressWarnings("unused")
			int num = a[i];
			//7: something wrong with knot detection. I think the thing I had before about the runs turning into knots if both end pointed internally was the correct thing
					//24 and 25 are transposed when added to their knot
			//35: 4 and 5 are swapped, when added to their Knot
			//37: 
			//failing tests: rot: 7
			//
			int limit =  8;
			for (int j = 7; j < limit; j++) {

				int rot = b[n - j];
				String testName = "Test djbouti size" + (i + 1) + " rot" + j;
				System.out.println(testName);
				DynamicTest dTest = DynamicTest.dynamicTest(testName, () -> Assert.assertEquals(true, testDjiboutiN(n, rot)));
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
	 * @throws SegmentBalanceException 
	 * @throws BalancerException 
	 */
	// old: 10038.75729043869 in 3.828s
	// new: 10178.770192333182 in 20.461s
	@Test
	public void testQatar() throws SegmentBalanceException, BalancerException {
		PointSetPath retTup = FileManagement.importFromFile(new File("./src/shell/qa194"));
		PointSet ps = new PointSet();
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.ps.size(); i++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			AB.add(retTup.ps.get(i));
		}

		beforePrintsAndShuffle(answer, AB);

		DistanceMatrix d = new DistanceMatrix(ps);
		//d.addDummyNode(answer.getFirst(), answer.getLast());
		Shell result = AB.tspSolve(AB, d);

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		Assert.assertTrue(result.getLength() < 9400);
	}

	@Test
	public void testWesternSahara() throws SegmentBalanceException, BalancerException {
		PointSetPath retTup = FileManagement.importFromFile(new File("./src/shell/wi29"));
		PointSet ps = new PointSet();
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.ps.size(); i++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			AB.add(retTup.ps.get(i));
		}

		beforePrintsAndShuffle(answer, AB);

		DistanceMatrix d = new DistanceMatrix(ps);
		//d.addDummyNode(answer.getFirst(), answer.getLast());
		Shell result = AB.tspSolve(AB, d);

		System.out.println("result " + result + " " + result.getLength());
		System.out.println("ans " + answer + " " + answer.getLength());
		Assert.assertTrue(result.getLength() < 27603);
	}
}

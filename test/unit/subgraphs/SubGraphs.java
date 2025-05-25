package unit.subgraphs;

import java.util.Collections;
import java.util.Random;

import shell.DistanceMatrix;
import shell.exceptions.SegmentBalanceException;
import shell.exceptions.TerminalParseException;
import shell.file.FileManagement;
import shell.file.PointSetPath;
import shell.shell.Shell;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class SubGraphs {
	public static void testMethod(String fileName) {
		PointSetPath retTup = null;
		try {
			retTup = FileManagement.importFromFile(FileManagement.getTestFile(fileName));
		} catch (TerminalParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
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
        assert(result.size() == answer.size()) : "CardinalityError result: " + result + " had " + result.size() + " points, expected: " + answer + " " + answer.size() + " points "; 
	}

}

package shell;

import org.junit.jupiter.api.*;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class Tests {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	@Test
	public void testDjibouti() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));

		Shell orgShell = retTup.ps.toShells();

		Shell pathShell = orgShell.collapseAllShells();
		assertTrue(pathShell.getLength() == retTup.tsp.getLength());
	}

	@Test
	public void testTriangulation() {
		PointSet ps = new PointSet();
		PointND start = new PointND.Double(0, -1), end = new PointND.Double(0, 1);

		ps.add(start);

		ps.add(end);

		ps.add(new PointND.Double(0, 0));

		DistanceMatrix m = new DistanceMatrix(ps);

		m = m.addDummyNode(start, end);

		double[][] distances = m.getMatrix();

		PointSet triangulated = m.toPointSet();

		DistanceMatrix triangulatedM = new DistanceMatrix(triangulated);

		double[][] triDistances = triangulatedM.getMatrix();
		assertTrue(triDistances.length == distances.length);

		for (int i = 0; i < distances.length; i++) {
			for (int j = 0; j < distances.length; j++) {
				if (i != j) {
					assertTrue((distances[i][j] + m.getMaxDist() * 2) == Math.round(triDistances[i][j]));
				} else {
					assertTrue(distances[i][j] == triDistances[i][j]);
				}
			}
		}

	}

	@Test
	public void testOptimizationBetweenEndpoints1() {
		Segment s = new Segment(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(12058.3333, 42195.5556));
		Shell AB = new Shell();
		AB.add(new PointND.Double(11715.8333, 41836.1111));
		AB.add(new PointND.Double(11511.3889, 42106.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell();
		answer.add(new PointND.Double(11438.3333, 42057.2222));
		answer.add(new PointND.Double(11511.3889, 42106.3889));
		answer.add(new PointND.Double(11715.8333, 41836.1111));
		answer.add(new PointND.Double(12058.3333, 42195.5556));
		assertTrue(result.getLength() <= answer.getLength());

	}

	@Test
	public void testOptimizationBetweenEndpoints2() {
		Segment s = new Segment(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12372.7778, 42711.3889));
		Shell AB = new Shell();
		AB.add(new PointND.Double(12300.0, 42433.3333));
		AB.add(new PointND.Double(12149.4444, 42477.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell();
		answer.add(new PointND.Double(12058.3333, 42195.5556));
		answer.add(new PointND.Double(12149.4444, 42477.5));
		answer.add(new PointND.Double(12300.0, 42433.3333));
		answer.add(new PointND.Double(12372.7778, 42711.3889));
		assertTrue(result.getLength() <= answer.getLength());

	}

	@Test
	public void testOptimizationBetweenEndpoints3() {
		Segment s = new Segment(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12363.3333, 43189.1667));
		Shell AB = new Shell();
		AB.add(new PointND.Double(12645.0, 42973.3333));
		AB.add(new PointND.Double(12355.8333, 43156.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell();
		answer.add(new PointND.Double(12421.6667, 42895.5556));
		answer.add(new PointND.Double(12645.0, 42973.3333));
		answer.add(new PointND.Double(12355.8333, 43156.3889));
		answer.add(new PointND.Double(12363.3333, 43189.1667));
		assertTrue(result.getLength() <= answer.getLength());

	}

	@Test
	public void testOptimizationBetweenEndpoints4() {
		Segment s = new Segment(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(11963.0556, 43290.5556));
		Shell AB = new Shell(new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints5() {
		Segment s = new Segment(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11485.5556, 43187.2222));
		Shell AB = new Shell(new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11485.5556, 43187.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints6() {
		Segment s = new Segment(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11183.3333, 42933.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11183.3333, 42933.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints7() {
		Segment s = new Segment(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11108.6111, 42373.8889));
		Shell AB = new Shell(new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints8() {
		Segment s = new Segment(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11438.3333, 42057.2222));
		Shell AB = new Shell(new PointND.Double(11003.6111, 42102.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints9() {
		Segment s = new Segment(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11846.9444, 42660.5556));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11846.9444, 42660.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints10() {
		Segment s = new Segment(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11770.2778, 42651.9444));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11770.2778, 42651.9444));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints11() {
		Segment s = new Segment(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11569.4444, 43136.6667));
		Shell AB = new Shell(new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints12() {
		System.out.println("Test 12:");
		Segment s = new Segment(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11423.8889, 43000.2778));
		Shell AB = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778));
		Shell nothing = new Shell();
		Shell answer = new Shell(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778));
		

		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		
		System.out.println("result length: " + result.getLength());
		System.out.println("answer length: " + answer.getLength());

		System.out.println(Shell.compareTo(answer, result));

		assertTrue(result.getLength() <= answer.getLength());

	}

	@Test
	public void testOptimizationBetweenEndpoints13() {
		Segment s = new Segment(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11416.6667, 42983.3333));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints14() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11770.2778, 42651.9444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11973.0556, 43026.1111));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints15() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778),
				new PointND.Double(11416.6667, 42983.3333), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333), new PointND.Double(11183.3333, 42933.3333),
				new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5),
				new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222), new PointND.Double(11511.3889, 42106.3889),
				new PointND.Double(11715.8333, 41836.1111), new PointND.Double(12058.3333, 42195.5556),
				new PointND.Double(12149.4444, 42477.5), new PointND.Double(12300.0, 42433.3333),
				new PointND.Double(12372.7778, 42711.3889), new PointND.Double(12421.6667, 42895.5556),
				new PointND.Double(12645.0, 42973.3333), new PointND.Double(12355.8333, 43156.3889),
				new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11973.0556, 43026.1111));
		
		System.out.println("Test 15: ");
		System.out.println("result length: " + result.getLength());
		System.out.println("answer length: " + answer.getLength());

		System.out.println(Shell.compareTo(answer, result));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints16() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11503.0556, 42855.2778),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints17() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11503.0556, 42855.2778), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints18() {
		Segment s = new Segment(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(12058.3333, 42195.5556));
		Shell AB = new Shell(new PointND.Double(11715.8333, 41836.1111), new PointND.Double(11511.3889, 42106.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(11511.3889, 42106.3889),
				new PointND.Double(11715.8333, 41836.1111), new PointND.Double(12058.3333, 42195.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints19() {
		Segment s = new Segment(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12372.7778, 42711.3889));
		Shell AB = new Shell(new PointND.Double(12300.0, 42433.3333), new PointND.Double(12149.4444, 42477.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints20() {
		Segment s = new Segment(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12363.3333, 43189.1667));
		Shell AB = new Shell(new PointND.Double(12645.0, 42973.3333), new PointND.Double(12355.8333, 43156.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints21() {
		Segment s = new Segment(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(11963.0556, 43290.5556));
		Shell AB = new Shell(new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints22() {
		Segment s = new Segment(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11485.5556, 43187.2222));
		Shell AB = new Shell(new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11485.5556, 43187.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints23() {
		Segment s = new Segment(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11183.3333, 42933.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11183.3333, 42933.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints24() {
		Segment s = new Segment(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11108.6111, 42373.8889));
		Shell AB = new Shell(new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints25() {
		Segment s = new Segment(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11438.3333, 42057.2222));
		Shell AB = new Shell(new PointND.Double(11003.6111, 42102.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints26() {
		Segment s = new Segment(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11846.9444, 42660.5556));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11846.9444, 42660.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints27() {
		Segment s = new Segment(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11770.2778, 42651.9444));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11770.2778, 42651.9444));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints28() {
		Segment s = new Segment(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11569.4444, 43136.6667));
		Shell AB = new Shell(new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints29() {
		Segment s = new Segment(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11423.8889, 43000.2778));
		Shell AB = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints30() {
		Segment s = new Segment(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11416.6667, 42983.3333));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints31() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778),
				new PointND.Double(11416.6667, 42983.3333), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333), new PointND.Double(11183.3333, 42933.3333),
				new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5),
				new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222), new PointND.Double(11511.3889, 42106.3889),
				new PointND.Double(11715.8333, 41836.1111), new PointND.Double(12058.3333, 42195.5556),
				new PointND.Double(12149.4444, 42477.5), new PointND.Double(12300.0, 42433.3333),
				new PointND.Double(12372.7778, 42711.3889), new PointND.Double(12421.6667, 42895.5556),
				new PointND.Double(12645.0, 42973.3333), new PointND.Double(12355.8333, 43156.3889),
				new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11973.0556, 43026.1111));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints32() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11770.2778, 42651.9444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11973.0556, 43026.1111));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints33() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11503.0556, 42855.2778), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints34() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11503.0556, 42855.2778),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints35() {
		Segment s = new Segment(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(12058.3333, 42195.5556));
		Shell AB = new Shell(new PointND.Double(11715.8333, 41836.1111), new PointND.Double(11511.3889, 42106.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11438.3333, 42057.2222), new PointND.Double(11511.3889, 42106.3889),
				new PointND.Double(11715.8333, 41836.1111), new PointND.Double(12058.3333, 42195.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints36() {
		Segment s = new Segment(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12372.7778, 42711.3889));
		Shell AB = new Shell(new PointND.Double(12300.0, 42433.3333), new PointND.Double(12149.4444, 42477.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints37() {
		Segment s = new Segment(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12363.3333, 43189.1667));
		Shell AB = new Shell(new PointND.Double(12645.0, 42973.3333), new PointND.Double(12355.8333, 43156.3889));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints38() {
		Segment s = new Segment(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(11963.0556, 43290.5556));
		Shell AB = new Shell(new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints39() {
		Segment s = new Segment(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11485.5556, 43187.2222));
		Shell AB = new Shell(new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11485.5556, 43187.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints40() {
		Segment s = new Segment(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11183.3333, 42933.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11183.3333, 42933.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints41() {
		Segment s = new Segment(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11108.6111, 42373.8889));
		Shell AB = new Shell(new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints42() {
		Segment s = new Segment(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11438.3333, 42057.2222));
		Shell AB = new Shell(new PointND.Double(11003.6111, 42102.5));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints43() {
		Segment s = new Segment(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11846.9444, 42660.5556));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11846.9444, 42660.5556));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints44() {
		Segment s = new Segment(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11770.2778, 42651.9444));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11770.2778, 42651.9444));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints45() {
		Segment s = new Segment(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11569.4444, 43136.6667));
		Shell AB = new Shell(new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints46() {
		Segment s = new Segment(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11423.8889, 43000.2778));
		Shell AB = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints47() {
		Segment s = new Segment(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11416.6667, 42983.3333));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints48() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11770.2778, 42651.9444));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11973.0556, 43026.1111));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints49() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778),
				new PointND.Double(11416.6667, 42983.3333), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333), new PointND.Double(11183.3333, 42933.3333),
				new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5),
				new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222), new PointND.Double(11511.3889, 42106.3889),
				new PointND.Double(11715.8333, 41836.1111), new PointND.Double(12058.3333, 42195.5556),
				new PointND.Double(12149.4444, 42477.5), new PointND.Double(12300.0, 42433.3333),
				new PointND.Double(12372.7778, 42711.3889), new PointND.Double(12421.6667, 42895.5556),
				new PointND.Double(12645.0, 42973.3333), new PointND.Double(12355.8333, 43156.3889),
				new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11600.0, 43150.0), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11297.5, 42853.3333),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11973.0556, 43026.1111));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints50() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11503.0556, 42855.2778), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

	@Test
	public void testOptimizationBetweenEndpoints51() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11503.0556, 42855.2778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11503.0556, 42855.2778),
				new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889),
				new PointND.Double(11003.6111, 42102.5), new PointND.Double(11438.3333, 42057.2222),
				new PointND.Double(11511.3889, 42106.3889), new PointND.Double(11715.8333, 41836.1111),
				new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12149.4444, 42477.5),
				new PointND.Double(12300.0, 42433.3333), new PointND.Double(12372.7778, 42711.3889),
				new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12645.0, 42973.3333),
				new PointND.Double(12355.8333, 43156.3889), new PointND.Double(12363.3333, 43189.1667),
				new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556),
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0),
				new PointND.Double(11297.5, 42853.3333));
		assertTrue(result.getLength() <= answer.getLength());
	}

}

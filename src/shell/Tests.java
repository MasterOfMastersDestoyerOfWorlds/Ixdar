package shell;

import org.junit.jupiter.api.*;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class Tests {
	
	@Test
	public void testQHull() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));

		PointSet ps = new PointSet();
		
		ps.add(new PointND.Double(1, 0, 0, 1));
		ps.add(new PointND.Double(2, 0, 0, 0));
		ps.add(new PointND.Double(3, 0, 1, 0));
		ps.add(new PointND.Double(4, 0, -1, 0));
		ps.add(new PointND.Double(5, 1, 0, 0));
		ps.add(new PointND.Double(6, -1, 0, 0));
		PointSet orgShell = retTup.ps.convexHullND(ps);
		
		assertTrue(orgShell.size() == 5);
	}

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	@Test
	public void testDjibouti() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));

		Shell orgShell = retTup.ps.toShells();

		Shell pathShell = orgShell.collapseAllShells();
		assertTrue(Math.abs(pathShell.getLength()-retTup.tsp.getLength()) < 0.1);
	}
	
	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	//old: 10038.75729043869 in 3.828s
	//new: 10178.770192333182 in 20.461s
	//@Test
	public void testQatar() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/qa194"));

		Shell orgShell = retTup.ps.toShells();

		Shell pathShell = orgShell.collapseAllShells();
		System.out.println(pathShell.getLength());
		assertTrue(pathShell.getLength() < 9400);
	}
	/**
	 * Tests that we can go from a set of point to a distance matrix, add a node to the distance matrix that would make it non-euclidian, 
	 * and then turn it back into a set of points. The distances are then checked against the orginal distances to ensure that the process behaved correctly.
	 */
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

	/**
	 * Tests ability of the reduce function of collapseReduce in 2D
	 */
	@Test
	public void testReduce2D(){
		Shell AB = new Shell();
		AB.add(new PointND.Double(1, 1, 0));
		AB.add(new PointND.Double(2, -1, 0));
		AB.add(new PointND.Double(3, 0, 1));
		AB.add(new PointND.Double(4, 0, -1));
		Shell nothing = new Shell();
		Shell result = Shell.collapseReduce(AB, nothing, 0);
		Shell answer = new Shell();
		answer.add(new PointND.Double(1, 1, 0));
		answer.add(new PointND.Double(3, 0, 1));
		answer.add(new PointND.Double(2, -1, 0));
		answer.add(new PointND.Double(4, 0, -1));
		assertTrue(result.getLength() <= answer.getLength());
	}

	/**
	 * Tests ability of the reduce function of collapseReduce in 3D
	 */
	@Test
	public void testReduce3D(){
		Shell AB = new Shell();
		AB.add(new PointND.Double(3, 0, 1, 0));
		AB.add(new PointND.Double(4, 0, -1, 0));
		AB.add(new PointND.Double(1, 0, 0, 1));
		AB.add(new PointND.Double(5, 1, 0, 0));
		AB.add(new PointND.Double(6, -1, 0, 0));
		Shell nothing = new Shell();
		Shell result = Shell.collapseReduce(AB, nothing, 0);
		Shell answer = new Shell();
		answer.add(new PointND.Double(1, 0, 0, 1));
		answer.add(new PointND.Double(3, 0, 1, 0));
		answer.add(new PointND.Double(5, 1, 0, 0));
		answer.add(new PointND.Double(4, 0, -1, 0));
		answer.add(new PointND.Double(6, -1, 0, 0));
		assertTrue(result.getLength() <= answer.getLength());
	}
	
	/**
	 * Tests ability of the reduce function of collapseReduce in 3D
	 */
	@Test
	public void testConvexHull3D(){
		PointSet ps = new PointSet();
		ps.add(new PointND.Double(3, 0, 1, 0));
		ps.add(new PointND.Double(4, 0, -1, 0));
		ps.add(new PointND.Double(1, 0, 0, 1));
		ps.add(new PointND.Double(5, 1, 0, 0));
		ps.add(new PointND.Double(6, -1, 0, 0));
		Shell result = ps.toShells();
		assertTrue(result.updateOrder() == 1);
	}

	/**
	 * Tests abbility to solve optimally between two set endpoints. the answer Shell is known to be correct.
	 */
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
		PointSet ps = new PointSet();
		System.out.println("1");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, answer));
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
		PointSet ps = new PointSet();
		System.out.println("2");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
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
		PointSet ps = new PointSet();
		System.out.println("3");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
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
		PointSet ps = new PointSet();
		System.out.println("5");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
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
		PointSet ps = new PointSet();
		System.out.println("7");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, answer));
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
		Segment s = new Segment(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11423.8889, 43000.2778));
		Shell AB = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778));
		Shell nothing = new Shell();
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11485.5556, 43187.2222),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11423.8889, 43000.2778));
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
		PointSet ps = new PointSet();
		System.out.println("13");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
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
		PointSet ps = new PointSet();
		System.out.println("16");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
	}

	@Test
	//TODO:i think this one is wrong
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
		PointSet ps = new PointSet();
		System.out.println("17");
		ps.addAll(answer);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(Shell.compareTo(loop, result));
	}



}

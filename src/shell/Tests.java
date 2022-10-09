package shell;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.awt.List;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

import javax.swing.plaf.synth.SynthOptionPaneUI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class Tests {
	
	//@Test
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
	//@Test
	public void testDjibouti() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));
		DistanceMatrix d = new DistanceMatrix(retTup.ps);
		Shell orgShell = retTup.ps.toShells(d);

		Shell pathShell = orgShell.collapseAllShells(d);
		assertTrue(Math.abs(pathShell.getLength()-retTup.tsp.getLength()) < 0.1);
	}
	
	@Test
	public boolean testDjiboutiN(int n, int rot) {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/djbouti"));
		PointSet ps = new PointSet();
		Shell answer = new Shell();

		Shell AB = new Shell();
		for(int i = rot; i < n + rot && i < retTup.ps.size(); i ++) {
			ps.add(retTup.ps.get(i));
			answer.add(retTup.ps.get(i));
			if(i != n+rot-1 && i != rot) {
				AB.add(retTup.ps.get(i));
			}
		}
		if(n + rot > retTup.ps.size()) {
			for(int i = 0; i < (n + rot) - retTup.ps.size(); i ++) {
				ps.add(retTup.ps.get(i));
				answer.add(retTup.ps.get(i));
				if(i != (n + rot - 1) - retTup.ps.size()) {
					AB.add(retTup.ps.get(i));
				}
			}
		}
		System.out.println("before   " + AB);
		Collections.shuffle(AB);//, new Random(2));
		System.out.println("shuffled " + AB);
		Segment s = new Segment(answer.getFirst(), answer.getLast());

		Shell nothing = new Shell();

		DistanceMatrix d = new DistanceMatrix(ps);
		
		
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		
		System.out.println(result + " " + result.getLength());
		System.out.println(answer + " " + answer.getLength());
		System.out.println("=========================");
		
		assert(result.getLength() <= answer.getLength()) : "result: " + result + " \n Shell was length: " + result.getLength() + "\n answer: " + answer + "\n Supposed to be length: " + answer.getLength();

		return result.getLength() <= answer.getLength() && result.size() == answer.size();
	}
	
	 @TestFactory
	  public Collection<DynamicTest> djboutiDynamicTests() {


	    Collection<DynamicTest> dynamicTests = new ArrayList<>();
	    int n = 38;

	    int [] a=new int[n];
	    for (int i=1;i <n;++i){
	    	a[i]=i + 1;
	    }
	    
	    int [] b=new int[n];
	    for (int i=0;i <n;++i){
	    	b[i]=i;
	    }
	    
	    for (int i = 1; i < n; i++) {
	    	
	      int num = a[i];

	      // create an test execution
	      for(int j = 0; j < n; j++) {

		      int rot = b[j];
		      // create a test display name
		      String testName = "Test djbouti size" + (i + 1) + " rot" +j;
		      // create dynamic test
		      DynamicTest dTest = DynamicTest.dynamicTest(testName, () -> assertEquals(true, testDjiboutiN(num, rot)));
	
		      // add the dynamic test to collection
		      dynamicTests.add(dTest);
	      }
	    }
	    return dynamicTests;
	  }
	
	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */
	//old: 10038.75729043869 in 3.828s
	//new: 10178.770192333182 in 20.461s
	//@Test
	public void testQatar() {
		PointSetPath retTup = Main.importFromFile(new File("./src/shell/qa194"));
		DistanceMatrix d = new DistanceMatrix(retTup.ps);
		Shell orgShell = retTup.ps.toShells(d);

		Shell pathShell = orgShell.collapseAllShells(d);
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
		PointND start = new PointND.Double(0.0, -1.0), end = new PointND.Double(0.0, 1.0);

		ps.add(start);

		ps.add(end);

		ps.add(new PointND.Double(0.0, 0.0));

		DistanceMatrix m = new DistanceMatrix(ps);

		m.addDummyNode(new Segment(start, end));

		double[][] distances = m.getMatrix();

		PointSet triangulated = m.toPointSet();

		DistanceMatrix triangulatedM = new DistanceMatrix(triangulated);

		double[][] triDistances = triangulatedM.getMatrix();
		assertTrue(triDistances.length == distances.length);

		for (int i = 0; i < distances.length; i++) {
			for (int j = 0; j < distances.length; j++) {
				if (i != j) {
					assertTrue((distances[i][j]) == Math.round(triDistances[i][j]));
				} else {
					assertTrue(distances[i][j] == triDistances[i][j]);
				}
			}
		}

	}
	
	/**
	 * Tests that we can go from a set of point to a distance matrix, add a node to the distance matrix that would make it non-euclidian, 
	 * and then turn it back into a set of points. The distances are then checked against the orginal distances to ensure that the process behaved correctly.
	 */
	@Test
	public void testAngleDummyNode() {
		PointSet ps = new PointSet();
		PointND start = new PointND.Double(0.0, -1.0), end = new PointND.Double(0.0, 1.0);
		System.out.println(end.getID());

		ps.add(start);

		ps.add(end);

		ps.add(new PointND.Double(0.0, 0.0));

		DistanceMatrix m = new DistanceMatrix(ps);

		PointND dummy = m.addDummyNode(new Segment(start, end));

		double angle = Vectors.findAngleSegments(start, dummy, end, m);
		//										      AB = 4.0   AC = 4.0
		
		System.out.println(m);
		System.out.println(180/Math.PI*angle);

	}
	
	/**
	 * Tests that we can go from a set of point to a distance matrix, add a node to the distance matrix that would make it non-euclidian, 
	 * and then turn it back into a set of points. The distances are then checked against the orginal distances to ensure that the process behaved correctly.
	 */
	@Test
	public void testCentroidCalculation() {
		PointSet ps1 = new PointSet();
		PointND start = new PointND.Double(0.0, -1.0), end = new PointND.Double(0.0, 1.0);

		ps1.add(start);

		ps1.add(end);
		
		PointND other1 = new PointND.Double(0.0, 0.0);

		ps1.add(other1);
		
		PointND other = new PointND.Double(3.0, 0.0);
		
		ps1.add(other);

		DistanceMatrix m = new DistanceMatrix(ps1);
		
		PointSet triangulated = m.toPointSet();

		DistanceMatrix triangulatedM = new DistanceMatrix(triangulated);
		System.out.println("Tri" +  triangulatedM);

		m.addDummyNode(new Segment(start, end));
		triangulated = m.toPointSet();
		triangulatedM = new DistanceMatrix(triangulated);
		System.out.println("Tri" + triangulatedM);
		System.out.println(m);
		m.addDummyNode(new Segment(other, other1));
		triangulated = m.toPointSet();
		triangulatedM = new DistanceMatrix(triangulated);
		System.out.println("Tri" + triangulatedM);
		System.out.println(m);
		
		PointND centroid = m.findCentroid();
		PointSet ps3 = m.toPointSet();
		System.out.println(ps3);
		ps3.remove(2);
		DistanceMatrix m1 = new DistanceMatrix(m.toPointSet());
		System.out.println(m1);
		DistanceMatrix m3 = new DistanceMatrix(ps3, m);
		PointSet ps2 = m.toPointSet();
		for(PointND p1 : ps2) {
			
			for(PointND p2 : ps2) {
				assert(Math.abs(m.getDistance(p1, p2) - m1.getDistance(p1, p2)) < 0.001) : m.getDistance(p1, p2) + " " + m1.getDistance(p1, p2);
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
		PointSet ps = AB.toPointSet();
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell nothing = new Shell();
		Shell result = Shell.collapseReduce(AB, nothing, d);
		Shell answer = new Shell();
		answer.add(new PointND.Double(1, 1, 0));
		answer.add(new PointND.Double(3, 0, 1));
		answer.add(new PointND.Double(2, -1, 0));
		answer.add(new PointND.Double(4, 0, -1));
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
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
		PointSet ps = AB.toPointSet();
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell nothing = new Shell();
		Shell result = Shell.collapseReduce(AB, nothing,d);
		Shell answer = new Shell();
		answer.add(new PointND.Double(1, 0, 0, 1));
		answer.add(new PointND.Double(3, 0, 1, 0));
		answer.add(new PointND.Double(5, 1, 0, 0));
		answer.add(new PointND.Double(4, 0, -1, 0));
		answer.add(new PointND.Double(6, -1, 0, 0));
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
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
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);		Shell answer = new Shell();
		answer.add(new PointND.Double(11438.3333, 42057.2222));
		answer.add(new PointND.Double(11511.3889, 42106.3889));
		answer.add(new PointND.Double(11715.8333, 41836.1111));
		answer.add(new PointND.Double(12058.3333, 42195.5556));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints2() {
		Segment s = new Segment(new PointND.Double(12058.3333, 42195.5556), new PointND.Double(12372.7778, 42711.3889));
		Shell AB = new Shell();
		AB.add(new PointND.Double(12300.0, 42433.3333));
		AB.add(new PointND.Double(12149.4444, 42477.5));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);		Shell answer = new Shell();
		answer.add(new PointND.Double(12058.3333, 42195.5556));
		answer.add(new PointND.Double(12149.4444, 42477.5));
		answer.add(new PointND.Double(12300.0, 42433.3333));
		answer.add(new PointND.Double(12372.7778, 42711.3889));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());

	}

	@Test
	public void testOptimizationBetweenEndpoints3() {
		Segment s = new Segment(new PointND.Double(12421.6667, 42895.5556), new PointND.Double(12363.3333, 43189.1667));
		Shell AB = new Shell();
		AB.add(new PointND.Double(12645.0, 42973.3333));
		AB.add(new PointND.Double(12355.8333, 43156.3889));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);		Shell answer = new Shell();
		answer.add(new PointND.Double(12421.6667, 42895.5556));
		answer.add(new PointND.Double(12645.0, 42973.3333));
		answer.add(new PointND.Double(12355.8333, 43156.3889));
		answer.add(new PointND.Double(12363.3333, 43189.1667));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());

	}

	@Test
	public void testOptimizationBetweenEndpoints4() {
		Segment s = new Segment(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(11963.0556, 43290.5556));
		Shell AB = new Shell(new PointND.Double(12386.6667, 43334.7222), new PointND.Double(12286.9444, 43355.5556));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(12363.3333, 43189.1667), new PointND.Double(12386.6667, 43334.7222),
				new PointND.Double(12286.9444, 43355.5556), new PointND.Double(11963.0556, 43290.5556));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints5() {
		Segment s = new Segment(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11485.5556, 43187.2222));
		Shell AB = new Shell(new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11461.1111, 43252.7778), new PointND.Double(11485.5556, 43187.2222));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints6() {
		Segment s = new Segment(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11183.3333, 42933.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11310.2778, 42929.4444),
				new PointND.Double(11183.3333, 42933.3333));
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints7() {
		Segment s = new Segment(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11108.6111, 42373.8889));
		Shell AB = new Shell(new PointND.Double(11133.3333, 42885.8333), new PointND.Double(11155.8333, 42712.5));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11183.3333, 42933.3333), new PointND.Double(11133.3333, 42885.8333),
				new PointND.Double(11155.8333, 42712.5), new PointND.Double(11108.6111, 42373.8889));

		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints8() {
		Segment s = new Segment(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11438.3333, 42057.2222));
		Shell AB = new Shell(new PointND.Double(11003.6111, 42102.5));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11108.6111, 42373.8889), new PointND.Double(11003.6111, 42102.5),
				new PointND.Double(11438.3333, 42057.2222));
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
		
	}

	@Test
	public void testOptimizationBetweenEndpoints9() {
		Segment s = new Segment(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11846.9444, 42660.5556));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11785.2778, 42884.4444),
				new PointND.Double(11846.9444, 42660.5556));
		System.out.println(answer.getLength());
		System.out.println(result.getLength());
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints10() {
		Segment s = new Segment(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11770.2778, 42651.9444));
		Shell AB = new Shell(new PointND.Double(11822.7778, 42673.6111));
		Shell answer = new Shell(new PointND.Double(11846.9444, 42660.5556), new PointND.Double(11822.7778, 42673.6111),
				new PointND.Double(11770.2778, 42651.9444));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);

		System.out.println(result);
		System.out.println(answer);
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints11() {
		Segment s = new Segment(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11569.4444, 43136.6667));
		Shell AB = new Shell(new PointND.Double(11583.3333, 43150.0));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell(new PointND.Double(11595.0, 43148.0556), new PointND.Double(11583.3333, 43150.0),
				new PointND.Double(11569.4444, 43136.6667));
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints12() {
		System.out.println("12");
		Segment s = new Segment(new PointND.Double(11569.4444, 43136.6667), new PointND.Double(11423.8889, 43000.2778));
		Shell AB = new Shell(new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell();
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);

		System.out.println(result);
		System.out.println(answer);
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
		

	}

	@Test
	public void testOptimizationBetweenEndpoints13() {
		Segment s = new Segment(new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11310.2778, 42929.4444), new PointND.Double(11416.6667, 42983.3333));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		Shell answer = new Shell();
		
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);
		
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());

	}

	@Test
	public void testOptimizationBetweenEndpoints14() {
		Segment s = new Segment(new PointND.Double(11600.0, 43150.0), new PointND.Double(11973.0556, 43026.1111));
		Shell AB = new Shell(new PointND.Double(11785.2778, 42884.4444), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11770.2778, 42651.9444));
		Shell answer = new Shell();
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);
		System.out.println(answer);
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);

		System.out.println(result);
		System.out.println(answer);
		System.out.println(result.getLength());
		System.out.println(answer.getLength());
		
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());
	}

	@Test
	public void testOptimizationBetweenEndpoints15() {
		System.out.println(15);
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
		Shell answer = new Shell();
		
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);
		
		System.out.println(answer);
		
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);

		System.out.println(result);
		
		System.out.println(answer.getLength());
		System.out.println(result.getLength());
		System.out.println(AB.size() + 2);
		System.out.println(answer.size() + " " + result.sizeRecursive());
		
		assertTrue(result.size() == answer.size());
		assertTrue(result.getLength() <= answer.getLength());
		/*
		 * Shell[15, 23, 24, 1, 30]
			Shell[19, 27, 28, 6, 16, 18]
			Shell[20, 21, 26, 29, 12, 14, 17]
			Shell[10, 13, 11, 5, 25, 22]
			Shell[7, 3, 2, 8, 9]
			Shell[4, 0]
		 */
		/*
		 * 
		   Shell[0, 6, 5, 3, 2, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 1]
		 */
	}

	@Test
	public void testOptimizationBetweenEndpoints16() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11690.5556, 42686.6667),
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
				new PointND.Double(11963.0556, 43290.5556), new PointND.Double(11600.0, 43150.0));
		Shell nothing = new Shell();
		//Shell result = Shell.solveBetweenEndpoints(s, AB, nothing);
		Shell answer = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);
		//ps.add(s.last);
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);
		/*System.out.println(d);
		Shell loop  = ps.toShells().collapseAllShells();
		System.out.println(loop);*/
		System.out.println("answer " + answer);
		System.out.println("result "+ result);
		/*System.out.println(answer.get(2).getID());
		System.out.println(answer.get(2).distance(answer.get(0)));*/
		System.out.println(answer.getLength());
		System.out.println(result.getLength());
		
		assertTrue(result.size() == answer.size());
		assertTrue(result.getLength() <= answer.getLength());

	}

	@Test
	//TODO:i think this one is wrong
	public void testOptimizationBetweenEndpoints17() {
		Segment s = new Segment(new PointND.Double(11770.2778, 42651.9444), new PointND.Double(11297.5, 42853.3333));
		Shell AB = new Shell(new PointND.Double(11690.5556, 42686.6667),
				new PointND.Double(11822.7778, 42673.6111), new PointND.Double(11846.9444, 42660.5556),
				new PointND.Double(11973.0556, 43026.1111), new PointND.Double(11595.0, 43148.0556),
				new PointND.Double(11583.3333, 43150.0), new PointND.Double(11569.4444, 43136.6667),
				new PointND.Double(11485.5556, 43187.2222), new PointND.Double(11461.1111, 43252.7778),
				new PointND.Double(11423.8889, 43000.2778), new PointND.Double(11416.6667, 42983.3333),
				new PointND.Double(11503.0556, 42855.2778), new PointND.Double(11310.2778, 42929.4444));
		Shell nothing = new Shell();
		PointSet ps = AB.toPointSet();
		ps.add(s.first);
		ps.add(s.last);
		DistanceMatrix d = new DistanceMatrix(ps);
		Shell result = Shell.solveBetweenEndpoints(s, AB, nothing, d);		Shell answer = new Shell();
		answer.add(s.first);
		answer.addAll(AB);
		answer.add(s.last);
		System.out.println(answer);
		System.out.println(result);
		
		
		System.out.println(answer.getLength());
		System.out.println(result.getLength());
		System.out.println(AB.size() + 2);
		System.out.println(answer.size() + " " + result.size());
		assertTrue(result.getLength() <= answer.getLength() && result.size() == answer.size());

	}



}

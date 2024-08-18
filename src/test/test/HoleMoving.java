package test;

import shell.DistanceMatrix;
import shell.FileManagement;
import shell.enums.RouteType;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.route.Route;
import shell.route.RouteInfo;
import shell.route.RouteMap;
import shell.shell.Shell;
import shell.ui.PointSetPath;
import shell.PointND;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

/**
 * Tests to verify that our tsp solver works as expected
 */
public class HoleMoving {

	/**
	 * Tests that our solver solves the djibouti problem set correctly
	 */

	@Test
	public void test_lines_kp1_10_kp2_9_layer_1() {
		testMethod("lines", "lines_kp1_10_kp2_9_layer_1.csv", 1, 10, 0, 9, 19, true, 0, RouteType.nextC);
	}

	@Test
	public void test_lines_kp1_10_kp2_9_layer_2() {
		testMethod("lines", "lines_kp1_10_kp2_9_layer_2.csv", 2, 10, 0, 9, 19, true, 1, RouteType.prevC);
	}

	@Test
	public void test_two_circles_kp1_5_kp2_10_layer_1() {
		testMethod("two_circle_in_10", "two_circles_kp1_5_kp2_10_layer_1.csv", 1, 5, 4, 10, 19, true, 4,
				RouteType.nextC);
	}

	@Test
	public void two_circles_kp1_5_kp2_10_after_16() {
		testMethod("two_circle_in_10", "two_circles_kp1_5_kp2_10_after_16.csv", -1, 5, 4, 10, 19, true, 16,
				RouteType.prevDC);
	}

	@Test
	public void djibouti_14_31_kp1_3_kp2_0_layer_1() {
		testMethod("djbouti_14-31-simple", "djibouti_14_31_kp1_3_kp2_0_layer_1.csv", -1, 3, 4, 0, 5, false, -1,
				RouteType.nextC);
	}

	@Test
	public void test_wi29_5_25_kp1_0_kp2_3_layer_1() {
		testMethod("wi29_5-25-simple", "wi29_5_25_kp1_0_kp2_3_layer_1.csv", 1, 5, 6, 2, 3, true, 6, RouteType.prevC);
	}

	@Test
	public void test_wi29_5_25_kp1_0_kp2_3_layer_2() {
		testMethod("wi29_5-25-simple", "wi29_5_25_kp1_0_kp2_3_layer_2.csv", 2, 5, 6, 2, 3, true, 0, RouteType.nextC);
	}

	@Test
	public void test_wi29_5_25_kp1_0_kp2_3_layer_3() {
		testMethod("wi29_5-25-simple", "wi29_5_25_kp1_0_kp2_3_layer_3.csv", 3, 5, 6, 2, 3, true, 4, RouteType.nextDC);
	}

	@Test
	public void test_wi29_5_25_kp1_0_kp2_3_layer_4() {
		testMethod("wi29_5-25-simple", "wi29_5_25_kp1_0_kp2_3_layer_4.csv", 4, 5, 6, 2, 3, true, 1, RouteType.nextC);
	}

	@Test
	public void test_wi29_5_25_kp1_0_kp2_3_layer_5() {
		testMethod("wi29_5-25-simple", "wi29_5_25_kp1_0_kp2_3_layer_5.csv", 5, 5, 6, 2, 3, true, 4, RouteType.prevC);
	}

	@Test
	public void wi29_6_25p20_kp1_4_kp2_7_layer_1() {
		testMethod("wi29_6-25p20", "wi29_6_25p20_kp1_4_kp2_7_layer_1.csv", -1, 4, 5, 7, 6, false, 1,
				RouteType.nextC);
	}

	@Test
	public void circle_in_5_arc_kp1_2_kp2_6_layer_1() {
		testMethod("circle_in_5_arc", "circle_in_5_arc_kp1_2_kp2_6_layer_1.csv", 1, 2, 3, 6, 5, true, 3,
				RouteType.prevC);
	}

	public void testMethod(String fileName, String stateFile, int layer, int kp1, int cp1, int kp2, int cp2,
			boolean knotPointsConnected, int sourcePoint, RouteType routeType) {
		PointSetPath retTup = FileManagement.importFromFile(new File("./src/test/solutions/" + fileName));
		Shell answer = new Shell();
		int n = retTup.ps.size();

		Shell AB = new Shell();
		for (int i = 0; i < n && i < retTup.tsp.size(); i++) {
			answer.add(retTup.tsp.get(i));
			AB.add(retTup.tsp.get(i));
		}

		DistanceMatrix d = retTup.d;
		if (retTup.d == null) {
			d = new DistanceMatrix(retTup.ps);
		}
		PointND wormHole = d.addDummyNode(d.size(), retTup.ps.getByID(kp1), retTup.ps.getByID(kp2));
		AB.initPoints(d);
		ArrayList<VirtualPoint> kPoints = new ArrayList<>();
		VirtualPoint wh = AB.pointMap.get(wormHole.getID());
		for (PointND p : retTup.tsp) {
			kPoints.add(AB.pointMap.get(p.getID()));
		}
		Knot k = new Knot(kPoints, AB, true);
		VirtualPoint knotPoint1 = AB.pointMap.get(kp1);
		VirtualPoint cutPoint1 = AB.pointMap.get(cp1);
		VirtualPoint knotPoint2 = AB.pointMap.get(kp2);
		VirtualPoint cutPoint2 = AB.pointMap.get(cp2);
		Segment cutSegment1 = k.getSegment(knotPoint1, cutPoint1);
		Segment cutSegment2 = k.getSegment(knotPoint2, cutPoint2);

		VirtualPoint external1 = wh;
		VirtualPoint external2 = wh;

		RouteMap<Integer, RouteInfo> routeMap = AB.cutEngine.internalPathEngine.ixdar(
				knotPoint1, cutPoint1,
				knotPoint2, cutPoint2, k, knotPointsConnected, cutSegment1, cutSegment2, layer, sourcePoint,
				routeType);

		try {

			BufferedReader br = new BufferedReader(new FileReader(new File("./src/test/routeMap/" + stateFile)));
			String line = br.readLine();
			while (line != null) {
				String[] cords = line.split(",");
				int id = java.lang.Integer.parseInt(cords[0]);
				RouteInfo r = routeMap.get(id);
				checkRoute(r, cords, 1, "prevC", id, AB);
				checkRoute(r, cords, 4, "prevDC", id, AB);
				checkRoute(r, cords, 7, "nextC", id, AB);
				checkRoute(r, cords, 10, "nextDC", id, AB);

				line = br.readLine();

			}
			br.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();

			boolean flag = false;
			assert (flag) : "" + e.toString();
		} catch (IOException e) {
			e.printStackTrace();
			boolean flag = false;
			assert (flag) : "" + e.toString();
		}

		System.out.println("reee");
	}

	public void checkRoute(RouteInfo r, String[] cords, int offset, String routeName, int id, Shell AB) {

		String routeType = cords[offset];
		assert (routeType.equals(routeName)) : "Malformed State Test: " + routeName + " expected";
		VirtualPoint ancestor = null;
		if (!cords[offset + 1].equals("NULL")) {
			int ancestorId = java.lang.Integer.parseInt(cords[offset + 1]);
			ancestor = AB.pointMap.get(ancestorId);
		}
		double delta = Double.MAX_VALUE;
		if (!cords[offset + 2].equals("INF")) {
			delta = java.lang.Double.parseDouble(cords[offset + 2]);
		}
		Route route = r.getRoute(RouteType.valueOf(routeName));
		if (ancestor == null) {
			assert (route.ancestor == null)
					: "Point Id: " + id + " neighbor: " + route.neighbor.id + " " + routeName + " ancestor: "
							+ route.ancestor + " expected: " + null;
		} else {
			if (route.ancestor == null) {
				boolean flag = false;
				assert (flag) : "Point Id: " + id + " neighbor: " + route.neighbor.id + " " + " " + routeName
						+ " ancestor: " + route.ancestor + " expected: " + ancestor;
			}
			assert (route.ancestor.equals(ancestor)) : "Point Id: " + id + " neighbor: " + route.neighbor.id + " " + " "
					+ routeName + " ancestor: " + route.ancestor + " expected: " + ancestor;
		}
		assert (Math.abs(route.delta - delta) < 0.1) : "Point Id: " + id + " " + routeName + " delta: " +
				route.delta + " expected: " + delta;
	}
}
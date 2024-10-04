package shell;

import static org.lwjgl.opengl.GL11.glViewport;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.util.Pair;
import org.joml.Vector2f;

import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.cuts.CutEngine;
import shell.cuts.InternalPathEngine;
import shell.cuts.route.RouteInfo;
import shell.exceptions.SegmentBalanceException;
import shell.file.FileManagement;
import shell.file.Manifold;
import shell.file.PointSetPath;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Run;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.render.sdf.SDFCircle;
import shell.render.sdf.SDFTexture;
import shell.shell.Shell;
import shell.shell.ShellComparator;
import shell.shell.ShellPair;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.ui.IxdarWindow;
import shell.ui.input.keys.KeyGuy;
import shell.ui.input.mouse.MouseTrap;
import shell.ui.tools.FreeTool;
import shell.ui.tools.Tool;

public class Main {

	public static File file;
	static String fileName;

	public static Main main;
	public static Camera2D camera;

	public static Tool tool;
	public static FreeTool freeTool = new FreeTool();

	public static Shell shell;
	public static PointSetPath retTup;
	public static Shell orgShell;
	public static ArrayList<Shell> subPaths = new ArrayList<>();
	public static Shell resultShell;
	public static ArrayList<VirtualPoint> result;
	public static SegmentBalanceException sbe;

	public static Knot manifoldKnot;
	public static int manifoldIdx = 0;
	public static ArrayList<Manifold> manifolds;
	public static int metroDrawLayer = -1;
	static PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
	public static PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
	public static ArrayList<Knot> knotsDisplayed = new ArrayList<>();

	public static Color stickyColor;
	public static ArrayList<Color> metroColors = new ArrayList<>();
	public static ArrayList<Color> knotGradientColors = new ArrayList<>();
	public static HashMap<Long, Integer> colorLookup = new HashMap<>();
	public static boolean active;
	public Canvas canvas;
	private static KeyGuy keys;
	private static MouseTrap mouseTrap;
	private SDFTexture logo;

	final int RIGHT_PANEL_SIZE = 250;
	final int BOTTOM_PANEL_SIZE = 250;

	public Main(String fileName) {
		Main.fileName = fileName;
		file = FileManagement.getTestFile(fileName);
		retTup = FileManagement.importFromFile(file);
		IxdarWindow frame = IxdarWindow.frame;
		frame.setName("Ixdar : " + fileName);

		camera = new Camera2D(Canvas3D.frameBufferWidth - RIGHT_PANEL_SIZE,
				Canvas3D.frameBufferHeight - BOTTOM_PANEL_SIZE, 0.9f, 0, BOTTOM_PANEL_SIZE,
				retTup.ps);

		Toggle.manifold.value = !retTup.manifolds.isEmpty();

		keys = new KeyGuy(this, frame, fileName, camera);
		mouseTrap = new MouseTrap(this, frame, camera, false);

		frame.addKeyListener(keys);
		frame.addMouseListener(mouseTrap);
		frame.addMouseMotionListener(mouseTrap);
		frame.addMouseWheelListener(mouseTrap);
		Canvas3D.canvas.addMouseMotionListener(mouseTrap);
		Canvas3D.canvas.addMouseListener(mouseTrap);
		Canvas3D.canvas.addMouseWheelListener(mouseTrap);
		tool = new FreeTool();

	}

	public static void main(String[] args) {
		main = new Main(args[0]);
		camera.initCamera();
		DistanceMatrix d = retTup.d;
		if (retTup.d == null) {
			d = new DistanceMatrix(retTup.ps);
		}
		ArrayList<PointND> toRemove = new ArrayList<>();
		for (PointND p : retTup.tsp) {
			if (p == null) {
				toRemove.add(p);
			}
		}
		retTup.tsp.removeAll(toRemove);
		if (tool.canUseToggle(Toggle.manifold)) {
			Toggle.manifold.value = true;
			Toggle.calculateKnot.value = false;
		}
		orgShell = retTup.tsp;

		shell = orgShell.copyShallow();

		shell.knotName = fileName;

		Collections.shuffle(shell);
		long startTimeKnotFinding = System.currentTimeMillis();

		result = new ArrayList<>(shell.slowSolve(shell, d, 10));

		long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
		double knotFindingSeconds = ((double) endTimeKnotFinding) / 1000.0;

		long startTimeKnotCutting = System.currentTimeMillis();
		calculateSubPaths();

		manifoldKnot = shell.cutEngine.flatKnots.values().iterator().next();
		for (Knot f : shell.cutEngine.flatKnots.values()) {
			if (f.knotPointsFlattened.size() > manifoldKnot.size()) {
				manifoldKnot = f;
			}
		}
		manifolds = retTup.manifolds;
		for (Manifold m : manifolds) {
			m.manifoldKnot = manifoldKnot;
			try {
				m.loadCutMatch(shell);
			} catch (SegmentBalanceException e) {
				e.printStackTrace();
			}
		}

		if (tool.canUseToggle(Toggle.manifold)) {
			if (result.size() > 1) {
				Toggle.manifold.value = false;
				Toggle.calculateKnot.value = true;
			}

			manifolds.parallelStream().forEach((m) -> {
				try {
					m.calculateManifoldCutMatch(shell, manifoldKnot);
				} catch (SegmentBalanceException sbe) {
					segmentBalanceExceptionHandler(sbe);
				}
			});

		}
		shell.buff.flush();

		Random colorSeed = new Random();

		int numKnots = shell.cutEngine.flatKnots.size();
		float startHue = colorSeed.nextFloat();
		float step = 1.0f / ((float) numKnots);
		int i = 0;
		for (Knot k : shell.cutEngine.flatKnots.values()) {
			knotGradientColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
			colorLookup.put((long) k.id, i);
			i++;
		}

		int totalLayers = shell.cutEngine.totalLayers;
		if (totalLayers == -1) {
			totalLayers = shell.cutEngine.totalLayers;
		}
		metroDrawLayer = totalLayers;
		Set<Integer> knotIds = shell.cutEngine.flatKnots.keySet();
		HashMap<Integer, Knot> flatKnots = shell.cutEngine.flatKnots;
		HashMap<Integer, Integer> flatKnotsHeight = shell.cutEngine.flatKnotsHeight;
		HashMap<Integer, Integer> flatKnotsLayer = shell.cutEngine.flatKnotsLayer;
		for (Integer id : knotIds) {
			Knot k = flatKnots.get(id);
			int heightNum = flatKnotsHeight.get(id);
			int layerNum = flatKnotsLayer.get(id);
			Shell knotShell = new Shell();
			for (VirtualPoint p : k.knotPointsFlattened) {
				knotShell.add(((Point) p).p);
			}
			if (totalLayers - layerNum == metroDrawLayer) {
				knotsDisplayed.add(k);
			}
			metroPathsHeight.add(new ShellPair(knotShell, k, heightNum));
			metroPathsLayer.add(new ShellPair(knotShell, k, totalLayers - layerNum));
		}

		float startHueM = colorSeed.nextFloat();
		float stepM = 1.0f / ((float) totalLayers);
		for (int j = 0; j <= totalLayers; j++) {
			metroColors.add(Color.getHSBColor((startHueM + stepM * j) % 1.0f, 1.0f, 1.0f));
		}

		long endTimeKnotCutting = System.currentTimeMillis() - startTimeKnotCutting;
		double knotCuttingSeconds = ((double) endTimeKnotCutting) / 1000.0;
		double ixdarSeconds = ((double) shell.cutEngine.internalPathEngine.totalTimeIxdar) / 1000.0;
		double ixdarProfileSeconds = ((double) shell.cutEngine.internalPathEngine.profileTimeIxdar) / 1000.0;
		System.out.println(result);
		System.out.println("Knot-finding time: " + knotFindingSeconds);
		System.out.println("Knot-cutting time: " + knotCuttingSeconds);
		System.out.println("Ixdar time: " + ixdarSeconds);
		System.out.println("Ixdar counted: " + CutEngine.countCalculated);
		System.out.println("Ixdar skip: " + CutEngine.countSkipped);
		System.out.println("Ixdar Calls:" + shell.cutEngine.internalPathEngine.ixdarCalls);
		System.out.println("maxSettledSize: " + RouteInfo.maxSettledSize);

		System.out.println("comparisons " + String.format("%,d", InternalPathEngine.comparisons));
		System.out.println("N " + shell.size());

		System.out.println(
				"Knot-cutting %: " + 100 * (knotCuttingSeconds / (knotCuttingSeconds + knotFindingSeconds)));

		System.out.println(
				"Ixdar %: " + 100 * (ixdarSeconds / (knotCuttingSeconds + knotFindingSeconds)));

		System.out.println("Ixdar profile time: " + ixdarProfileSeconds);
		System.out.println(
				"Ixdar Profile %: " + 100 * (ixdarProfileSeconds / (ixdarSeconds)));
		System.out.println("Best Length: " + orgShell.getLength());
		System.out.println("===============================================");

		System.out.println(shell.cutEngine.flatKnots);
		stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
		stickyColor = Color.CYAN;

	}

	public void draw(Camera camera3D) {
		camera.updateSize(Canvas3D.frameBufferWidth - RIGHT_PANEL_SIZE, Canvas3D.frameBufferHeight - BOTTOM_PANEL_SIZE);
		try {
			float SHIFT_MOD = 1;
			if (keys != null && keys.pressedKeys.contains(KeyEvent.VK_SHIFT)) {
				SHIFT_MOD = 2;
			}
			if (keys != null) {
				keys.paintUpdate(SHIFT_MOD);
			}
			if (mouseTrap != null) {
				mouseTrap.paintUpdate(SHIFT_MOD);
			}
			glViewport(0, BOTTOM_PANEL_SIZE,
					Canvas3D.frameBufferWidth - RIGHT_PANEL_SIZE, Canvas3D.frameBufferHeight - BOTTOM_PANEL_SIZE);
			camera.setZIndex(camera3D);
			camera.calculateCameraTransform();
			tool.draw(camera, Drawing.MIN_THICKNESS);

			if (sbe != null) {
				resultShell.drawShell(true, Drawing.MIN_THICKNESS * 2, Color.MAGENTA, retTup.ps,
						camera);
				Drawing.drawCutMatch(sbe, Drawing.MIN_THICKNESS * 2, retTup.ps, camera);
			}
			if (!(retTup == null)) {
				Drawing.drawPath(retTup.tsp, Drawing.MIN_THICKNESS, Color.RED, retTup.ps, false, false,
						true,
						false,
						camera);
			}
			if (tool.canUseToggle(Toggle.drawCutMatch) && tool.canUseToggle(Toggle.manifold)
					&& manifolds != null
					&& manifolds.get(manifoldIdx).cutMatchList != null) {
				Manifold m = manifolds.get(manifoldIdx);
				Drawing.drawCutMatch(m.cutMatchList, m.manifoldCutSegment1,
						m.manifoldCutSegment2, m.manifoldExSegment1, m.manifoldExSegment2,
						m.manifoldKnot, Drawing.MIN_THICKNESS * 2, retTup.ps, camera);
			}
			if (tool.canUseToggle(Toggle.drawMainPath)) {
				orgShell.drawShell(false, Drawing.MIN_THICKNESS, Color.BLUE, retTup.ps, camera);
			}
			if (tool.canUseToggle(Toggle.drawDisplayedKnots) && tool.canUseToggle(Toggle.drawMetroDiagram)
					&& shell != null) {
				drawDisplayedKnots(camera);
			}
			if (logo == null) {
				logo = new SDFTexture("decal_sdf_small.png", Color.IXDAR_DARK, 0.6f, 0f, true);
			}
			new SDFCircle().draw(new Vector2f(mouseTrap.normalizedPosX, mouseTrap.normalizedPosY), stickyColor,
					camera3D);
			glViewport(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
			logo.drawRightBound(Canvas3D.frameBufferWidth, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, Color.IXDAR, camera);
			camera3D.setZIndex(camera);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void drawDisplayedKnots(Camera2D camera) {
		if (metroDrawLayer == shell.cutEngine.totalLayers) {

			if (tool.canUseToggle(Toggle.drawKnotGradient) && manifoldKnot != null) {
				ArrayList<Pair<Long, Long>> idTransform = lookupPairs(manifoldKnot);
				Drawing.drawGradientPath(manifoldKnot, idTransform, colorLookup, knotGradientColors, camera,
						Drawing.MIN_THICKNESS);
			} else if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
				for (Shell temp : subPaths) {
					temp.drawShell(true, Drawing.MIN_THICKNESS * 2,
							stickyColor, retTup.ps, camera);
				}
			}
		} else {
			PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
			int size = metroPathsLayer.size();
			for (int i = 0; i < size; i++) {
				ShellPair temp = metroPathsLayer.remove();
				newQueue.add(temp);
				if (metroDrawLayer >= 0 && temp.priority != metroDrawLayer) {
					continue;
				}
				if (metroDrawLayer < 0) {
					if (tool.canUseToggle(Toggle.drawKnotGradient)) {
						ArrayList<Pair<Long, Long>> idTransform = lookupPairs(temp.k);
						Drawing.drawGradientPath(temp.k, idTransform, colorLookup, knotGradientColors,
								camera,
								Drawing.MIN_THICKNESS);
					} else if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
						temp.shell.drawShell(true, 2 + 1f * temp.priority,
								metroColors.get(temp.priority), retTup.ps, camera);
					}
				} else {
					if (tool.canUseToggle(Toggle.drawKnotGradient)) {
						ArrayList<Pair<Long, Long>> idTransform = lookupPairs(temp.k);
						Drawing.drawGradientPath(temp.k, idTransform, colorLookup, knotGradientColors,
								camera,
								Drawing.MIN_THICKNESS);
					} else if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
						temp.shell.drawShell(true, Drawing.MIN_THICKNESS * 2,
								metroColors.get(temp.priority), retTup.ps, camera);
					}

				}
			}
			metroPathsLayer = newQueue;
		}
	}

	public static ArrayList<Pair<Long, Long>> lookupPairs(Knot k) {

		ArrayList<Pair<Long, Long>> idTransform = new ArrayList<>();
		for (int i = 0; i < k.manifoldSegments.size(); i++) {
			Segment s = k.manifoldSegments.get(i);
			VirtualPoint vp1 = s.first;
			VirtualPoint vp2 = s.last;

			Knot smallestKnot1 = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[vp1.id]);

			Knot smallestKnot2 = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[vp2.id]);
			idTransform.add(new Pair<Long, Long>((long) smallestKnot1.id, (long) smallestKnot2.id));
		}
		return idTransform;
	}

	public static void calculateSubPaths() {
		try {

			for (int i = 0; i < result.size(); i++) {
				VirtualPoint vp = result.get(i);
				if (vp.isKnot) {
					System.out.println("Next Knot: " + vp);
					Shell temp = shell.cutKnot((Knot) vp);
					System.out.println("Knot: " + temp + " Length: " + temp.getLength());
					subPaths.add(temp);
				}
				if (vp.isRun) {
					Run run = (Run) vp;
					for (VirtualPoint sub : run.knotPoints) {
						if (sub.isKnot) {
							System.out.println("Next Knot: " + sub);
							Shell temp = shell.cutKnot((Knot) sub);
							subPaths.add(temp);
							System.out.println("Knot: " + temp + " Length: " + temp.getLength());
						}

					}
				}
			}
		} catch (SegmentBalanceException sbe) {
			segmentBalanceExceptionHandler(sbe);
		}
	}

	public static void segmentBalanceExceptionHandler(SegmentBalanceException sbe) {
		Shell result = new Shell();
		for (VirtualPoint p : sbe.topKnot.knotPoints) {
			result.add(((Point) p).p);
		}
		shell.buff.printLayer(0);
		System.out.println();
		System.out.println(sbe);
		// StackTraceElement ste = sbe.getStackTrace()[0];
		for (StackTraceElement ste : sbe.getStackTrace()) {
			if (ste.getMethodName().equals("cutKnot")) {
				break;
			}
			System.out.println("ErrorSource: " + ste.getMethodName() + " " + ste.getFileName() + ":"
					+ ste.getLineNumber());
		}
		System.out.println();
		resultShell = result;
	}

	public static void updateKnotsDisplayed() {
		PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
		PriorityQueue<ShellPair> metroPathsLayer = Main.metroPathsLayer;
		int size = metroPathsLayer.size();
		knotsDisplayed = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			ShellPair temp = metroPathsLayer.remove();
			if (temp.priority == metroDrawLayer) {
				knotsDisplayed.add(temp.k);
			}
			newQueue.add(temp);
		}
		Main.metroPathsLayer = newQueue;
	}

	public static void activate(boolean state) {
		active = state;
		mouseTrap.active = state;
		keys.active = state;
	}

}

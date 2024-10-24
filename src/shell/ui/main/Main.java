package shell.ui.main;

import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
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

import shell.DistanceMatrix;
import shell.PointND;
import shell.Toggle;
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
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.color.ColorBox;
import shell.render.color.ColorRGB;
import shell.render.sdf.SDFCircle;
import shell.render.sdf.SDFTexture;
import shell.render.shaders.ShaderProgram;
import shell.render.text.Font;
import shell.render.text.HyperString;
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
	public static HashMap<Long, Integer> metroColorLookup = new HashMap<>();
	public static ArrayList<Color> knotGradientColors = new ArrayList<>();
	public static HashMap<Long, Integer> colorLookup = new HashMap<>();
	public static boolean active;
	public Canvas canvas;
	public static KeyGuy keys;
	public static MouseTrap mouse;
	private static HyperString toolTip;
	public SDFTexture logo;
	public Font font;

	final static int RIGHT_PANEL_SIZE = 195;
	final static int BOTTOM_PANEL_SIZE = 195;
	public static int MAIN_VIEW_OFFSET_X;
	public static int MAIN_VIEW_OFFSET_Y;
	public static int MAIN_VIEW_WIDTH;
	public static int MAIN_VIEW_HEIGHT;
	public static HyperString toolInfo;
	private static boolean showToolTip;

	public Main(String fileName) {
		Main.fileName = fileName;
		file = FileManagement.getTestFile(fileName);
		retTup = FileManagement.importFromFile(file);
		IxdarWindow.setTitle("Ixdar : " + fileName);

		int wWidth = (int) IxdarWindow.getWidth();
		int wHeight = (int) IxdarWindow.getHeight();
		camera = new Camera2D(wWidth - RIGHT_PANEL_SIZE,
				wHeight - BOTTOM_PANEL_SIZE, 0.9f, 0, BOTTOM_PANEL_SIZE,
				retTup.ps);

		Toggle.manifold.value = !retTup.manifolds.isEmpty();

		keys = new KeyGuy(this, fileName, camera);
		mouse = new MouseTrap(this, camera, false);
		activate(true);
		tool = new FreeTool();
	}

	public static void main(String[] args) {
		main = new Main(args[0]);

		int wWidth = (int) IxdarWindow.getWidth();
		int wHeight = (int) IxdarWindow.getHeight();
		MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
		MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
		MAIN_VIEW_OFFSET_X = 0;
		MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
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
			metroColorLookup.put((long) k.id, totalLayers - layerNum);
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
		try {
			int wWidth = (int) IxdarWindow.getWidth();
			int wHeight = (int) IxdarWindow.getHeight();
			MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
			MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
			MAIN_VIEW_OFFSET_X = 0;
			MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
			updateView(MAIN_VIEW_OFFSET_X, MAIN_VIEW_OFFSET_Y, MAIN_VIEW_WIDTH, MAIN_VIEW_HEIGHT);
			float SHIFT_MOD = 1;
			if (keys != null && keys.pressedKeys.contains(KeyEvent.VK_SHIFT)) {
				SHIFT_MOD = 2;
			}
			if (keys != null) {
				keys.paintUpdate(SHIFT_MOD);
			}
			if (mouse != null) {
				mouse.paintUpdate(SHIFT_MOD);
			}
			camera.setZIndex(camera3D);
			camera.calculateCameraTransform();

			tool.setScreenOffset(camera);
			tool.draw(camera, Drawing.MIN_THICKNESS);
			new SDFCircle().draw(new Vector2f(mouse.normalizedPosX - camera.ScreenOffsetX,
					mouse.normalizedPosY - camera.ScreenOffsetY), Drawing.CIRCLE_RADIUS,
					stickyColor,
					camera);
			if (sbe != null) {
				Drawing.drawShell(resultShell, true, Drawing.MIN_THICKNESS * 2, Color.MAGENTA, retTup.ps,
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
				Drawing.drawShell(orgShell, false, Drawing.MIN_THICKNESS, Color.BLUE, retTup.ps, camera);
			}
			if (tool.canUseToggle(Toggle.drawDisplayedKnots) && tool.canUseToggle(Toggle.drawMetroDiagram)
					&& shell != null) {
				drawDisplayedKnots(camera);
			}
			if (logo == null) {
				logo = new SDFTexture("decal_sdf_small.png", Color.IXDAR_DARK, 0.6f, 0f, true);
			}
			updateView(wWidth - RIGHT_PANEL_SIZE, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE);
			logo.draw(0, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, Color.IXDAR, camera);

			updateView(wWidth - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, RIGHT_PANEL_SIZE,
					wHeight - BOTTOM_PANEL_SIZE);
			new SDFCircle().draw(new Vector2f(mouse.normalizedPosX - camera.ScreenOffsetX,
					mouse.normalizedPosY - camera.ScreenOffsetY), Drawing.CIRCLE_RADIUS,
					Color.RED,
					camera);
			int row = 0;
			float rowHeight = Drawing.FONT_HEIGHT_PIXELS;

			Drawing.font.drawRow("FPS:" + Clock.fps(), row++, tool.scrollOffsetY, rowHeight, 0, Color.IXDAR, camera);
			Drawing.font.drawRow("Tool: " + tool.displayName(), row++, tool.scrollOffsetY, rowHeight, 0, Color.IXDAR,
					camera);

			toolInfo = tool.info();
			Drawing.font.drawHyperStringRows(toolInfo, row, tool.scrollOffsetY, rowHeight, camera);
			row += toolInfo.lines;
			if (toolTip != null && showToolTip) {
				int isRight = mouse.normalizedPosX > wWidth / 2 ? 1 : 0;
				int toolTipWidth = toolTip.getWidthPixels();

				int isTop = mouse.normalizedPosY > wHeight / 2 ? 1 : 0;
				int toolTipHeight = toolTip.getHeightPixels();
				updateView((int) mouse.normalizedPosX - (isRight * toolTipWidth),
						(int) mouse.normalizedPosY - (isTop * toolTipHeight),
						toolTip.getWidthPixels(),
						toolTip.lines * (int) Drawing.FONT_HEIGHT_PIXELS);
				new ColorBox().draw(Color.BLUE, camera);
			}
			camera3D.setZIndex(camera);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void updateView(int x, int y, int width, int height) {
		camera.updateViewBounds(x, y, width, height);
		glViewport(x, y, width, height);
		for (ShaderProgram s : Canvas3D.shaders) {
			s.updateProjectionMatrix(width, height, 1f);
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
					Drawing.drawShell(temp, true, Drawing.MIN_THICKNESS,
							metroColors.get(0), retTup.ps, camera);
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
						Drawing.drawShell(temp.shell, true,
								Drawing.MIN_THICKNESS + Drawing.MIN_THICKNESS * (temp.priority - 1),
								metroColors.get(temp.priority), retTup.ps, camera);
					}
				} else {
					if (tool.canUseToggle(Toggle.drawKnotGradient)) {
						ArrayList<Pair<Long, Long>> idTransform = lookupPairs(temp.k);
						Drawing.drawGradientPath(temp.k, idTransform, colorLookup, knotGradientColors,
								camera,
								Drawing.MIN_THICKNESS);
					} else if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
						Drawing.drawShell(temp.shell, true, Drawing.MIN_THICKNESS,
								metroColors.get(temp.priority), retTup.ps, camera);
					}

				}
			}
			metroPathsLayer = newQueue;
		}
		for (Knot k : knotsDisplayed) {
			if (k.s1 != null && k.s2 != null) {

				Segment s1 = k.s1;
				if (!k.contains(s1.last)) {
					s1 = new Segment(s1.last, s1.first, s1.distance);
				}
				Segment s2 = k.s2;
				if (!k.contains(s2.last)) {
					s2 = new Segment(s2.last, s2.first, s2.distance);
				}

				Color c = Color.WHITE;
				if (tool.canUseToggle(Toggle.drawKnotGradient)) {
					c = getKnotGradientColor(s1.last);
				} else if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
					c = getMetroColor(s1.last, k);
				}
				Drawing.drawDashedSegment(s1, c, camera);
				Drawing.drawDashedSegment(s2, c, camera);
			}
		}
	}

	public static Color getKnotGradientColor(VirtualPoint displayPoint) {
		Knot smallestKnot = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[displayPoint.id]);
		return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
	}

	public static Color getKnotGradientColorFlatten(Knot k) {
		Knot smallestKnot = shell.cutEngine.flatKnots.get(shell.cutEngine.knotToFlatKnot.get(k.id));
		return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
	}

	public static Color getMetroColor(VirtualPoint displayPoint, Knot k) {
		if (metroDrawLayer < 0) {
			Knot smallestKnot = shell.cutEngine.flatKnots.get(shell.smallestKnotLookup[displayPoint.id]);
			return metroColors.get(metroColorLookup.get((long) smallestKnot.id));
		} else {
			return metroColors.get(metroColorLookup.get((long) k.id));
		}
	}

	public static Color getMetroColorFlatten(Knot thickKnot) {
		Knot smallestKnot = shell.cutEngine.flatKnots.get(shell.cutEngine.knotToFlatKnot.get(thickKnot.id));
		return metroColors.get(metroColorLookup.get((long) smallestKnot.id));
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
		if (state) {
			glfwSetKeyCallback(IxdarWindow.window,
					(window, key, scancode, action, mods) -> keys.keyCallback(window, key, scancode, action, mods));

			glfwSetMouseButtonCallback(IxdarWindow.window,
					(window, button, action, mods) -> mouse.clickCallback(window, button, action, mods));

			glfwSetCursorPosCallback(IxdarWindow.window, (window, x, y) -> mouse.moveCallback(window, x, y));

			glfwSetScrollCallback(IxdarWindow.window, (window, x, y) -> mouse.mouseScrollCallback(window, y));
		}
		active = state;
		mouse.active = state;
		keys.active = state;
	}

	public static void setTooltipText(HyperString pointInfo) {
		toolTip = pointInfo;
		showToolTip = true;

	}

	public static void clearTooltipText() {
		toolTip = null;
		showToolTip = false;
	}

	public static MainPanel inView(float x, float y) {
		boolean inMainViewRightBound = x < Main.MAIN_VIEW_WIDTH + Main.MAIN_VIEW_OFFSET_X;
		boolean inMainViewLeftBound = x > Main.MAIN_VIEW_OFFSET_X;
		float invY = IxdarWindow.getHeight() - y;
		boolean inMainViewLowerBound = invY > Main.MAIN_VIEW_OFFSET_Y;
		boolean inMainViewUpperBound = invY < Main.MAIN_VIEW_HEIGHT + Main.MAIN_VIEW_OFFSET_Y;
		if (inMainViewLeftBound && inMainViewRightBound && inMainViewLowerBound
				&& inMainViewUpperBound) {
			return MainPanel.KnotView;
		} else if (!inMainViewLowerBound && !inMainViewRightBound) {
			return MainPanel.Logo;
		} else if (!inMainViewLowerBound && inMainViewRightBound && inMainViewLeftBound) {
			return MainPanel.Terminal;
		} else if (inMainViewLowerBound && inMainViewUpperBound && !inMainViewRightBound) {
			return MainPanel.Info;
		}
		return MainPanel.None;
	}
}

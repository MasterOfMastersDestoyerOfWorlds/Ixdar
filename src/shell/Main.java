package shell;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.apache.commons.lang3.ObjectUtils.Null;

import shell.cuts.CutEngine;
import shell.cuts.CutInfo;
import shell.cuts.CutMatchList;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Run;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.route.RouteInfo;
import shell.route.RoutePair;
import shell.shell.Shell;
import shell.shell.ShellPair;
import shell.shell.ShellComparator;
import shell.ui.Camera;
import shell.ui.Drawing;
import shell.ui.FileManagement;
import shell.ui.PointSetPath;
import shell.ui.PrintScreenAction;
import shell.ui.SaveAction;

public class Main extends JComponent implements KeyListener, MouseListener, MouseWheelListener {

	private static final long serialVersionUID = 2722424842956800923L;
	public static ArrayList<VirtualPoint> result;

	static boolean calculateKnot = true;
	boolean drawMainPath = false;
	static boolean drawMetroDiagram = true;
	static boolean drawMetroDiagram2 = true;
	static boolean startWithAnswer = true;
	int minLineThickness = 1;
	boolean calc = false;
	static boolean manifold = false;
	static boolean drawCutMatch = true;

	public static Shell shell;
	public static PointSetPath retTup;
	public static Shell orgShell;
	public static ArrayList<Shell> subPaths = new ArrayList<>();
	static PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
	static PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
	public static ArrayList<Color> metroColors = new ArrayList<>();
	public static ArrayList<Color> metro2Colors = new ArrayList<>();
	public static HashMap<Integer, Integer> colorLookup = new HashMap<>();
	static int metroDrawLayer = -1;
	static SegmentBalanceException drawException;
	static Shell resultShell;
	static JFrame frame;
	static Main main;
	static File currFile;
	static String fileName;
	private static Color stickyColor;
	int queuedMouseWheelTicks = 0;
	Camera camera;
	static CutMatchList manifoldCutMatch;
	private static Knot manifoldKnot;
	private static Segment manifoldCutSegment1;
	private static Segment manifoldCutSegment2;
	private static Segment manifoldExSegment1;
	private static Segment manifoldExSegment2;
	private static BalanceMap manifoldBalanceMap;

	boolean init;

	public Main() {

		fileName = "djbouti_8-34-manifold";
		currFile = FileManagement.getTestFile(fileName);
		retTup = FileManagement.importFromFile(currFile);
		frame = new JFrame("Ixdar : " + fileName);
		ImageIcon img = new ImageIcon("decalSmall.png");
		frame.setIconImage(img.getImage());
		frame.getContentPane().add(this);

		frame.getContentPane().setBackground(new Color(20, 20, 20));
		frame.pack();
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		camera = new Camera(750, 750, 1, 0, 0, retTup.ps);
		frame.setSize(new Dimension(camera.Width, camera.Height));
		frame.setVisible(true);
		frame.addKeyListener(this);
		frame.addMouseListener(this);
		frame.addMouseWheelListener(this);

		JRootPane rootPane = frame.getRootPane();
		rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
				KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK),
				"printScreen");
		rootPane.getActionMap().put("printScreen",
				new PrintScreenAction(frame));

		rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
				KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
				"saveNew");
		rootPane.getActionMap().put("saveNew",
				new SaveAction(frame, fileName));
		init = true;
	}

	// cut 5-3 and 2-0 or 18-16 and 15-13
	public static void main(String[] args) {
		main = new Main();

		DistanceMatrix d = retTup.d;
		if (retTup.d == null) {
			d = new DistanceMatrix(retTup.ps);
		}
		float z1 = 0;
		ArrayList<PointND> toRemove = new ArrayList<>();
		for (PointND p : retTup.tsp) {
			if (p == null) {
				toRemove.add(p);
			}
		}
		retTup.tsp.removeAll(toRemove);
		if (retTup.manifold) {
			manifold = true;
			calculateKnot = false;
		}
		orgShell = retTup.tsp;

		System.out.println(orgShell.getLength());

		shell = orgShell.copyShallow();

		shell.knotName = fileName;

		Collections.shuffle(shell);
		System.out.println(shell);
		long startTimeKnotFinding = System.currentTimeMillis();
		if (manifold) {

			result = new ArrayList<>(shell.slowSolve(shell, d, 10));
			if (result.size() > 1) {
				manifold = false;
				calculateKnot = true;
			}
			calculateSubPaths();
			Shell top = subPaths.get(0);
			manifoldKnot = shell.cutEngine.flatKnots.values().iterator().next();

			for (Knot f : shell.cutEngine.flatKnots.values()) {
				if (f.knotPointsFlattened.size() > manifoldKnot.size()) {
					manifoldKnot = f;
				}
			}
			PointND wormHole = d.addDummyNode(d.size(), retTup.ps.getByID(retTup.kp1), retTup.ps.getByID(retTup.kp2));
			VirtualPoint knotPoint1 = shell.pointMap.get(retTup.kp1);
			VirtualPoint cutPoint1 = shell.pointMap.get(retTup.cp1);
			VirtualPoint knotPoint2 = shell.pointMap.get(retTup.kp2);
			VirtualPoint cutPoint2 = shell.pointMap.get(retTup.cp2);
			manifoldCutSegment1 = manifoldKnot.getSegment(knotPoint1, cutPoint1);
			manifoldCutSegment2 = manifoldKnot.getSegment(knotPoint2, cutPoint2);
			manifoldExSegment1 = manifoldKnot.getSegment(knotPoint1, knotPoint1);
			manifoldExSegment2 = manifoldKnot.getSegment(knotPoint2, knotPoint2);

			VirtualPoint external1 = knotPoint1;
			VirtualPoint external2 = knotPoint2;
			CutInfo c1 = new CutInfo(shell, knotPoint1, cutPoint1, manifoldCutSegment1, external1,
					knotPoint2,
					cutPoint2, manifoldCutSegment2,
					external2, manifoldKnot, null);
			SegmentBalanceException sbe12 = new SegmentBalanceException(shell, null, c1);
			manifoldBalanceMap = new BalanceMap(manifoldKnot, sbe12);
			try {
				manifoldBalanceMap.addCut(knotPoint1, cutPoint1);
				manifoldBalanceMap.addCut(knotPoint2, cutPoint2);
				manifoldBalanceMap.addExternalMatch(knotPoint1, knotPoint1, null);
				manifoldBalanceMap.addExternalMatch(knotPoint2, knotPoint2, null);
				c1.balanceMap = manifoldBalanceMap;
				manifoldCutMatch = shell.cutEngine.internalPathEngine.calculateInternalPathLength(
						knotPoint1, cutPoint1, external1,
						knotPoint2, cutPoint2, external2, manifoldKnot, manifoldBalanceMap, c1, true);
			} catch (SegmentBalanceException sbe) {
				segmentBalanceExceptionHandler(sbe);
			}
		}
		if (calculateKnot) {
			result = new ArrayList<>(shell.slowSolve(shell, d, 10));
		}
		shell.buff.flush();
		long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
		double knotFindingSeconds = ((double) endTimeKnotFinding) / 1000.0;

		long startTimeKnotCutting = System.currentTimeMillis();

		Random colorSeed = new Random();
		if (drawMetroDiagram2) {
			int numKnots = shell.cutEngine.flatKnots.size();
			float startHue = colorSeed.nextFloat();
			float step = 1.0f / ((float) numKnots);
			int i = 0;
			for (Knot k : shell.cutEngine.flatKnots.values()) {
				metro2Colors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
				colorLookup.put(k.id, i);
				i++;
			}
		}
		if (drawMetroDiagram) {
			int totalLayers = shell.cutEngine.totalLayers;
			if (totalLayers == -1) {
				calculateSubPaths();
				totalLayers = shell.cutEngine.totalLayers;
			}
			if (startWithAnswer) {
				metroDrawLayer = totalLayers;
			}
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
				if (totalLayers - layerNum < 0) {
					float z = 0;
				}
				metroPathsHeight.add(new ShellPair(knotShell, k, heightNum));
				metroPathsLayer.add(new ShellPair(knotShell, k, totalLayers - layerNum));
			}
			float startHue = colorSeed.nextFloat();
			float step = 1.0f / ((float) totalLayers);
			for (int i = 0; i <= totalLayers; i++) {
				metroColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
			}
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

		System.out.println("comparisons " + String.format("%,d", shell.cutEngine.internalPathEngine.comparisons));
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

		if(manifold){
			System.out.println("Manifold Length: " + (orgShell.getLength() + manifoldCutMatch.delta));

		}
		System.out.println(shell.cutEngine.flatKnots);
		stickyColor = new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
		stickyColor = Color.CYAN;
		frame.repaint();

	}

	@Override
	public void paint(Graphics g) {
		if (init && retTup != null) {
			initCamera();
		}
		double SHIFT_MOD = 1;
		if (pressedKeys.contains(KeyEvent.VK_SHIFT)) {
			SHIFT_MOD = 2;
		}
		if (!pressedKeys.isEmpty()) {
			for (Iterator<Integer> it = pressedKeys.iterator(); it.hasNext();) {
				switch (it.next()) {
					case KeyEvent.VK_W:
					case KeyEvent.VK_UP:
						camera.PanY += 25 * SHIFT_MOD;
						break;
					case KeyEvent.VK_A:
					case KeyEvent.VK_LEFT:
						camera.PanX += 25 * SHIFT_MOD;
						break;
					case KeyEvent.VK_S:
					case KeyEvent.VK_DOWN:
						camera.PanY -= 25 * SHIFT_MOD;
						break;
					case KeyEvent.VK_D:
					case KeyEvent.VK_RIGHT:
						camera.PanX -= 25 * SHIFT_MOD;
						break;
					case KeyEvent.VK_EQUALS:
						camera.ScaleFactor += 0.05 * SHIFT_MOD;
						break;
					case KeyEvent.VK_MINUS:
						camera.ScaleFactor -= 0.05 * SHIFT_MOD;
						break;
					case KeyEvent.VK_R:
						camera.ScaleFactor = 1;
						camera.PanX = camera.defaultPanX;
						camera.PanY = camera.defaultPanY;
						break;
				}
			}
		}
		if (queuedMouseWheelTicks < 0) {
			camera.ScaleFactor += 0.05 * SHIFT_MOD;
			queuedMouseWheelTicks++;
		}
		if (queuedMouseWheelTicks > 0) {
			camera.ScaleFactor -= 0.05 * SHIFT_MOD;
			queuedMouseWheelTicks--;
		}
		try {
			Graphics2D g2 = (Graphics2D) g;
			BufferedImage img = ImageIO.read(new File("decal.png"));
			double height = SwingUtilities.getWindowAncestor(this).getHeight(),
					width = SwingUtilities.getWindowAncestor(this).getWidth();
			g.drawImage(img, ((int) width) - (int) (width / 3.5), ((int) height) - (int) (height / 3.5), 150, 150,
					null);
			if (drawException != null) {
				resultShell.drawShell(this, g2, true, minLineThickness * 2, Color.magenta, retTup.ps, camera);
				Drawing.drawCutMatch(this, g2, drawException, minLineThickness * 2, retTup.ps, camera);
			}
			if (!(retTup == null)) {
				Drawing.drawPath(this, g2, retTup.path, minLineThickness, Color.RED, retTup.ps, false, false, true,
						false,
						camera);
			}
			if (drawCutMatch && manifold && manifoldCutMatch != null) {
				Drawing.drawCutMatch(this, g2, manifoldCutMatch, manifoldBalanceMap, manifoldCutSegment1,
						manifoldCutSegment2, manifoldExSegment1, manifoldExSegment2,
						manifoldKnot, minLineThickness * 2, retTup.ps, camera);
			}
			if (drawMainPath) {
				orgShell.drawShell(this, g2, false, minLineThickness, Color.BLUE, retTup.ps, camera);
			}
			if (drawMetroDiagram && shell != null) {
				if (metroDrawLayer == shell.cutEngine.totalLayers) {

					if (drawMetroDiagram2 && manifold) {
						Drawing.drawGradientPath(g2, manifoldKnot, shell, camera, minLineThickness);
					} else {
						for (Shell temp : subPaths) {
							temp.drawShell(this, g2, true, minLineThickness * 2,
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
							if (drawMetroDiagram2) {
								Drawing.drawGradientPath(g2, temp.k, shell, camera, minLineThickness);
							} else {
								temp.shell.drawShell(this, g2, true, 2 + 1f * temp.priority,
										metroColors.get(temp.priority), retTup.ps, camera);
							}
						} else {
							if (drawMetroDiagram2) {
								Drawing.drawGradientPath(g2, temp.k, shell, camera, minLineThickness);
							} else {
								temp.shell.drawShell(this, g2, true, minLineThickness * 2,
										metroColors.get(temp.priority), retTup.ps, camera);
							}

						}
					}
					metroPathsLayer = newQueue;
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			SwingUtilities.getWindowAncestor(this)
					.dispatchEvent(new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
		}
		if (!pressedKeys.isEmpty()) {
			frame.repaint();
		}

	}

	private void initCamera() {
		init = false;
		double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
		double meanX = 0, meanY = 0, numPoints = 0;
		for (PointND pn : retTup.ps) {
			if (!pn.isDummyNode()) {
				Point2D p = pn.toPoint2D();

				if (p.getX() < minX) {
					minX = p.getX();
				}
				if (p.getY() < minY) {
					minY = p.getY();
				}
				meanX += p.getX();
				if (p.getX() > maxX) {
					maxX = p.getX();
				}
				if (p.getY() > maxY) {
					maxY = p.getY();
				}
				meanY += p.getY();
				numPoints++;
			}
		}
		meanX = meanX / numPoints;
		meanY = meanY / numPoints;
		int count = 0, offsetx = 0, offsety = 0;

		double height = 750,
				width = 750;
		double rangeX = maxX - minX, rangeY = maxY - minY;
		if (rangeX > rangeY) {
			offsetx += (((double) rangeY) / ((double) rangeX) * height / 2);
			rangeY = rangeX;

		} else {
			offsety += (((double) rangeX) / ((double) rangeY) * width / 2);
			rangeX = rangeY;
		}
		camera.PanX = 50 - offsety;
		camera.PanY = 50 - offsetx;
		camera.defaultPanX = camera.PanX;
		camera.defaultPanY = camera.PanY;
	}

	private final Set<Integer> pressedKeys = new HashSet<>();

	@Override
	public void keyPressed(KeyEvent e) {
		pressedKeys.add(e.getKeyCode());

		frame.repaint();
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	public void keyReleased(KeyEvent e) {
		pressedKeys.remove(e.getKeyCode());
		if (e.getKeyCode() == KeyEvent.VK_C) {
			Random colorSeed = new Random();
			stickyColor = new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
			if (drawMetroDiagram) {
				metroColors = new ArrayList<>();
				int totalLayers = shell.cutEngine.totalLayers;
				float startHue = colorSeed.nextFloat();
				float step = 1.0f / ((float) totalLayers);
				for (int i = 0; i <= totalLayers; i++) {
					metroColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
				}
			}
			float startHue = colorSeed.nextFloat();
			float step = 1.0f / ((float) shell.cutEngine.flatKnots.size());
			for (int i = 0; i < metro2Colors.size(); i++) {
				metro2Colors.set(i, Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_B) {
			drawCutMatch = !drawCutMatch;
		}
		if (e.getKeyCode() == KeyEvent.VK_N) {
			drawMetroDiagram2 = !drawMetroDiagram2;
		}
		if (e.getKeyCode() == KeyEvent.VK_M) {
			if (metroDrawLayer != -1) {
				metroDrawLayer = -1;
			} else {
				metroDrawLayer = shell.cutEngine.totalLayers;
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_CLOSE_BRACKET) {
			metroDrawLayer++;
			if (metroDrawLayer > shell.cutEngine.totalLayers) {
				metroDrawLayer = shell.cutEngine.totalLayers;
			}
			if (metroDrawLayer < 1) {
				metroDrawLayer = 1;
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_OPEN_BRACKET) {
			if (metroDrawLayer == -1) {
				metroDrawLayer = shell.cutEngine.totalLayers;
			} else {
				metroDrawLayer--;
				if (metroDrawLayer < 1) {
					metroDrawLayer = 1;
				}
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_O) {
			drawMainPath = !drawMainPath;
		}
		if (e.getKeyCode() == KeyEvent.VK_U) {
			if (subPaths.size() == 1) {
				Shell ans = subPaths.get(0);
				if (orgShell.getLength() > ans.getLength()) {
					FileManagement.appendAns(currFile, ans);
					orgShell = ans;
				}
			}
		}
		if (e.getKeyCode() == KeyEvent.VK_ENTER) {
			calculateSubPaths();
		}
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		System.out.println("Click: " + e.getX() + " , " + e.getY());
	}

	@Override
	public void mousePressed(MouseEvent e) {
		System.out.println("Holding: " + e.getX() + " , " + e.getY());
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		System.out.println("Released: " + e.getX() + " , " + e.getY());
	}

	@Override
	public void mouseEntered(MouseEvent e) {
	}

	@Override
	public void mouseExited(MouseEvent e) {
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		queuedMouseWheelTicks += e.getWheelRotation();
		repaint();
	}

	private static void calculateSubPaths() {

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
		drawException = sbe;
	}
}

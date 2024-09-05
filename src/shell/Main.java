package shell;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.ComponentOrientation;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
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
import javax.swing.JButton;
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
import shell.file.FileManagement;
import shell.file.Manifold;
import shell.file.PointSetPath;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Run;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.route.RouteInfo;
import shell.shell.Shell;
import shell.shell.ShellPair;
import shell.shell.ShellComparator;
import shell.ui.Camera;
import shell.ui.Drawing;
import shell.ui.KeyGuy;
import shell.ui.Logo;
import shell.ui.MouseTrap;
import shell.ui.actions.FindManifoldAction;
import shell.ui.actions.GenerateManifoldTestsAction;
import shell.ui.actions.PrintScreenAction;
import shell.ui.actions.SaveAction;
import shell.ui.tools.FindManifoldTool;
import shell.ui.tools.FreeTool;

public class Main extends JComponent {

	private static final long serialVersionUID = 2722424842956800923L;
	public static ArrayList<VirtualPoint> result;
	public static Toggle calculateKnot = new Toggle(true, ToggleType.CalculateKnot);
	public static Toggle drawMainPath = new Toggle(false, ToggleType.DrawMainPath);
	public static Toggle drawMetroDiagram = new Toggle(true, ToggleType.DrawMetroDiagram);
	public static Toggle drawKnotGradient = new Toggle(true, ToggleType.DrawKnotGradient);
	static boolean startWithAnswer = true;
	int minLineThickness = 1;
	boolean calc = false;
	public static boolean manifold = false;
	public static Toggle drawCutMatch = new Toggle(true, ToggleType.DrawCutMatch);
	public static FreeTool freeTool = new FreeTool();

	public static Shell shell;
	public static PointSetPath retTup;
	public static Shell orgShell;
	public static ArrayList<Shell> subPaths = new ArrayList<>();
	static PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
	static PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
	public static ArrayList<Color> metroColors = new ArrayList<>();
	public static ArrayList<Color> knotGradientColors = new ArrayList<>();
	public static HashMap<Integer, Integer> colorLookup = new HashMap<>();
	public static int metroDrawLayer = -1;
	static SegmentBalanceException drawException;
	static Shell resultShell;
	static JFrame frame;
	static Main main;
	public static File file;
	static String fileName;
	public static Color stickyColor;
	public static Camera camera;
	static MouseTrap mouse;
	static KeyGuy keys;
	public static Knot manifoldKnot;
	public static int manifoldIdx = 0;
	public static ArrayList<Manifold> manifolds;
	public static Tool tool;

	public Main() {

		fileName = "rings_3";
		file = FileManagement.getTestFile(fileName);
		retTup = FileManagement.importFromFile(file);
		frame = new JFrame("Ixdar : " + fileName);

		ImageIcon img = new ImageIcon("decalSmall.png");
		frame.setIconImage(img.getImage());
		Container pane = frame.getContentPane();
		pane.setBackground(new Color(20, 20, 20));

		pane.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
		pane.setLayout(new GridBagLayout());

		GridBagConstraints mConstraints = new GridBagConstraints();
		mConstraints.gridx = 1;
		mConstraints.gridy = 1;
		mConstraints.fill = GridBagConstraints.HORIZONTAL;
		mConstraints.anchor = GridBagConstraints.LAST_LINE_END;
		mConstraints.ipady = 10;
		mConstraints.ipady = 10;
		Logo logo = new Logo();
		pane.add(logo, mConstraints);
		// pane.add(this, mConstraints);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1.0;
		c.weighty = 1.0;
		pane.add(this, c);
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		camera = new Camera(600, 600, 0.9, 0, 0, retTup.ps);
		frame.setVisible(true);
		this.setVisible(true);
		this.setEnabled(true);
		this.setMinimumSize(new Dimension(camera.Width, camera.Height));
		this.setSize(new Dimension(camera.Width, camera.Height));
		this.setPreferredSize(new Dimension(camera.Width, camera.Height));
		pane.setPreferredSize(new Dimension(750, 750));
		pane.setSize(new Dimension(750, 750));

		keys = new KeyGuy(this, frame, fileName, manifold);
		frame.addKeyListener(keys);

		mouse = new MouseTrap(this);
		pane.addMouseListener(mouse);
		pane.addMouseWheelListener(mouse);
		pane.addMouseMotionListener(mouse);

		manifold = !retTup.manifolds.isEmpty();
		frame.pack();

		tool = new FreeTool();

	}

	public static void main(String[] args) {
		main = new Main();

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
		if (manifold) {
			manifold = true;
			calculateKnot.value = false;
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

		if (manifold) {
			if (result.size() > 1) {
				manifold = false;
				calculateKnot.value = true;
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
			colorLookup.put(k.id, i);
			i++;
		}

		int totalLayers = shell.cutEngine.totalLayers;
		if (totalLayers == -1) {
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

		System.out.println(shell.cutEngine.flatKnots);
		stickyColor = new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
		stickyColor = Color.CYAN;
		frame.repaint();

	}

	@Override
	public void paint(Graphics g) {
		if (Main.main != null) {
			camera.updateSize(this.getWidth(), this.getHeight());
			double SHIFT_MOD = 1;
			if (keys != null && keys.pressedKeys.contains(KeyEvent.VK_SHIFT)) {
				SHIFT_MOD = 2;
			}
			if (keys != null) {
				keys.paintUpdate(SHIFT_MOD);
			}
			if (mouse != null) {
				mouse.paintUpdate(SHIFT_MOD);
			}
			try {
				Graphics2D g2 = (Graphics2D) g;
				camera.calculateCameraTransform();
				tool.draw(g2, camera, minLineThickness);

				if (drawException != null) {
					resultShell.drawShell(this, g2, true, minLineThickness * 2, Color.magenta, retTup.ps, camera);
					Drawing.drawCutMatch(this, g2, drawException, minLineThickness * 2, retTup.ps, camera);
				}
				if (!(retTup == null)) {
					Drawing.drawPath(this, g2, retTup.path, minLineThickness, Color.RED, retTup.ps, false, false, true,
							false,
							camera);
				}
				if (tool.canUseToggle(drawCutMatch) && manifold && manifolds != null
						&& manifolds.get(manifoldIdx).cutMatchList != null) {
					Manifold m = manifolds.get(manifoldIdx);
					Drawing.drawCutMatch(this, g2, m.cutMatchList, null, m.manifoldCutSegment1,
							m.manifoldCutSegment2, m.manifoldExSegment1, m.manifoldExSegment2,
							m.manifoldKnot, minLineThickness * 2, retTup.ps, camera);
				}
				if (tool.canUseToggle(drawMainPath)) {
					orgShell.drawShell(this, g2, false, minLineThickness, Color.BLUE, retTup.ps, camera);
				}
				if (tool.canUseToggle(drawMetroDiagram) && shell != null) {
					if (metroDrawLayer == shell.cutEngine.totalLayers) {

						if (tool.canUseToggle(drawKnotGradient) && manifoldKnot != null) {
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
								if (tool.canUseToggle(drawKnotGradient)) {
									Drawing.drawGradientPath(g2, temp.k, shell, camera, minLineThickness);
								} else {
									temp.shell.drawShell(this, g2, true, 2 + 1f * temp.priority,
											metroColors.get(temp.priority), retTup.ps, camera);
								}
							} else {
								if (tool.canUseToggle(drawKnotGradient)) {
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
						.dispatchEvent(
								new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
			}
		}

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
		drawException = sbe;
	}

}

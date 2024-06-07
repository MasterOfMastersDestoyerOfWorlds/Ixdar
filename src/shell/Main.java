package shell;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The main class that facilitates running our tsp solver
 */
public class Main extends JComponent {

	private static final long serialVersionUID = 2722424842956800923L;
	private static final boolean SCALE = false;
	private static final int WIDTH = 750, HEIGHT = 750;
	public static ArrayList<VirtualPoint> result;

	static boolean calculateKnot = true;
	static boolean drawSubPaths = true;
	boolean drawMainPath = true;
	int minLineThickness = 1;
	boolean calc = false;

	public static Shell shell = null;
	public static Shell maxShell;
	public static PointSetPath retTup;
	public static Shell orgShell;
	public static ArrayList<Shell> subPaths = new ArrayList<>();
	static SegmentBalanceException drawException;
	static Shell resultShell;

	/**
	 * Creates the Jframe where the solution is drawn
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		JFrame frame = new JFrame("Ixdar");
		ImageIcon img = new ImageIcon("decalSmall.png");
		frame.setIconImage(img.getImage());
		frame.getContentPane().add(new Main());

		frame.getContentPane().setBackground(new Color(20, 20, 20));
		frame.pack();
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setSize(new Dimension(WIDTH, HEIGHT));
		frame.setVisible(true);

		String fileName = "wi29_5-25x3";
		retTup = importFromFile(new File("./src/test/solutions/" + fileName));
		DistanceMatrix d = new DistanceMatrix(retTup.ps);

		orgShell = retTup.tsp;

		System.out.println(orgShell.getLength());

		maxShell = orgShell.copyShallow();
		shell = maxShell;

		maxShell.knotName = fileName;

		Collections.shuffle(maxShell);
		System.out.println(maxShell);
		long startTimeKnotFinding = System.currentTimeMillis();
		if (calculateKnot) {
			result = new ArrayList<>(maxShell.slowSolve(maxShell, d, 4));
		}
		maxShell.buff.flush();
		long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
		double knotFindingSeconds = ((double) endTimeKnotFinding) / 1000.0;

		long startTimeKnotCutting = System.currentTimeMillis();

		if (drawSubPaths) {
			try {

				for (int i = 0; i < result.size(); i++) {
					VirtualPoint vp = result.get(i);
					if (vp.isKnot) {
						System.out.println("Next Knot: " + vp);
						Shell temp = maxShell.cutKnot((Knot) vp);
						System.out.println("Knot: " + temp + " Length: " + temp.getLength());
						subPaths.add(temp);
					}
					if (vp.isRun) {
						Run run = (Run) vp;
						for (VirtualPoint sub : run.knotPoints) {
							if (sub.isKnot) {
								System.out.println("Next Knot: " + sub);
								Shell temp = maxShell.cutKnot((Knot) sub);
								subPaths.add(temp);
								System.out.println("Knot: " + temp + " Length: " + temp.getLength());
							}

						}
					}
				}
			} catch (SegmentBalanceException sbe) {
				Shell result = new Shell();
				for (VirtualPoint p : sbe.topKnot.knotPoints) {
					result.add(((Point) p).p);
				}
				maxShell.buff.printLayer(0);
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
		long endTimeKnotCutting = System.currentTimeMillis() - startTimeKnotCutting;
		double knotCuttingSeconds = ((double) endTimeKnotCutting) / 1000.0;
		System.out.println(result);
		System.out.println("Knot-finding time: " + knotFindingSeconds);
		System.out.println("Knot-cutting time: " + knotCuttingSeconds);
		System.out.println(
				"Knot-cutting %: " + 100 * (knotCuttingSeconds / (knotCuttingSeconds + knotFindingSeconds)));

		System.out.println("Best Length: " + orgShell.getLength());
		System.out.println("===============================================");
		frame.repaint();

	}

	/**
	 * Creates a visual depiction of the shells/tsp path of the point set
	 * 
	 * @param g
	 */
	@Override
	public void paint(Graphics g) {
		try {
			Graphics2D g2 = (Graphics2D) g;
			BufferedImage img = ImageIO.read(new File("decal.png"));
			g.drawImage(img, WIDTH - (int) (WIDTH / 3.5), HEIGHT - (int) (HEIGHT / 3.5), WIDTH / 5, HEIGHT / 5, null);

			// wi29_6-25: Something is wrong with the difference calculator, it is not
			// cutting the neighbor segments that went unmatched

			if (drawSubPaths) {
				for (Shell temp : subPaths) {
					temp.drawShell(this, g2, true, minLineThickness * 2, null, retTup.ps);
				}
			}
			if (drawException != null) {
				resultShell.drawShell(this, g2, true, minLineThickness * 2, Color.magenta, retTup.ps);
				drawCutMatch(this, g2, drawException, minLineThickness * 2, retTup.ps);
			}

			drawPath(this, g2, retTup.path, minLineThickness, Color.RED, retTup.ps, false, false, true, false);
			if (drawMainPath)
				orgShell.drawShell(this, g2, false, minLineThickness, Color.BLUE, retTup.ps);

		} catch (Exception e) {
			e.printStackTrace();
			SwingUtilities.getWindowAncestor(this)
					.dispatchEvent(new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
		}

	}

	private void drawCutMatch(JComponent frame, Graphics2D g2, SegmentBalanceException sbe, int lineThickness,
			PointSet ps) {

		BasicStroke stroke = new BasicStroke(lineThickness);

		BasicStroke doubleStroke = new BasicStroke(lineThickness * 2);
		g2.setStroke(stroke);

		// Draw x 1

		Font font = new Font("San-Serif", Font.PLAIN, 20);
		g2.setFont(font);

		g2.setColor(Color.RED);

		double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;

		for (PointND pn : ps) {
			if (!pn.isDummyNode()) {
				Point2D p = pn.toPoint2D();

				if (p.getX() < minX) {
					minX = p.getX();
				}
				if (p.getY() < minY) {
					minY = p.getY();
				}
				if (p.getX() > maxX) {
					maxX = p.getX();
				}
				if (p.getY() > maxY) {
					maxY = p.getY();
				}
			}
		}

		double rangeX = maxX - minX, rangeY = maxY - minY;
		double height = SwingUtilities.getWindowAncestor(frame).getHeight(),
				width = SwingUtilities.getWindowAncestor(frame).getWidth();

		if (!SCALE) {
			height = WIDTH;
			width = HEIGHT;
		}

		int offsetx = 100, offsety = 100;

		double[] firstCoords = new double[2];
		double[] lastCoords = new double[2];
		double[] midCoords = new double[2];

		Point2D first = ((Point) sbe.cut1.first).p.toPoint2D();
		Point2D last = ((Point) sbe.cut1.last).p.toPoint2D();

		firstCoords[0] = (-(first.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		firstCoords[1] = (-(first.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		lastCoords[0] = (-(last.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		lastCoords[1] = (-(last.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;
		midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
		midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;
		g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

		g2.setColor(new Color(210, 105, 30));
		// Draw x 2
		first = ((Point) sbe.cut2.first).p.toPoint2D();
		last = ((Point) sbe.cut2.last).p.toPoint2D();

		firstCoords[0] = (-(first.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		firstCoords[1] = (-(first.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		lastCoords[0] = (-(last.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		lastCoords[1] = (-(last.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;
		midCoords[0] = (firstCoords[0] + lastCoords[0]) / 2.0 - 8;
		midCoords[1] = (firstCoords[1] + lastCoords[1]) / 2.0 + 8;

		g2.drawString("X", (int) midCoords[0], (int) midCoords[1]);

		// Draw external segment 1

		Point2D knotPoint1 = ((Point) sbe.ex1.getKnotPoint(sbe.topKnot.knotPointsFlattened)).p.toPoint2D();

		firstCoords[0] = (-(knotPoint1.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		firstCoords[1] = (-(knotPoint1.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		g2.setColor(Color.GREEN);

		g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
		drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords, sbe.ex1);

		// Draw external segment 2

		Point2D knotPoint2 = ((Point) sbe.ex2.getKnotPoint(sbe.topKnot.knotPointsFlattened)).p.toPoint2D();

		firstCoords[0] = (-(knotPoint2.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		firstCoords[1] = (-(knotPoint2.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		g2.setColor(Color.GREEN);

		g2.draw(new Ellipse2D.Double(firstCoords[0] - 5, firstCoords[1] - 5, 10, 10));
		drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords, sbe.ex2);

		// Draw Cuts and Matches
		for (CutMatch cutMatch : sbe.cutMatchList.cutMatches) {

			// Draw Matches
			g2.setColor(Color.CYAN);
			g2.setStroke(stroke);
			for (Segment s : cutMatch.matchSegments) {
				drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords,
						s);
			}

			// Draw Cuts
			g2.setColor(Color.ORANGE);
			g2.setStroke(doubleStroke);
			for (Segment s : cutMatch.cutSegments) {
				drawSegment(g2, minX, minY, rangeX, rangeY, height, width, offsetx, offsety, firstCoords, lastCoords,
						s);
			}
			// Draw SubKnot
			Shell result = new Shell();
			for (VirtualPoint p : cutMatch.knot.knotPoints) {
				result.add(((Point) p).p);
			}
			Main.drawPath(frame, g2, Shell.toPath(result), lineThickness, Color.lightGray, ps, true, false, false,
					true);

		}

	}

	private void drawSegment(Graphics2D g2, double minX, double minY, double rangeX, double rangeY, double height,
			double width, int offsetx, int offsety, double[] firstCoords, double[] lastCoords, Segment s) {
		Point2D first;
		Point2D last;
		if (s.first.isKnot) {
			first = ((Point) ((Knot) s.first).knotPoints.get(0)).p.toPoint2D();
		} else {
			first = ((Point) s.first).p.toPoint2D();
		}
		if (s.last.isKnot) {
			last = ((Point) ((Knot) s.last).knotPoints.get(0)).p.toPoint2D();
		} else {
			last = ((Point) s.last).p.toPoint2D();
		}

		firstCoords[0] = (-(first.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		firstCoords[1] = (-(first.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		lastCoords[0] = (-(last.getX() - minX) * (width) / rangeX + width + offsetx) / 1.5;
		lastCoords[1] = (-(last.getY() - minY) * (height) / rangeY + height + offsety) / 1.5;

		g2.drawLine((int) firstCoords[0], (int) firstCoords[1], (int) lastCoords[0], (int) lastCoords[1]);
	}

	/**
	 * Draws the tsp path of the pointset ps
	 * 
	 * @param frame
	 * @param g2
	 * @param path
	 * @param color
	 * @param ps
	 * @param drawLines
	 * @param drawCircles
	 * @param drawNumbers
	 */
	public static void drawPath(JComponent frame, Graphics2D g2, Path2D path, int lineThickness, Color color,
			PointSet ps,
			boolean drawLines, boolean drawCircles, boolean drawNumbers, boolean dashed) {
		g2.setPaint(color);

		BasicStroke stroke = new BasicStroke(lineThickness);
		if (dashed) {
			stroke = new BasicStroke(lineThickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] { 9 },
					0);
		}
		g2.setStroke(stroke);

		GeneralPath scaledpath = new GeneralPath();
		double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
		boolean first = true;
		for (PointND pn : ps) {
			if (!pn.isDummyNode()) {
				Point2D p = pn.toPoint2D();

				if (p.getX() < minX) {
					minX = p.getX();
				}
				if (p.getY() < minY) {
					minY = p.getY();
				}
				if (p.getX() > maxX) {
					maxX = p.getX();
				}
				if (p.getY() > maxY) {
					maxY = p.getY();
				}
			}
		}

		PathIterator pi = path.getPathIterator(null);
		Point2D start = null;
		double rangeX = maxX - minX, rangeY = maxY - minY;
		double height = SwingUtilities.getWindowAncestor(frame).getHeight(),
				width = SwingUtilities.getWindowAncestor(frame).getWidth();

		if (!SCALE) {
			height = WIDTH;
			width = HEIGHT;
		}

		int count = 0, offsetx = 100, offsety = 100;
		while (!pi.isDone()) {
			double[] coords = new double[2];
			pi.currentSegment(coords);
			pi.next();
			coords[0] = (-(coords[0] - minX) * (width) / rangeX + width + offsetx) / 1.5;
			coords[1] = (-(coords[1] - minY) * (height) / rangeY + height + offsety) / 1.5;
			if (drawCircles) {
				g2.draw(new Ellipse2D.Double(coords[0] - 5, coords[1] - 5, 10, 10));
			}
			if (drawNumbers) {
				Font font = new Font("Serif", Font.PLAIN, 12);
				g2.setFont(font);

				g2.drawString("" + count, (int) coords[0] - 5, (int) coords[1] - 5);
			}
			if (first) {
				scaledpath.moveTo(coords[0], coords[1]);
				first = false;
				start = new Point2D.Double(coords[0], coords[1]);
			} else {
				scaledpath.lineTo(coords[0], coords[1]);
			}

			count++;
		}
		scaledpath.lineTo(start.getX(), start.getY());
		if (drawLines) {
			g2.draw(scaledpath);
		}

	}

	/**
	 * Imports the point set and optimal tsp path from a file
	 * 
	 * @param f
	 * @return the optimal PointSetPath
	 */
	public static PointSetPath importFromFile(File f) {
		try {

			BufferedReader br = new BufferedReader(new FileReader(f));
			ArrayList<PointND> lines = new ArrayList<PointND>();
			String line = br.readLine();
			PointSet ps = new PointSet();
			Path2D path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
			Shell tsp = new Shell();

			boolean flag = true, first = true;
			int index = 0;
			DistanceMatrix d = null;
			HashMap<Integer, PointND> lookUp = new HashMap<>();
			while (line != null) {
				if (flag == true) {
					String[] cords = line.split(" ");
					Point2D pt2d = null;
					if (cords[0].equals("WH")) {
						System.out.println("WORMHOLEFOUND!");
						if (d == null) {
							d = new DistanceMatrix(ps);
						}
						PointND wormHole = d.addDummyNode(lookUp.get(java.lang.Integer.parseInt(cords[1])),
								lookUp.get(java.lang.Integer.parseInt(cords[2])));
						pt2d = wormHole.toPoint2D();
						lines.add(wormHole);
						ps.add(wormHole);
						tsp.add(wormHole);

					} else {
						PointND pt = new PointND.Double(index, java.lang.Double.parseDouble(cords[1]),
								java.lang.Double.parseDouble(cords[2]));
						pt2d = pt.toPoint2D();
						lookUp.put(index, pt);
						lines.add(pt);
						ps.add(pt);
						tsp.add(pt);

						if (first) {
							path.moveTo(pt2d.getX(), pt2d.getY());
							first = false;
						} else {
							path.lineTo(pt2d.getX(), pt2d.getY());
						}

					}

				}
				if (line.contains("NODE_COORD_SECTION")) {
					flag = true;
				}
				line = br.readLine();
				index++;

			}
			br.close();
			return new PointSetPath(ps, path, tsp);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;

	}
}

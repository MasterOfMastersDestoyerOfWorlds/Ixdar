package shell;

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
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
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

/**
 * The main class that facilitates running our tsp solver
 */
public class Main extends JComponent implements KeyListener, MouseListener, MouseWheelListener {

	private static final long serialVersionUID = 2722424842956800923L;
	public static ArrayList<VirtualPoint> result;

	static boolean calculateKnot = true;
	static boolean drawSubPaths = true;
	boolean drawMainPath = false;
	int minLineThickness = 1;
	boolean calc = false;

	public static Shell shell = null;
	public static Shell maxShell;
	public static PointSetPath retTup;
	public static Shell orgShell;
	public static ArrayList<Shell> subPaths = new ArrayList<>();
	static SegmentBalanceException drawException;
	static Shell resultShell;
	static JFrame frame;
	static Main main;
	private static Color stickyColor;
	int queuedMouseWheelTicks = 0;
	Camera camera;

	/**
	 * Creates the Jframe where the solution is drawn
	 * 
	 * @param args
	 */

	public Main() {
		frame = new JFrame("Ixdar");
		ImageIcon img = new ImageIcon("decalSmall.png");
		frame.setIconImage(img.getImage());
		frame.getContentPane().add(this);

		frame.getContentPane().setBackground(new Color(20, 20, 20));
		frame.pack();
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		camera = new Camera(750, 750, 1, 0, 0);
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
	}

	public static void main(String[] args) {
		main = new Main();
		String fileName = "three_circle_in_10";
		boolean printAll = false;
		retTup = FileManagement.importFromFile(new File("./src/test/solutions/" + fileName));
		DistanceMatrix d = retTup.d;
		if (retTup.d == null) {
			d = new DistanceMatrix(retTup.ps);
		}

		orgShell = retTup.tsp;

		System.out.println(orgShell.getLength());

		maxShell = orgShell.copyShallow();
		shell = maxShell;

		maxShell.knotName = fileName;

		Collections.shuffle(maxShell);
		System.out.println(maxShell);
		long startTimeKnotFinding = System.currentTimeMillis();
		if (calculateKnot) {
			result = new ArrayList<>(maxShell.slowSolve(maxShell, d, 10));
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
				if (printAll) {
					maxShell.buff.printAll();
				} else {
					maxShell.buff.printLayer(0);
				}
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
		System.out.println(maxShell.cutEngine.flatKnots);
		Random colorSeed = new Random();
		stickyColor = new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
		frame.repaint();

	}

	/**
	 * Creates a visual depiction of the shells/tsp path of the point set
	 * 
	 * @param g
	 */
	@Override
	public void paint(Graphics g) {
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
						camera.PanX = 0;
						camera.PanY = 0;
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

			// wi29_6-25: Something is wrong with the difference calculator, it is not
			// cutting the neighbor segments that went unmatched

			if (drawSubPaths) {
				for (Shell temp : subPaths) {
					temp.drawShell(this, g2, true, minLineThickness * 2,
							stickyColor, retTup.ps, camera);
				}
			}
			if (drawException != null) {
				resultShell.drawShell(this, g2, true, minLineThickness * 2, Color.magenta, retTup.ps, camera);
				Drawing.drawCutMatch(this, g2, drawException, minLineThickness * 2, retTup.ps, camera);
			}

			Drawing.drawPath(this, g2, retTup.path, minLineThickness, Color.RED, retTup.ps, false, false, true, false,
					camera);
			if (drawMainPath)
				orgShell.drawShell(this, g2, false, minLineThickness, Color.BLUE, retTup.ps, camera);

		} catch (Exception e) {
			e.printStackTrace();
			SwingUtilities.getWindowAncestor(this)
					.dispatchEvent(new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
		}
		if (!pressedKeys.isEmpty()) {
			frame.repaint();
		}

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

}

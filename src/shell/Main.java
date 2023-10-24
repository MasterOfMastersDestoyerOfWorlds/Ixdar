package shell;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Point2D.Double;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The main class that facilitates running our tsp solver
 */
public class Main extends JComponent{

	private static final long serialVersionUID = 2722424842956800923L;
	private static final boolean SCALE = false;
	private static final int WIDTH= 1000, HEIGHT = WIDTH;

	/**
	 * Creates a visual depiction of the shells/tsp path of the point set
	 * @param g
	 */
	@Override
    public void paint(Graphics g) {
		try {
	        Graphics2D g2 = (Graphics2D) g;
	
	
	        PointSetPath retTup = importFromFile(new File("./src/shell/djbouti"));
			DistanceMatrix d = new DistanceMatrix(retTup.ps);
	        Shell orgShell = retTup.ps.toShells(d);
	     
	        Shell maxShell = orgShell.copyRecursive();
	        
	        Shell conShell = maxShell.copyRecursive();
	        
	    
	        
	        /* All currently unused code

	        Shell hell1 = orgShell.collapseChildOntoShell();
	        
	        Shell hell2 = orgShell.getChild().collapseChildOntoShell();
	        
	        for( int i = 0 ; i <3; i ++) {
	        	minShell = minShell.collapseShellOntoParent();
	        }
	        
	        for( int i = 0 ; i <3; i ++) {
	        	maxShell = maxShell.collapseChildOntoShell();
	        }*/
	        
	        //maxShell.drawShell(this, g2, true, null, retTup.ps);
			
			//conShell = conShell.collapseAllShells(d); //finds optimal tsp path
			//System.out.println(conShell);


	        //conShell.drawShell(this, g2, false, Color.BLUE, retTup.ps);
	        //maxShell.drawShell(this, g2, true, null, retTup.ps);
	        
	        
	        /*Shell ndShell =new Shell();
	        ndShell.addAll(retTup.ps);
	        PointND start = ndShell.get(4), end = ndShell.get(2);
	        ndShell.remove(ndShell.get(4));
	        ndShell.remove(ndShell.get(2));
	        
	        Shell ndTest = Shell.solveBetweenEndpoints(new Segment(start, end), ndShell, new Shell());
	        System.out.println(ndTest.getLength());
	        ndTest.drawShell(this, g2, false, Color.BLUE);*/
			//conShell2 = conShell2.consensusWithChildren2(true);
			//conShell = conShell.consensusWithChildren2(true);
			//Shell hell1 = conShell.collapseChildOntoShell();

			//Shell hell2 = conShell.getChild().collapseChildOntoShell();
	        //Shell.collapseBOntoA(minShell, maxShell).drawShell(this, g2, new Random(), false);


	        //orgShell.drawShell(this, g2, new Random(), true);

	        //conShell.getChild().drawShell(this, g2, new Random(), false);
		        
		    
		    //hell1.drawShell(this, g2, new Random(), false);
	        //hell2.drawShell(this, g2, new Random(), false);
	        
	        //conShell.copy().collapseChildOntoShell().drawShell(this, g2, new Random(), false, Color.RED);	
	        	
	        //conShell.copy().getChild().collapseChildOntoShell().drawShell(this, g2, new Random(), false, Color.BLUE);
	        
	        	
	        //conShell.drawShell(this, g2, new Random(), true, null);


	        
	        

	        //Shell.collapseReduce(conShell2.getChild(), conShell2.getChild().getChild(), false).drawShell(this, g2, new Random(), false, null);

	        //conShell.getChild().consensusWithChildren().drawShell(this, g2, new Random(), false);

	        drawPath(this, g2, retTup.path, Color.RED, retTup.ps, false, false, true);
			orgShell.drawShell(this, g2, false,Color.BLUE, retTup.ps);
	        System.out.println("===============================================");
		}catch(Exception e) {
			e.printStackTrace();
			SwingUtilities.getWindowAncestor(this).dispatchEvent(new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
		}


    }

	/**
	 * Draws the tsp path of the pointset ps
	 * @param frame
	 * @param g2
	 * @param path
	 * @param color
	 * @param ps
	 * @param drawLines
	 * @param drawCircles
	 * @param drawNumbers
	 */
    public static void drawPath( JComponent frame, Graphics2D g2, Path2D path, Color color , PointSet ps, boolean drawLines, boolean drawCircles, boolean drawNumbers) {
        g2.setStroke(new BasicStroke(1.0f));
        g2.setPaint(color);

		GeneralPath scaledpath = new GeneralPath();
		double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
		boolean first = true;
		for(PointND pn : ps){
			
			Point2D p = pn.toPoint2D();
			
			if(p.getX() < minX) {
				minX = p.getX();
			}
			if(p.getY() < minY) {
				minY = p.getY();
			}
			if(p.getX() > maxX) {
				maxX = p.getX();
			}
			if(p.getY() > maxY) {
				maxY = p.getY();
			}
		}
		
		PathIterator pi = path.getPathIterator(null);
		Point2D start = null;
		double rangeX = maxX-minX, rangeY = maxY-minY;
		double height = SwingUtilities.getWindowAncestor(frame).getHeight(), width = SwingUtilities.getWindowAncestor(frame).getWidth();
		
		if(!SCALE) {
			height = WIDTH;
			width = HEIGHT;
		}
		
		int count = 0, offset = 100;
		while(!pi.isDone()) {
			double[] coords = new double[2];
			pi.currentSegment(coords);
			pi.next();
			coords[0] = (-(coords[0] - minX)*(width)/rangeX  + width + offset)/1.5;
			coords[1] = (-(coords[1] - minY)*(height)/rangeY + height + offset)/1.5;
			if(drawCircles) {
				g2.draw(new Ellipse2D.Double(coords[0]-5, coords[1]-5, 10, 10));
			}
			if(drawNumbers) {
				Font font = new Font("Serif", Font.PLAIN, 12);
				g2.setFont(font);

				g2.drawString("" + count, (int)coords[0]-5, (int)coords[1]-5);
			}
			if(first) {
				scaledpath.moveTo(coords[0], coords[1]);
				first = false;
				start = new Point2D.Double(coords[0], coords[1]);
			}
			else {
				scaledpath.lineTo(coords[0], coords[1]);
			}

			count ++;
		}
		scaledpath.lineTo(start.getX(), start.getY());
		if(drawLines) {
			g2.draw(scaledpath);
		}
		
	}

	/**
	 * Creates the Jframe where the solution is drawn
	 * @param args
	 */
	public static void main(String[] args) {
        JFrame frame = new JFrame("Draw GeneralPath Demo");
        frame.getContentPane().add(new Main());
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(new Dimension(WIDTH, HEIGHT));
        frame.setVisible(true);
        
    }

	/**
	 * Imports the point set and optimal tsp path from a file
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
			while (line != null) {
				if(flag == true) {
					String[] cords = line.split(" ");
					PointND pt = new PointND.Double(index, java.lang.Double.parseDouble(cords[1]), java.lang.Double.parseDouble(cords[2]));
					lines.add(pt);
					ps.add(pt);
					tsp.add(pt);
					if(first) {
						path.moveTo(java.lang.Double.parseDouble(cords[1]), java.lang.Double.parseDouble(cords[2]));
						first = false;
					}
					else {
						path.lineTo(java.lang.Double.parseDouble(cords[1]), java.lang.Double.parseDouble(cords[2]));
					}
				}
				if(line.contains("NODE_COORD_SECTION")) {
					flag = true;
				}
				line = br.readLine();
				index++;
				
			}
			br.close();
			return new PointSetPath(ps, path, tsp);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		return null;
		
	}
}

package shellCopy;

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

public class Main extends JComponent{

	private static final long serialVersionUID = 2722424842956800923L;
	private static final boolean SCALE = false;
	private static final int WIDTH= 1000, HEIGHT = WIDTH;
	@Override
    public void paint(Graphics g) {
		try {
	        Graphics2D g2 = (Graphics2D) g;
	
	
	        PointSetPath retTup = importFromFile(new File(".\\src\\shellCopy\\djbouti"));
	        
	        Shell orgShell = retTup.ps.toShells();
	        
	        Shell maxShell = orgShell.copy();
	        
	        Shell minShell = maxShell.getMinimalShell();
	        
	        Shell conShell = maxShell.copy();
	        
	        
	        //Shell hell1 = orgShell.collapseChildOntoShell();
	        
	        //Shell hell2 = orgShell.getChild().collapseChildOntoShell();
	        
	        /*for( int i = 0 ; i <3; i ++) {
	        	minShell = minShell.collapseShellOntoParent();
	        }
	        
	        for( int i = 0 ; i <3; i ++) {
	        	maxShell = maxShell.collapseChildOntoShell();
	        }*/
	        
	        	conShell = maxShell;
	        	Shell conShell2 = conShell.copy();
	        	while(!conShell2.isMinimal()) {
	        		//conShell = conShell.consensusWithChildren(true);
	        		conShell2 = conShell2.consensusWithChildren2(true, this, g2, retTup.ps);
	        		System.out.println(conShell2.updateOrder());
	        	}

        		//conShell2 = conShell2.consensusWithChildren2(true);
	        	System.out.println(conShell2.getLength());
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

	        conShell2.drawShell(this, g2, new Random(), true, Color.RED);
	        
	        

	        //Shell.collapseReduce(conShell2.getChild(), conShell2.getChild().getChild(), false).drawShell(this, g2, new Random(), false, null);

	        //conShell.getChild().consensusWithChildren().drawShell(this, g2, new Random(), false);

	        drawPath(this, g2, retTup.path, Color.RED, retTup.ps, false, true, false);
		}catch(Exception e) {
			e.printStackTrace();
			SwingUtilities.getWindowAncestor(this).dispatchEvent(new WindowEvent(SwingUtilities.getWindowAncestor(this), WindowEvent.WINDOW_CLOSING));
		}


    }
	
    public static void drawPath( JComponent frame, Graphics2D g2, Path2D path, Color color , PointSet ps, boolean drawLines, boolean drawCircles, boolean drawNumbers) {
        g2.setStroke(new BasicStroke(1.0f));
        g2.setPaint(color);

		GeneralPath scaledpath = new GeneralPath();
		double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE, maxX = 0, maxY = 0;
		boolean first = true;
		for(Point2D p : ps){
			
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
			count ++;
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
		}
		scaledpath.lineTo(start.getX(), start.getY());
		if(drawLines) {
			g2.draw(scaledpath);
		}
		
	}

	public static void main(String[] args) {
        JFrame frame = new JFrame("Draw GeneralPath Demo");
        frame.getContentPane().add(new Main());
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(new Dimension(WIDTH, HEIGHT));
        frame.setVisible(true);
        
    }
	public PointSetPath importFromFile(File f) {
		try {
			
			BufferedReader br = new BufferedReader(new FileReader(f));
			ArrayList<Point2D> lines = new ArrayList<Point2D>();
			String line = br.readLine();
			PointSet ps = new PointSet();
			Path2D path = new GeneralPath(GeneralPath.WIND_NON_ZERO);

			boolean flag = true, first = true;
			while (line != null) {
				if(flag == true) {
					String[] cords = line.split(" ");
					Point2D pt = new Point2D.Double(java.lang.Double.parseDouble(cords[1]), java.lang.Double.parseDouble(cords[2]));
					lines.add(pt);
					ps.add(pt);
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
				
			}
			br.close();
			return new PointSetPath(ps, path);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		return null;
		
	}
}

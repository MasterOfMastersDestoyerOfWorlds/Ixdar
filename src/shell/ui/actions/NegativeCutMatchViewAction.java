package shell.ui.actions;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JFrame;

import shell.Main;
import shell.Toggle;
import shell.ui.tools.NegativeCutMatchViewTool;

public class NegativeCutMatchViewAction extends AbstractAction {
    JFrame frame;
    NegativeCutMatchViewTool negativeCutMatchViewTool;

    public NegativeCutMatchViewAction(JFrame frame) {
        this.frame = frame;
        negativeCutMatchViewTool = new NegativeCutMatchViewTool();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        negativeCutMatchViewTool.reset();
        negativeCutMatchViewTool.initSegmentMap();
        Main.tool = negativeCutMatchViewTool;

    }

}

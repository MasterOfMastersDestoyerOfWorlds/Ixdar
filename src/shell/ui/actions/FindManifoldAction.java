package shell.ui.actions;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JFrame;

import shell.Main;
import shell.ui.tools.FindManifoldTool;

public class FindManifoldAction extends AbstractAction {
    JFrame frame;
    FindManifoldTool findManifoldTool;

    public FindManifoldAction(JFrame frame) {
        this.frame = frame;
        findManifoldTool = new FindManifoldTool();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        findManifoldTool.reset();
        findManifoldTool.state = FindManifoldTool.States.FindStart;
        Main.tool = findManifoldTool;
        Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;

    }

}

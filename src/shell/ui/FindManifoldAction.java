package shell.ui;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JFrame;

import shell.Main;
import shell.enums.FindState;

public class FindManifoldAction extends AbstractAction {
    JFrame frame;

    public FindManifoldAction(JFrame frame) {
        this.frame = frame;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Main.drawCutMatch = false;
        Main.findState.state = FindState.States.FindStart;
    }

}

package shell.ui.actions;

import java.awt.event.ActionEvent;
import java.util.ArrayList;

import javax.swing.AbstractAction;
import javax.swing.JFrame;

import shell.Main;
import shell.Toggle;
import shell.file.Manifold;
import shell.ui.tools.EditManifoldTool;

public class EditManifoldAction extends AbstractAction {
    JFrame frame;
    EditManifoldTool editCutMatchTool;

    public EditManifoldAction(JFrame frame) {
        this.frame = frame;
        editCutMatchTool = new EditManifoldTool();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (Toggle.manifold.value && Toggle.drawCutMatch.value) {
            editCutMatchTool.reset();
            Main.tool = editCutMatchTool;
        }

    }

}

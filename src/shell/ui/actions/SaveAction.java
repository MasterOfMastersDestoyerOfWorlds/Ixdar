package shell.ui.actions;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JFrame;

public class SaveAction extends AbstractAction {
    JFrame frame;
    SaveDialog dialog;

    public SaveAction(JFrame frame, String fileName) {
        super("saveNew");
        this.frame = frame;
        dialog = new SaveDialog(frame, fileName);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        String newFilename = dialog.showDialog();

        if ((newFilename != null) && (newFilename.length() > 0)) {
            System.out.println("Saving to file: " + newFilename);
        }
    }
}

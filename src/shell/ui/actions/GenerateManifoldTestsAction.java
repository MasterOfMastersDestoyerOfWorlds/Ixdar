package shell.ui.actions;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import javax.swing.AbstractAction;
import javax.swing.JFrame;
import shell.Main;
import shell.PointND;
import shell.exceptions.SegmentBalanceException;
import shell.file.FileManagement;
import shell.file.Manifold;
import shell.shell.Shell;

public class GenerateManifoldTestsAction extends AbstractAction {

    JFrame frame;
    String fileName;
    boolean inManifold;

    public GenerateManifoldTestsAction(JFrame frame, String fileName, boolean inManifold) {
        super("generateManifoldTests");
        this.frame = frame;
        this.fileName = fileName.replace('_', '-') + "-manifold";
        this.inManifold = inManifold;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (!inManifold) {
            String directoryLocation = FileManagement.solutionsFolder + fileName;
            File dir = new File(directoryLocation);
            if (!dir.exists()) {
                dir.mkdir();
            }
            System.out.println("Generating Tests for: " + fileName);
            Shell manifold = Main.orgShell;
            if (manifold == null) {
                System.out.println("No manifold found");
                return;
            }
            System.out.println(manifold);
            int numFiles = 0;
            ArrayList<Manifold> manifolds = new ArrayList<>();
            for (int i = 0; i < manifold.size(); i++) {
                PointND kp1 = manifold.get(i);
                int idxNext = i + 1 == manifold.size() ? 0 : i + 1;
                PointND cp1 = manifold.get(idxNext);
                for (int k = i; k < manifold.size(); k++) {
                    if (i == k) {
                        continue;
                    }
                    PointND kp2 = manifold.get(k);
                    int idxNext2 = k + 1 == manifold.size() ? 0 : k + 1;
                    PointND cp2 = manifold.get(k + 1 == manifold.size() ? 0 : k + 1);
                    if (k == idxNext || i == idxNext2 || idxNext == idxNext2) {
                        continue;
                    }
                    try {
                        Manifold m = new Manifold(kp1.getID(), cp1.getID(), kp2.getID(), cp2.getID(), true);
                        m.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
                        Manifold m2 = new Manifold(cp1.getID(), kp1.getID(), cp2.getID(), kp2.getID(), true);
                        m2.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
                        Manifold m3 = new Manifold(kp1.getID(), cp1.getID(), cp2.getID(), kp2.getID(), false);
                        m3.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
                        Manifold m4 = new Manifold(cp1.getID(), kp1.getID(), kp2.getID(), cp2.getID(), false);
                        m4.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
                        manifolds.add(m);
                        manifolds.add(m2);
                        manifolds.add(m3);
                        manifolds.add(m4);
                    } catch (SegmentBalanceException e) {
                        e.printStackTrace();
                    }

                    numFiles++;
                }
            }
            try {
                checkAndMakeMasterFile(directoryLocation, manifolds);
            } catch (SegmentBalanceException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            System.out.println(numFiles);
        } else {
            System.out.println("Inside manifold, updating individual test...");
        }
    }

    public void checkAndMakeFile(String directoryLocation, PointND kp1, PointND cp1, PointND kp2, PointND cp2,
            ArrayList<Manifold> manifolds) throws SegmentBalanceException {
        String manifoldTestFileLocation = directoryLocation + "/" + fileName + "_"
                + kp1.getID() + "-" + cp1.getID() + "_"
                + kp2.getID() + "-" + cp2.getID();
        File manifoldTestFile = new File(manifoldTestFileLocation);

        Manifold m = new Manifold(kp1.getID(), cp1.getID(), kp2.getID(), cp2.getID(), true);
        m.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
        Manifold m2 = new Manifold(cp1.getID(), kp1.getID(), cp2.getID(), kp2.getID(), true);
        m2.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
        Manifold m3 = new Manifold(kp1.getID(), cp1.getID(), cp2.getID(), kp2.getID(), false);
        m3.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
        Manifold m4 = new Manifold(cp1.getID(), kp1.getID(), kp2.getID(), cp2.getID(), false);
        m4.calculateManifoldCutMatch(Main.shell, Main.manifoldKnot);
        manifolds.add(m);
        manifolds.add(m2);
        manifolds.add(m3);
        manifolds.add(m4);

        try {
            if (!manifoldTestFile.exists()) {
                manifoldTestFile.createNewFile();
            }
            FileManagement.appendLine(manifoldTestFile, "LOAD " + Main.file.getName());
            FileManagement.appendLine(manifoldTestFile, m.toFileString());
            FileManagement.appendLine(manifoldTestFile, m2.toFileString());
            FileManagement.appendLine(manifoldTestFile, m3.toFileString());
            FileManagement.appendLine(manifoldTestFile, m4.toFileString());

        } catch (IOException e) {
            System.out.println(e);
        }
        System.out.println(manifoldTestFileLocation);
    }

    public void checkAndMakeMasterFile(String directoryLocation,
            ArrayList<Manifold> manifolds) throws SegmentBalanceException {
        String manifoldTestFileLocation = directoryLocation + "/" + fileName + "_master";
        File manifoldTestFile = new File(manifoldTestFileLocation);

        try {
            if (!manifoldTestFile.exists()) {
                manifoldTestFile.createNewFile();
            }
            ArrayList<String> lines = new ArrayList<>();
            lines.add("LOAD " + Main.file.getName());
            for (Manifold m : manifolds) {
                lines.add(m.toFileString());
            }
            FileManagement.writeLines(manifoldTestFile, lines);

        } catch (IOException e) {
            System.out.println(e);
        }
        System.out.println(manifoldTestFileLocation);
    }
}
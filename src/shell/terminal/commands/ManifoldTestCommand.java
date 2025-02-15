package shell.terminal.commands;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import shell.Toggle;
import shell.cuts.Manifold;
import shell.exceptions.SegmentBalanceException;
import shell.file.FileManagement;
import shell.point.PointND;
import shell.shell.Shell;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class ManifoldTestCommand extends TerminalCommand {

    public static String cmd = "mft";

    @Override
    public String fullName() {
        return "manifoldtest";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "write a test to find the shortest cutmatch sequence between any two points";
    }

    @Override
    public String usage() {
        return "usage: mft|manifoldtest";
    }

    @Override
    public int argLength() {
        return 0;
    }

    public static String run(String fileName, String directory) {
        fileName = fileName.replace('_', '-');
        int idx = fileName.length();
        if (fileName.contains(".ix")) {
            idx = fileName.indexOf(".ix");
        }
        fileName = fileName.substring(0, idx) + "-manifold";
        String dirPath = directory + "/";
        if (!Main.tool.canUseToggle(Toggle.Manifold)) {
            Shell manifold = Main.orgShell;
            if (manifold == null) {
                return "";
            }
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
                }
            }
            try {
                checkAndMakeMasterFile(dirPath, fileName, manifolds);
                return fileName;
            } catch (SegmentBalanceException e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String newFileName = run(terminal.loadedFile.getName(), terminal.directory);
        if (newFileName.isBlank()) {
            terminal.error("could not create manifold file");
        }
        return new String[] { "ld " + newFileName };
    }

    public void checkAndMakeFile(String directoryLocation, String fileName, PointND kp1, PointND cp1, PointND kp2,
            PointND cp2,
            ArrayList<Manifold> manifolds) throws SegmentBalanceException {
        String manifoldTestFileLocation = directoryLocation + "/" + fileName + "_"
                + kp1.getID() + "-" + cp1.getID() + "_"
                + kp2.getID() + "-" + cp2.getID() + ".ix";
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

    public static void checkAndMakeMasterFile(String directoryLocation, String fileName,
            ArrayList<Manifold> manifolds) throws SegmentBalanceException {
        File dir = new File(new File(directoryLocation).getParent() + "\\" + fileName);
        File manifoldTestFile = new File(dir.getPath() + "\\" + fileName + "_master" + ".ix");

        try {
            if (!dir.exists()) {
                dir.mkdir();
            }
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
    }
}

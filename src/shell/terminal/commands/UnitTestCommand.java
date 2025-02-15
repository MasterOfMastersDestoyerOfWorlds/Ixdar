package shell.terminal.commands;

import java.io.File;

import shell.file.FileManagement;
import shell.terminal.Terminal;

public class UnitTestCommand extends TerminalCommand {

    public static String cmd = "ut";

    @Override
    public String fullName() {
        return "unittest";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "write a test to check if ixdar can find the smallest known length";
    }

    @Override
    public String usage() {
        return "usage: ut|unittest";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String template = "package unit.subgraphs;\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "import org.junit.jupiter.api.parallel.Execution;\n" +
                "import org.junit.jupiter.api.parallel.ExecutionMode;\n" +
                "import unit.SubGraphs;\n" +
                "@Execution(ExecutionMode.CONCURRENT)\n" +
                "public class %s {\n\n";
        String testItem = "\t@Test\n" + "\tpublic void test_%s() {\n"
                + "\t\tSubGraphs.testMethod(\"%s\");\n" + "\t}\n\n";
        File dir = new File(terminal.directory);
        String dirName = dir.getName();
        String testName = dirName.substring(0, 1).toUpperCase() + dirName.substring(1) + "Test";
        template = String.format(template, testName);
        File[] solutions = new File(terminal.directory).listFiles();
        for (int i = 0; i < solutions.length; i++) {
            File f = solutions[i];
            String fileName = f.getName().replace(".ix", "");
            template += String.format(testItem, fileName.replace("-", "_"), fileName);
        }
        template += "}";
        FileManagement.writeSubGraphTest(testName + ".java", template);
        return new String[] { "cd " };

    }
}

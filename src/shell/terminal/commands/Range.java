package shell.terminal.commands;

public class Range {
    public int startIdx;
    public int endIdx;

    public Range(int startIdx, int endIdx) {
        this.startIdx = startIdx;
        this.endIdx = endIdx;
    }

    public static Range parse(String arg) throws Exception {
        if (arg.contains("-")) {
            String[] parts = arg.split("-");
            if (parts.length != 2) {
                throw new Exception("more than one dash in range");
            }
            int start = 0;
            int end = 0;
            try {
                start = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw new Exception("first half of range is not an integer");
            }

            try {
                end = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new Exception("second half of range is not an integer");
            }
            return new Range(start, end);
        } else {
            try {
                int start = Integer.parseInt(arg);
                return new Range(start, start);
            } catch (NumberFormatException e) {
                throw new Exception("first half of range is not an integer");
            }
        }
    }

}

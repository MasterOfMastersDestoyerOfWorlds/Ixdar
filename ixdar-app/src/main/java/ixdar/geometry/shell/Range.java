package ixdar.geometry.shell;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.geometry.point.PointND;

public class Range {
    public int startIdx;
    public int endIdx;
    public int rangeLength;
    public boolean reversed;

    public Range(int startIdx, int endIdx) {
        this.startIdx = startIdx;
        this.endIdx = endIdx;
        if (startIdx == endIdx) {
            rangeLength = 1;
        } else {
            rangeLength = Math.abs(endIdx - startIdx) + 1;
        }
        reversed = startIdx > endIdx;
    }

    public static Range parse(String arg) throws TerminalParseException {
        if (arg.contains("-")) {
            String[] parts = arg.split("-");
            if (parts.length != 2) {
                throw new TerminalParseException("more than one dash in range");
            }
            int start = 0;
            int end = 0;
            try {
                start = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw new TerminalParseException("first half of range is not an integer");
            }

            try {
                end = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new TerminalParseException("second half of range is not an integer");
            }
            return new Range(start, end);
        } else {
            try {
                int start = Integer.parseInt(arg);
                return new Range(start, start);
            } catch (NumberFormatException e) {
                throw new TerminalParseException("first half of range is not an integer");
            }
        }
    }

    @Override
    public String toString() {
        if (startIdx != endIdx) {
            return startIdx + "-" + endIdx;
        }
        return startIdx + "";
    }

    public boolean hasPoint(PointND p) {
        if (reversed) {
            if (p.getID() <= startIdx && p.getID() >= endIdx) {
                return true;
            }
        } else {
            if (p.getID() >= startIdx && p.getID() <= endIdx) {
                return true;
            }
        }
        return false;
    }

}

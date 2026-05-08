package ixdar.geometry.shell;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.geometry.point.PointND;

public class Range {
    public static final String STR = "-";
    public static final String FIRST_HALF_OF_RANGE_IS_NOT_AN_INTEGER = "first half of range is not an integer";
    public int startIdx;
    public int endIdx;
    public int rangeLength;
    public boolean reversed;

    /**
     * TODO: document {@code Range}.
     *
     * @param startIdx TODO: describe
     * @param endIdx TODO: describe
     */
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

    /**
     * TODO: document {@code parse}.
     *
     * @param arg TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static Range parse(String arg) throws TerminalParseException {
        if (arg.contains(STR)) {
            String[] parts = arg.split(STR);
            if (parts.length != 2) {
                throw new TerminalParseException("more than one dash in range");
            }
            int start = 0;
            int end = 0;
            try {
                start = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw new TerminalParseException(FIRST_HALF_OF_RANGE_IS_NOT_AN_INTEGER);
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
                throw new TerminalParseException(FIRST_HALF_OF_RANGE_IS_NOT_AN_INTEGER);
            }
        }
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        if (startIdx != endIdx) {
            return startIdx + STR + endIdx;
        }
        return startIdx + "";
    }

    /**
     * TODO: document {@code hasPoint}.
     *
     * @param p TODO: describe
     * @return TODO: describe
     */
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

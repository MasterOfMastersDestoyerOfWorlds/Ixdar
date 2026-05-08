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
     * Inclusive integer interval {@code [startIdx, endIdx]}; if {@code startIdx > endIdx} the
     * range is marked {@link #reversed} and {@link #rangeLength} accounts for either direction.
     *
     * @param startIdx first index of the interval (inclusive)
     * @param endIdx last index of the interval (inclusive); may be less than {@code startIdx}
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
     * Parse a CLI range token. Either a single integer ({@code "5"}) or a dash-separated pair
     * ({@code "3-7"}); the dash form may be reversed ({@code "7-3"}).
     *
     * @param arg the token to parse
     * @return the parsed range
     * @throws TerminalParseException if the token is empty, has more than one dash, or either
     *         half is not an integer
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
     * Render the range using the same syntax accepted by {@link #parse(String)}: either
     * {@code "n"} for a single index or {@code "a-b"} for a span.
     *
     * @return CLI-compatible string form
     */
    @Override
    public String toString() {
        if (startIdx != endIdx) {
            return startIdx + STR + endIdx;
        }
        return startIdx + "";
    }

    /**
     * Test whether {@code p}'s ID falls within this inclusive range, honoring {@link #reversed}.
     *
     * @param p point whose {@code getID()} is checked
     * @return {@code true} when the ID lies in the interval (in either direction)
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

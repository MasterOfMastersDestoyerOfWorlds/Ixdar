package ixdar.game.trade.level;

import java.util.Arrays;

import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.level.TradeLevel.CitySpec;
import ixdar.game.trade.level.TradeLevel.CompanySpec;
import ixdar.game.trade.level.TradeLevel.FacilitySpec;
import ixdar.game.trade.level.TradeLevel.LoopSpec;
import ixdar.game.trade.level.TradeLevel.TownSpec;

/**
 * Reads a trade level's {@code .ix} file: one record per line, a keyword and its space-separated fields,
 * {@code #} starting a comment. TITLE and GOAL take the rest of the line; the others are listed in the
 * campaign files under {@code resources/trade/}.
 */
public final class TradeLevelFile {
    public static final String RESOURCE_FOLDER = "trade";
    public static final String PLAYER = "player";

    private TradeLevelFile() {
    }

    /**
     * Parse a level.
     *
     * @param text the file's contents
     * @return the level it describes
     * @throws IllegalArgumentException naming the line of a record it cannot read
     */
    public static TradeLevel read(String text) {
        TradeLevel level = new TradeLevel();
        String[] lines = text.split("\n");
        for (int number = 0; number < lines.length; number++) {
            String line = lines[number].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\s+");
            String rest = line.substring(f[0].length()).strip();
            try {
                switch (f[0]) {
                case "TITLE" -> level.title = rest;
                case "GOAL" -> level.goal = rest;
                case "LAPS" -> level.laps = Integer.parseInt(f[1]);
                case "SHARE" -> level.shareGoal = Double.parseDouble(f[1]);
                case "MEDALS" -> level.medals = Arrays.stream(f, 1, f.length).mapToInt(Integer::parseInt).toArray();
                case "MAP" -> {
                    level.seed = Long.parseLong(f[1]);
                    level.width = Float.parseFloat(f[2]);
                    level.height = Float.parseFloat(f[3]);
                    level.targetCells = Integer.parseInt(f[4]);
                }
                case "TOWNS" -> level.generatedTowns = Integer.parseInt(f[1]);
                case "CITIES" -> level.generatedCities = Integer.parseInt(f[1]);
                case "MOOR" -> level.moors.add(numbers(f));
                case "RIVER" -> level.rivers.add(numbers(f));
                case "BRIDGE" -> level.bridges.add(numbers(f));
                case "CITY" -> level.cities.add(new CitySpec(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]),
                        Integer.parseInt(f[4]), Double.parseDouble(f[5])));
                case "TOWN" -> level.towns.add(new TownSpec(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3])));
                case "COMPANY" -> level.companies.add(new CompanySpec(f[1], f[2].equals(PLAYER), f[3],
                        Integer.parseInt(f[4]), Integer.parseInt(f[5])));
                case "STATION" -> level.facilities.add(new FacilitySpec(f[1], f[2], Facility.STATION));
                case "BASE" -> level.facilities.add(new FacilitySpec(f[1], f[2], Facility.BASE));
                case "LOOP" -> level.loops.add(new LoopSpec(f[1], Integer.parseInt(f[2]),
                        Arrays.copyOfRange(f, 3, f.length)));
                case "RULE" -> level.rules.set(f[1], Double.parseDouble(f[2]));
                default -> throw new IllegalArgumentException("unknown record " + f[0]);
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("line " + (number + 1) + ": " + failure.getMessage(), failure);
            }
        }
        return level;
    }

    /**
     * A record's fields after its keyword, as numbers.
     *
     * @param fields the record split on spaces
     * @return every field but the first
     */
    private static float[] numbers(String[] fields) {
        float[] values = new float[fields.length - 1];
        for (int k = 1; k < fields.length; k++) {
            values[k - 1] = Float.parseFloat(fields[k]);
        }
        return values;
    }
}

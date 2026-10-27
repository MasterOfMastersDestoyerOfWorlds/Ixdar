package ixdar.game;

import java.util.ArrayList;
import java.util.List;

/**
 * One playable level of the trade game: its terrain, its cities with their demand, where the player and
 * the rival start, and how it is won. The tutorials teach routing on gold medals; the rival level is the
 * market war. Cities are stored as parallel arrays.
 */
public class TradeLevel {
    public static final float MAP_WIDTH = 780f;
    public static final float MAP_HEIGHT = 520f;
    public static final int TARGET_CELLS = 9;
    public static final int NO_RIVAL = -1;
    public static final int RIVAL_LEVEL_INDEX = 3;
    public static final String HOME = "Home";
    public static final String ASHBY = "Ashby";
    public static final String BRAMFORD = "Bramford";
    public static final String COBHAM = "Cobham";
    public static final String DORRIN = "Dorrin";
    public static final String ELMET = "Elmet";
    public static final String FARROW = "Farrow";

    public String title;
    public String goalText;
    public long seed;
    public int lapLimit;
    public int startGold;
    public int wagonCapacity;
    public int villagesToPlace;
    /** Gold earned by the lap limit for bronze, silver and gold; empty on the rival level. */
    public int[] medalGold = new int[0];
    /** The unit share of all demand that wins; zero on the tutorials. */
    public double shareGoal;

    public String[] cityNames;
    public float[] cityX;
    public float[] cityY;
    public int[] cityDemand;
    public double[] cityCeiling;

    public int playerHome;
    public int rivalHome = NO_RIVAL;
    public int rivalStartGold;
    public int rivalWagonCapacity;
    /** Cities with a player station at the start. */
    public int[] playerStations = new int[0];
    /** The player's starting loop in visiting order, beginning at home; empty for none. */
    public int[] playerLoop = new int[0];
    public int[] rivalStations = new int[0];
    public int[][] rivalLoops = new int[0][];
    public int[] rivalWagons = new int[0];

    public float moorX;
    public float moorY;
    public float moorRadiusX;
    public float moorRadiusY;
    public float[] riverX = new float[0];
    public float[] riverY = new float[0];
    public float[] bridgeYs = new float[0];

    /**
     * The campaign in play order: three tutorials, then the rival level.
     *
     * @return every level
     */
    public static List<TradeLevel> campaign() {
        List<TradeLevel> levels = new ArrayList<>();

        TradeLevel first = new TradeLevel();
        first.title = "1 · First blood";
        first.goalText = "Pipe from home to a town and back, buy a station there, end the lap.";
        first.seed = 11;
        first.lapLimit = 6;
        first.startGold = 160;
        first.wagonCapacity = 160;
        first.villagesToPlace = 8;
        first.medalGold = new int[] { 300, 470, 620 };
        first.cities(new String[] { HOME, ASHBY, BRAMFORD, COBHAM },
                new float[] { 250, 400, 470, 330 }, new float[] { 260, 180, 330, 380 },
                new int[] { 0, 40, 45, 30 }, new double[] { 0, 3.6, 3.4, 3.8 });
        first.moorX = 560;
        first.moorY = 160;
        first.moorRadiusX = 60;
        first.moorRadiusY = 40;
        levels.add(first);

        TradeLevel second = new TradeLevel();
        second.title = "2 · Fuel";
        second.goalText = "Grow your loop. Every cell burns blood: a city that runs the wagon dry shows red.";
        second.seed = 23;
        second.lapLimit = 8;
        second.startGold = 260;
        second.wagonCapacity = 150;
        second.villagesToPlace = 12;
        second.medalGold = new int[] { 450, 680, 880 };
        second.cities(new String[] { HOME, ASHBY, BRAMFORD, COBHAM, DORRIN, ELMET, FARROW },
                new float[] { 200, 320, 420, 300, 520, 560, 420 }, new float[] { 260, 150, 220, 380, 140, 330, 430 },
                new int[] { 0, 35, 40, 30, 45, 40, 35 }, new double[] { 0, 3.4, 3.4, 3.6, 4.0, 3.8, 3.7 });
        second.playerStations = new int[] { 1 };
        second.playerLoop = new int[] { 0, 1 };
        second.moorX = 420;
        second.moorY = 300;
        second.moorRadiusX = 45;
        second.moorRadiusY = 35;
        levels.add(second);

        TradeLevel third = new TradeLevel();
        third.title = "3 · Pipe and collapse";
        third.goalText = "Build a second loop across the river, pipe it into your network, then collapse the chain.";
        third.seed = 37;
        third.lapLimit = 10;
        third.startGold = 420;
        third.wagonCapacity = 220;
        third.villagesToPlace = 14;
        third.medalGold = new int[] { 1500, 2200, 2900 };
        third.cities(new String[] { HOME, ASHBY, BRAMFORD, COBHAM, DORRIN, ELMET, FARROW, "Garth" },
                new float[] { 170, 270, 300, 520, 600, 560, 440, 650 },
                new float[] { 260, 160, 360, 150, 230, 380, 300, 320 },
                new int[] { 0, 35, 35, 40, 45, 40, 30, 35 }, new double[] { 0, 3.4, 3.4, 3.8, 3.9, 3.8, 3.6, 4.0 });
        third.playerStations = new int[] { 1, 2 };
        third.playerLoop = new int[] { 0, 1, 2 };
        third.riverX = new float[] { 400, 385, 410, 390, 405 };
        third.riverY = new float[] { -20, 120, 260, 400, 540 };
        third.bridgeYs = new float[] { 190, 330 };
        third.moorX = 260;
        third.moorY = 440;
        third.moorRadiusX = 50;
        third.moorRadiusY = 30;
        levels.add(third);

        TradeLevel rival = new TradeLevel();
        rival.title = "4 · The blood war";
        rival.goalText = "Reach 60% of all demand, or bankrupt the rival, within 20 laps.";
        rival.seed = 7;
        rival.lapLimit = 20;
        rival.startGold = 300;
        rival.wagonCapacity = 150;
        rival.villagesToPlace = 17;
        rival.shareGoal = 0.6;
        rival.cities(new String[] { HOME, ASHBY, "Exley", "Kingsmoor", BRAMFORD, "Fenwick", "Gorham",
                "Thornby", "Rival base", "Dunmere", "Eskdale" },
                new float[] { 140, 250, 160, 440, 320, 380, 260, 500, 640, 610, 680 },
                new float[] { 280, 160, 420, 250, 350, 100, 450, 140, 190, 400, 300 },
                new int[] { 0, 40, 30, 60, 40, 30, 25, 35, 50, 55, 45 },
                new double[] { 0, 2.4, 2.6, 2.4, 2.4, 2.7, 2.8, 2.6, 2.3, 2.3, 2.4 });
        rival.playerStations = new int[] { 1, 3, 4 };
        rival.playerLoop = new int[] { 0, 1, 3, 4 };
        rival.rivalHome = 8;
        rival.rivalStartGold = 400;
        rival.rivalStations = new int[] { 3, 8, 9, 10 };
        rival.rivalLoops = new int[][] { { 8, 3, 9, 10 } };
        rival.rivalWagons = new int[] { 2 };
        rival.rivalWagonCapacity = 200;
        rival.riverX = new float[] { 560, 548, 572, 552, 566, 545 };
        rival.riverY = new float[] { -20, 90, 200, 300, 410, 540 };
        rival.bridgeYs = new float[] { 160, 390 };
        rival.moorX = 390;
        rival.moorY = 440;
        rival.moorRadiusX = 70;
        rival.moorRadiusY = 40;
        levels.add(rival);
        return levels;
    }

    /**
     * Set the level's cities; index 0 is the player's home.
     *
     * @param names    city names
     * @param xs       city x positions
     * @param ys       city y positions
     * @param demand   units each city needs per lap
     * @param ceilings the most each town pays per unit
     */
    private void cities(String[] names, float[] xs, float[] ys, int[] demand, double[] ceilings) {
        cityNames = names;
        cityX = xs;
        cityY = ys;
        cityDemand = demand;
        cityCeiling = ceilings;
        playerHome = 0;
    }
}

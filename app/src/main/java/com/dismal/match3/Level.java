package com.dismal.match3;

/**
 * Static level descriptions. Jelly rows are strings where '.' is empty,
 * '1' is one layer and '2' is two layers. Toy counts are listed in
 * {@link Toy} order: yoyo, bubbles, spring toy, bouncy ball.
 */
public final class Level {

    public final String name;
    public final int rows;
    public final int cols;
    public final int colors;
    public final int moves;
    public final int[] toyCounts;
    public final boolean hasJelly;
    private final String[] jelly;

    public Level(String name, int rows, int cols, int colors, int moves, String... jelly) {
        this(name, rows, cols, colors, moves, null, jelly);
    }

    public Level(String name, int rows, int cols, int colors, int moves, int[] toyCounts, String... jelly) {
        this.name = name;
        this.rows = rows;
        this.cols = cols;
        this.colors = colors;
        this.moves = moves;
        this.jelly = jelly;

        this.toyCounts = new int[Toy.values().length];
        if (toyCounts != null) {
            for (int i = 0; i < Math.min(toyCounts.length, this.toyCounts.length); i++) {
                this.toyCounts[i] = Math.max(0, toyCounts[i]);
            }
        }

        boolean anyJelly = false;
        for (String row : jelly) {
            if (row != null && (row.indexOf('1') >= 0 || row.indexOf('2') >= 0)) {
                anyJelly = true;
                break;
            }
        }
        this.hasJelly = anyJelly;
    }

    public int jellyAt(int r, int c) {
        if (r < 0 || r >= jelly.length) {
            return 0;
        }
        String row = jelly[r];
        if (row == null || c < 0 || c >= row.length()) {
            return 0;
        }
        char ch = row.charAt(c);
        if (ch == '2') {
            return 2;
        }
        if (ch == '1') {
            return 1;
        }
        return 0;
    }

    public int toyTotal() {
        int n = 0;
        for (int count : toyCounts) {
            n += count;
        }
        return n;
    }

    public boolean hasToys() {
        return toyTotal() > 0;
    }

    /** Counts in {@link Toy} order: yoyo, bubbles, spring toy, bouncy ball. */
    private static int[] toys(int yoyo, int bubbles, int spring, int ball) {
        return new int[]{yoyo, bubbles, spring, ball};
    }

    public static final Level[] LEVELS = {
            new Level("Jelly Garden", 8, 8, 5, 24,
                    "........",
                    "...11...",
                    "..1111..",
                    ".111111.",
                    ".111111.",
                    "..1111..",
                    "...11...",
                    "........"),

            new Level("Twin Ponds", 8, 8, 6, 24,
                    "........",
                    "..1111..",
                    "..1221..",
                    "........",
                    "........",
                    "..1221..",
                    "..1111..",
                    "........"),

            new Level("Polka Dots", 8, 9, 6, 26,
                    ".........",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".........",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".........",
                    "........."),

            new Level("Jelly Frame", 9, 9, 6, 56,
                    "222...222",
                    "2.......2",
                    "2.......2",
                    ".........",
                    ".........",
                    ".........",
                    "2.......2",
                    "2.......2",
                    "222...222"),

            new Level("Crown of Jelly", 9, 9, 6, 60,
                    "111111111",
                    "111111111",
                    "111111111",
                    "111111111",
                    "111111111",
                    "111111111",
                    "111111111",
                    "111111111",
                    "....1...."),

            new Level("Toy Box", 8, 8, 6, 38, toys(1, 0, 0, 2),
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........"),

            new Level("Bubble Parade", 8, 8, 6, 44, toys(0, 2, 1, 0),
                    "........",
                    "........",
                    "..1111..",
                    "..1111..",
                    "..1111..",
                    "........",
                    "........",
                    "........"),

            new Level("Picnic Blanket", 8, 8, 6, 34,
                    "........",
                    "........",
                    "11111111",
                    "11111111",
                    "11111111",
                    "11111111",
                    "........",
                    "........"),

            new Level("Toy Tumble", 8, 8, 6, 26, toys(1, 1, 0, 0),
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........"),

            new Level("Bubble Wrap", 9, 9, 6, 26,
                    ".........",
                    ".1.1.1.1.",
                    ".........",
                    ".1.1.1.1.",
                    ".........",
                    ".1.1.1.1.",
                    ".........",
                    ".1.1.1.1.",
                    "........."),

            new Level("Ring Toss", 8, 8, 5, 32,
                    "11111111",
                    "1......1",
                    "1......1",
                    "1......1",
                    "1......1",
                    "1......1",
                    "1......1",
                    "11111111"),

            new Level("Diamond Dust", 8, 9, 6, 28,
                    ".........",
                    "....1....",
                    "...121...",
                    "..12221..",
                    "..12221..",
                    "...121...",
                    "....1....",
                    "........."),

            new Level("Soda Fountain", 8, 8, 6, 46, toys(0, 0, 2, 1),
                    "........",
                    "........",
                    "........",
                    "........",
                    "..1111..",
                    "..1111..",
                    "..1111..",
                    "..1111.."),

            new Level("Gumdrop Grid", 8, 8, 5, 40,
                    "11....11",
                    "11....11",
                    "........",
                    "...11...",
                    "...11...",
                    "........",
                    "11....11",
                    "11....11"),

            new Level("Honeycomb", 8, 9, 6, 36,
                    ".1.....1.",
                    "11.111.11",
                    ".1.....1.",
                    ".........",
                    ".1.....1.",
                    "11.111.11",
                    ".1.....1.",
                    "........."),

            new Level("Candy Corners", 8, 8, 5, 36,
                    "11....11",
                    "11....11",
                    "........",
                    "........",
                    "........",
                    "........",
                    "11....11",
                    "11....11"),

            new Level("Spring Fling", 8, 8, 6, 44, toys(0, 1, 2, 0),
                    "........",
                    "..1111..",
                    "..1..1..",
                    "..1..1..",
                    "..1111..",
                    "........",
                    "........",
                    "........"),

            new Level("Lemon Drop", 9, 9, 6, 50,
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1.",
                    ".1.1.1.1."),

            new Level("Button Box", 9, 9, 6, 48, toys(1, 1, 1, 0),
                    ".........",
                    ".........",
                    ".........",
                    "111111111",
                    ".........",
                    "111111111",
                    ".........",
                    ".........",
                    "........."),

            new Level("Bowl of Jelly", 9, 9, 6, 36,
                    ".........",
                    "..11111..",
                    ".1111111.",
                    ".1122111.",
                    ".1122111.",
                    ".1111111.",
                    "..11111..",
                    ".........",
                    "........."),

            new Level("Wind-Up", 8, 8, 6, 44, toys(1, 1, 0, 1),
                    "11....11",
                    "1......1",
                    "........",
                    "........",
                    "........",
                    "........",
                    "1......1",
                    "11....11"),

            new Level("Marbles", 8, 9, 6, 30,
                    "..1...1..",
                    ".1.1.1.1.",
                    "..1...1..",
                    ".........",
                    "..1...1..",
                    ".1.1.1.1.",
                    "..1...1..",
                    "........."),

            new Level("Snow Globe", 9, 9, 6, 28,
                    ".........",
                    "..11111..",
                    "..1...1..",
                    "..1...1..",
                    "..1...1..",
                    "..1...1..",
                    "..11111..",
                    ".........",
                    "........."),

            new Level("Present Stack", 8, 8, 6, 46, toys(1, 1, 1, 0),
                    "1111....",
                    "1111....",
                    "........",
                    "........",
                    "........",
                    "........",
                    "....1111",
                    "....1111"),

            new Level("Jelly Crown", 9, 9, 6, 64,
                    "111111111",
                    "1.......1",
                    "1.11111.1",
                    "1.1...1.1",
                    "1.1...1.1",
                    "1.11111.1",
                    "1.......1",
                    "111111111",
                    ".........")
    };

    public static int lastIndex() {
        return LEVELS.length - 1;
    }
}

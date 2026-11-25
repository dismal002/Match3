package com.dismal.match3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Grid model for the game: pieces, jelly layers, match groups, gravity and shuffling.
 * Pure Java, no Android dependency, so the rules can be checked on their own.
 */
public final class Board {

    public final int rows;
    public final int cols;
    public final int colors;
    public final Tile[][] grid;
    public final int[][] jelly;

    public int score;
    public int movesLeft;

    private final Random random;
    /** Toys that have not dropped in yet, in drop order. */
    private final List<Toy> toyQueue = new ArrayList<>();

    public Board(Level level, Random random) {
        this.rows = level.rows;
        this.cols = level.cols;
        this.colors = Math.min(level.colors, TileType.values().length);
        this.random = random;
        this.grid = new Tile[rows][cols];
        this.jelly = new int[rows][cols];
        this.score = 0;
        this.movesLeft = level.moves;

        for (Toy toy : Toy.values()) {
            for (int i = 0; i < level.toyCounts[toy.index]; i++) {
                toyQueue.add(toy);
            }
        }
        Collections.shuffle(toyQueue, random);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                jelly[r][c] = level.jellyAt(r, c);
                grid[r][c] = new Tile(randomType(), r, c);
            }
        }
        clearStartingMatches();
        if (!hasMove()) {
            shuffle();
        }
        spawnNextToy();
    }

    // ------------------------------------------------------------------ lookups

    public boolean inBounds(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < cols;
    }

    public Tile at(int r, int c) {
        return inBounds(r, c) ? grid[r][c] : null;
    }

    public Tile cell(int index) {
        int r = index / cols;
        int c = index % cols;
        return inBounds(r, c) ? grid[r][c] : null;
    }

    public int jellyLeft() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (jelly[r][c] > 0) {
                    n++;
                }
            }
        }
        return n;
    }

    public TileType randomType() {
        return TileType.values()[random.nextInt(colors)];
    }

    // ------------------------------------------------------------------ matches

    /** A straight run of three or more equal pieces. */
    private final class Run {
        final TileType type;
        final boolean horizontal;
        final int startR;
        final int startC;
        final int len;

        Run(TileType type, boolean horizontal, int startR, int startC, int len) {
            this.type = type;
            this.horizontal = horizontal;
            this.startR = startR;
            this.startC = startC;
            this.len = len;
        }

        List<Integer> cells() {
            List<Integer> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(horizontal ? startR * cols + startC + i : (startR + i) * cols + startC);
            }
            return out;
        }
    }

    /** One connected group of runs, i.e. a single match made by the player. */
    public static final class Cluster {
        public final Set<Integer> cells = new LinkedHashSet<>();
        public TileType type;
        public int maxLen;
        public boolean hasHorizontal;
        public boolean hasVertical;
        public int spawnR;
        public int spawnC;
    }

    public List<Cluster> findClusters() {
        List<Run> runs = new ArrayList<>();

        for (int r = 0; r < rows; r++) {
            int c = 0;
            while (c < cols) {
                Tile t = grid[r][c];
                if (t == null || !t.isMatchable()) {
                    c++;
                    continue;
                }
                int end = c + 1;
                while (end < cols) {
                    Tile n = grid[r][end];
                    if (n == null || !n.isMatchable() || n.type != t.type) {
                        break;
                    }
                    end++;
                }
                if (end - c >= 3) {
                    runs.add(new Run(t.type, true, r, c, end - c));
                }
                c = end;
            }
        }

        for (int c = 0; c < cols; c++) {
            int r = 0;
            while (r < rows) {
                Tile t = grid[r][c];
                if (t == null || !t.isMatchable()) {
                    r++;
                    continue;
                }
                int end = r + 1;
                while (end < rows) {
                    Tile n = grid[end][c];
                    if (n == null || !n.isMatchable() || n.type != t.type) {
                        break;
                    }
                    end++;
                }
                if (end - r >= 3) {
                    runs.add(new Run(t.type, false, r, c, end - r));
                }
                r = end;
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        if (runs.isEmpty()) {
            return clusters;
        }

        int n = runs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Map<Integer, Integer> owner = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int idx : runs.get(i).cells()) {
                Integer prev = owner.get(idx);
                if (prev == null) {
                    owner.put(idx, i);
                } else {
                    union(parent, i, prev);
                }
            }
        }

        Map<Integer, List<Run>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            List<Run> group = groups.get(root);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(root, group);
            }
            group.add(runs.get(i));
        }

        for (List<Run> group : groups.values()) {
            Cluster cl = new Cluster();
            cl.type = group.get(0).type;
            Run bestH = null;
            Run bestV = null;
            for (Run run : group) {
                cl.cells.addAll(run.cells());
                if (run.horizontal) {
                    cl.hasHorizontal = true;
                    if (bestH == null || run.len > bestH.len) {
                        bestH = run;
                    }
                } else {
                    cl.hasVertical = true;
                    if (bestV == null || run.len > bestV.len) {
                        bestV = run;
                    }
                }
            }
            int hLen = bestH == null ? 0 : bestH.len;
            int vLen = bestV == null ? 0 : bestV.len;
            cl.maxLen = Math.max(hLen, vLen);

            if (bestH != null && bestV != null) {
                Integer shared = null;
                for (int idx : bestH.cells()) {
                    if (bestV.cells().contains(idx)) {
                        shared = idx;
                        break;
                    }
                }
                int spawn = shared != null ? shared : bestH.cells().get(bestH.len / 2);
                cl.spawnR = spawn / cols;
                cl.spawnC = spawn % cols;
            } else if (bestH != null) {
                cl.spawnR = bestH.startR;
                cl.spawnC = bestH.startC + bestH.len / 2;
            } else {
                cl.spawnR = bestV.startR + bestV.len / 2;
                cl.spawnC = bestV.startC;
            }
            clusters.add(cl);
        }
        return clusters;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    // ------------------------------------------------------------------ board moves

    public void swapTiles(int r1, int c1, int r2, int c2) {
        Tile a = grid[r1][c1];
        Tile b = grid[r2][c2];
        grid[r1][c1] = b;
        grid[r2][c2] = a;
        if (a != null) {
            a.row = r2;
            a.col = c2;
        }
        if (b != null) {
            b.row = r1;
            b.col = c1;
        }
    }

    /** Returns the first swap that would create a match, as {r1, c1, r2, c2}. */
    public int[] findHint() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.special == Tile.Special.RAINBOW) {
                    if (c + 1 < cols) {
                        return new int[]{r, c, r, c + 1};
                    }
                    if (r + 1 < rows) {
                        return new int[]{r, c, r + 1, c};
                    }
                }
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (c + 1 < cols && swapCreatesMatch(r, c, r, c + 1)) {
                    return new int[]{r, c, r, c + 1};
                }
                if (r + 1 < rows && swapCreatesMatch(r, c, r + 1, c)) {
                    return new int[]{r, c, r + 1, c};
                }
            }
        }
        // A toy may always be nudged down one cell, which counts as a move.
        for (int r = rows - 2; r >= 0; r--) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.toy != null) {
                    return new int[]{r, c, r + 1, c};
                }
            }
        }
        return null;
    }

    public boolean hasMove() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.special == Tile.Special.RAINBOW) {
                    return true;
                }
            }
        }
        return findHint() != null;
    }

    private boolean swapCreatesMatch(int r1, int c1, int r2, int c2) {
        Tile a = grid[r1][c1];
        Tile b = grid[r2][c2];
        if (a == null || b == null) {
            return false;
        }
        if (a.type == b.type && a.toy == null && b.toy == null) {
            return false;
        }
        if (a.special == Tile.Special.RAINBOW || b.special == Tile.Special.RAINBOW) {
            return true;
        }
        grid[r1][c1] = b;
        grid[r2][c2] = a;
        boolean match = !findClusters().isEmpty();
        grid[r1][c1] = a;
        grid[r2][c2] = b;
        return match;
    }

    /** Compact every column downwards and spawn fresh pieces above the board. */
    public void applyGravity() {
        for (int c = 0; c < cols; c++) {
            int write = rows - 1;
            for (int r = rows - 1; r >= 0; r--) {
                Tile t = grid[r][c];
                if (t == null) {
                    continue;
                }
                if (r != write) {
                    grid[write][c] = t;
                    grid[r][c] = null;
                    t.row = write;
                }
                write--;
            }
            int above = 1;
            for (int r = write; r >= 0; r--) {
                Tile t = new Tile(randomType(), r, c);
                t.animRow = -above;
                t.animCol = c;
                grid[r][c] = t;
                above++;
            }
        }
    }

    public void shuffle() {
        List<Tile> plain = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.special == Tile.Special.NONE && t.toy == null) {
                    plain.add(t);
                }
            }
        }
        List<TileType> pool = new ArrayList<>();
        for (Tile t : plain) {
            pool.add(t.type);
        }
        int guard = 0;
        do {
            Collections.shuffle(pool, random);
            for (int i = 0; i < plain.size(); i++) {
                plain.get(i).type = pool.get(i);
            }
            guard++;
        } while (guard < 300 && (!findClusters().isEmpty() || !hasMove()));

        if (!findClusters().isEmpty() || !hasMove()) {
            for (Tile t : plain) {
                t.type = randomType();
            }
        }
    }

    /** Removes pre-made matches when a level starts, so the board opens quietly. */
    private void clearStartingMatches() {
        int guard = 0;
        while (guard++ < 3000) {
            List<Cluster> clusters = findClusters();
            if (clusters.isEmpty()) {
                return;
            }
            for (Cluster cl : clusters) {
                for (int idx : cl.cells) {
                    int r = idx / cols;
                    int c = idx % cols;
                    if (grid[r][c] != null) {
                        grid[r][c].type = randomType();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ collectibles

    public int toysOnBoard() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.toy != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Toys of one kind still to drop plus toys of that kind still on the board. */
    public int toysLeftOf(Toy kind) {
        int n = 0;
        for (Toy queued : toyQueue) {
            if (queued == kind) {
                n++;
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = grid[r][c];
                if (t != null && t.toy == kind) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Toys still to drop plus toys still on the board. */
    public int toysLeft() {
        return toyQueue.size() + toysOnBoard();
    }

    /** Drops the next queued toy into a free top cell. */
    public Tile spawnNextToy() {
        if (toyQueue.isEmpty()) {
            return null;
        }
        int start = random.nextInt(cols);
        for (int i = 0; i < cols; i++) {
            int c = (start + i) % cols;
            Tile cur = grid[0][c];
            if (cur == null || cur.toy != null || cur.special != Tile.Special.NONE) {
                continue;
            }
            Tile toy = new Tile(cur.type, 0, c);
            toy.toy = toyQueue.remove(0);
            grid[0][c] = toy;
            return toy;
        }
        return null;
    }

    /** Removes every toy that reached the last row. */
    public List<Toy> collectBottomToys() {
        List<Toy> out = new ArrayList<>();
        int last = rows - 1;
        for (int c = 0; c < cols; c++) {
            Tile t = grid[last][c];
            if (t != null && t.toy != null) {
                grid[last][c] = null;
                out.add(t.toy);
            }
        }
        return out;
    }

    public boolean isComplete() {
        return jellyLeft() == 0 && toysLeft() == 0;
    }
}

package com.dismal.match3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** The whole game: board simulation plus canvas drawing and touch handling. */
public final class GameView extends View {

    private enum Phase { IDLE, SWAP, POP, FALL, WON, LOST }

    private static final float SWAP_TIME = 0.15f;
    private static final float POP_TIME = 0.26f;
    private static final float FALL_LIMIT = 5f;
    private static final float HINT_DELAY = 6f;
    /** Sprites carry their own padding, so they are drawn slightly larger than the art box. */
    private static final float SPRITE_FILL = 1.12f;

    private static final int INK = 0xFFFFFFFF;
    private static final int TOY_INK = 0xFFFFE066;
    private static final int WOOD_DARK = 0xFF6B3A14;
    private static final int WOOD_MID = 0xFFB26B2C;
    private static final int CREAM = 0xFFFFF1D0;
    private static final int OUTLINE = 0xFF5A2E0F;
    private static final int TEAL = 0xFF5CCBB8;
    private static final int TEAL_DARK = 0xFF23766B;
    private static final int ORANGE = 0xFFFF9A1F;
    private static final int ORANGE_DARK = 0xFFC2590A;

    private final Random random = new Random();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF box = new RectF();
    private final RectF box2 = new RectF();
    private final Path iconPath = new Path();
    private DashPathEffect dash;
    private final Path starPath = new Path();

    private final Typeface boldTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private final Typeface regularTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    /** Chunky display face for the cartoon lettering; falls back to bold sans on old devices. */
    private final Typeface heavyTypeface = Typeface.create("sans-serif-black", Typeface.NORMAL);

    private Bitmap gameBg;
    private int gameBgW;
    private int gameBgH;
    private Bitmap mapTile;
    private int mapTileW;
    private int mapTileH;
    private float mapWorldH;
    private float mapViewTop;
    private float mapViewBottom;
    private float mapScroll;
    private float mapScrollTarget;
    private float mapDragLastY;
    private float mapDragDist;
    private boolean mapFocusPending = true;
    private Shader mapGradient;
    private float mapGradientH;
    private float shownProgress;

    private Level level;
    private Board board;
    private int levelIndex;

    private Phase phase = Phase.FALL;
    private float phaseTime;
    private float idleTime;
    private long lastNanos;
    private boolean running = true;

    /** The level map is the home screen: first launch and after every clear. */
    private boolean mapMode = true;
    private int unlockedLevels;
    private float mapTime;
    private final float[] nodeX = new float[Level.LEVELS.length];
    private final float[] nodeY = new float[Level.LEVELS.length];
    private final int[] bestStars = new int[Level.LEVELS.length];
    private final RectF playButton = new RectF();
    private final RectF mapButton = new RectF();

    /** The level whose goal card is open on the map, or -1. */
    private int pendingLevel = -1;
    private final RectF dialogPlayButton = new RectF();
    private final RectF dialogCloseButton = new RectF();

    private float boardX;
    private float boardY;
    private float cell;
    private float topInset;
    private float bottomInset;

    private final RectF hintButton = new RectF();
    private final RectF restartButton = new RectF();

    private int selectedR = -1;
    private int selectedC = -1;
    private int pressR = -1;
    private int pressC = -1;
    private float pressX;
    private float pressY;

    private final int[] swapCells = new int[4];
    private Tile swapTileA;
    private Tile swapTileB;
    private final double[] swapFromA = new double[2];
    private final double[] swapFromB = new double[2];
    private boolean reverting;
    private boolean swapHadRainbow;
    private boolean swapHadToy;
    private Tile rainbowTile;
    private Tile otherTile;

    private final Set<Integer> popCells = new LinkedHashSet<>();
    private final List<PendingSpawn> popSpawns = new ArrayList<>();
    private int cascade = 1;

    private int[] hint;
    private String toast;
    private float toastTime;

    private final List<FloatText> texts = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        paint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.LEFT);
        SpriteArt.load(context.getAssets());
        loadLevel(0);
    }

    // ------------------------------------------------------------------ lifecycle

    public void resume() {
        running = true;
        lastNanos = 0L;
        postInvalidateOnAnimation();
    }

    public void pause() {
        running = false;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            topInset = bars.top;
            bottomInset = bars.bottom;
        } else {
            topInset = insets.getSystemWindowInsetTop();
            bottomInset = insets.getSystemWindowInsetBottom();
        }
        invalidate();
        return insets;
    }

    // ------------------------------------------------------------------ level state

    private void loadLevel(int index) {
        levelIndex = Math.max(0, Math.min(index, Level.lastIndex()));
        level = Level.LEVELS[levelIndex];
        board = new Board(level, random);
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t != null) {
                    t.animRow = r - board.rows - 1.5;
                    t.animCol = c;
                    t.vy = 0;
                }
            }
        }
        phase = Phase.FALL;
        phaseTime = 0f;
        cascade = 1;
        popCells.clear();
        popSpawns.clear();
        texts.clear();
        particles.clear();
        selectedR = -1;
        selectedC = -1;
        pressR = -1;
        hint = null;
        toast = null;
        toastTime = 0f;
        shownProgress = 0f;
        swapTileA = null;
        swapTileB = null;
        idleTime = 0f;
        invalidate();
    }

    private void advance() {
        if (phase == Phase.WON) {
            int earned = starsEarned();
            if (earned > bestStars[levelIndex]) {
                bestStars[levelIndex] = earned;
            }
            unlockedLevels = Math.min(Level.LEVELS.length,
                    Math.max(unlockedLevels, levelIndex + 1));
            mapMode = true;
            mapFocusPending = true;
            mapTime = 0f;
            toast = null;
            toastTime = 0f;
            invalidate();
        } else if (phase == Phase.LOST) {
            loadLevel(levelIndex);
        }
    }

    private void startLevel(int index) {
        mapMode = false;
        loadLevel(index);
    }

    private int currentLevel() {
        return Math.min(unlockedLevels, Level.lastIndex());
    }

    /** Three stars for a comfortable clear, two for a tight one, one for any clear. */
    private int starsEarned() {
        if (phase != Phase.WON || level.moves <= 0) {
            return 1;
        }
        float left = board.movesLeft / (float) level.moves;
        if (left >= 0.5f) {
            return 3;
        }
        if (left >= 0.25f) {
            return 2;
        }
        return 1;
    }

    // ------------------------------------------------------------------ simulation

    private void update(float dt) {
        phaseTime += dt;
        switch (phase) {
            case IDLE:
                idleTime += dt;
                if (hint == null && idleTime > HINT_DELAY && selectedR < 0) {
                    hint = board.findHint();
                }
                break;
            case SWAP:
                animateSwap(Math.min(1f, phaseTime / SWAP_TIME));
                if (phaseTime >= SWAP_TIME) {
                    endSwap();
                }
                break;
            case POP:
                for (int idx : popCells) {
                    Tile t = board.cell(idx);
                    if (t != null) {
                        t.pop = Math.min(1.0, phaseTime / POP_TIME);
                    }
                }
                if (phaseTime >= POP_TIME) {
                    finishPop();
                }
                break;
            case FALL:
                animateFall(dt);
                if (fallSettled()) {
                    afterFall();
                } else if (phaseTime > FALL_LIMIT) {
                    for (int r = 0; r < board.rows; r++) {
                        for (int c = 0; c < board.cols; c++) {
                            Tile t = board.grid[r][c];
                            if (t != null) {
                                t.animRow = r;
                                t.animCol = c;
                                t.vy = 0;
                            }
                        }
                    }
                    afterFall();
                }
                break;
            default:
                break;
        }
        updateEffects(dt);
    }

    private void animateSwap(double progress) {
        if (swapTileA == null || swapTileB == null) {
            return;
        }
        double eased = easeOut(progress);
        swapTileA.animRow = lerp(swapFromA[0], swapTileA.row, eased);
        swapTileA.animCol = lerp(swapFromA[1], swapTileA.col, eased);
        swapTileB.animRow = lerp(swapFromB[0], swapTileB.row, eased);
        swapTileB.animCol = lerp(swapFromB[1], swapTileB.col, eased);
    }

    private void animateFall(float dt) {
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t == null) {
                    continue;
                }
                double target = t.row;
                if (t.animRow == target && t.vy == 0) {
                    continue;
                }
                t.vy += 70.0 * dt;
                t.animRow += t.vy * dt;
                if (t.animRow >= target) {
                    if (t.vy > 4.2) {
                        t.animRow = target;
                        t.vy = -t.vy * 0.16;
                    } else {
                        t.animRow = target;
                        t.vy = 0;
                    }
                }
            }
        }
    }

    private boolean fallSettled() {
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t == null) {
                    continue;
                }
                if (Math.abs(t.animRow - t.row) > 0.002 || t.vy != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void afterFall() {
        int last = board.rows - 1;
        boolean picked = false;
        for (int c = 0; c < board.cols; c++) {
            Tile t = board.grid[last][c];
            if (t != null && t.toy != null) {
                picked = true;
                float x = boardX + (c + 0.5f) * cell;
                float y = boardY + (last + 0.5f) * cell;
                texts.add(new FloatText(x, y - dp(6), t.toy.label + " collected", TOY_INK));
                burst(t);
            }
        }
        if (picked) {
            List<Toy> collected = board.collectBottomToys();
            board.score += 250 * collected.size();
            board.applyGravity();
            phase = Phase.FALL;
            phaseTime = 0f;
            return;
        }

        List<Board.Cluster> clusters = board.findClusters();
        if (clusters.isEmpty()) {
            endTurn();
        } else {
            startClear(clusters, -1, -1);
        }
    }

    private void endTurn() {
        cascade = 1;
        hint = null;
        idleTime = 0f;
        if (board.isComplete()) {
            phase = Phase.WON;
            phaseTime = 0f;
            return;
        }
        if (board.movesLeft <= 0) {
            phase = Phase.LOST;
            phaseTime = 0f;
            return;
        }
        if (!board.hasMove()) {
            board.shuffle();
            toast = "No moves left - board reshuffled";
            toastTime = 2.4f;
        }
        if (board.toysOnBoard() == 0) {
            Tile incoming = board.spawnNextToy();
            if (incoming != null) {
                incoming.animRow = -1.6;
                incoming.vy = 0;
                phase = Phase.FALL;
                phaseTime = 0f;
                return;
            }
        }
        phase = Phase.IDLE;
    }

    private void attemptSwap(int r1, int c1, int r2, int c2) {
        if (phase != Phase.IDLE) {
            return;
        }
        Tile a = board.at(r1, c1);
        Tile b = board.at(r2, c2);
        if (a == null || b == null) {
            return;
        }
        swapCells[0] = r1;
        swapCells[1] = c1;
        swapCells[2] = r2;
        swapCells[3] = c2;
        swapHadRainbow = a.special == Tile.Special.RAINBOW || b.special == Tile.Special.RAINBOW;
        swapHadToy = a.toy != null || b.toy != null;
        rainbowTile = a.special == Tile.Special.RAINBOW ? a : (b.special == Tile.Special.RAINBOW ? b : null);
        otherTile = rainbowTile == a ? b : a;
        selectedR = -1;
        selectedC = -1;
        hint = null;
        idleTime = 0f;
        startSwapAnimation(r1, c1, r2, c2, false);
    }

    private void startSwapAnimation(int r1, int c1, int r2, int c2, boolean revert) {
        Tile a = board.at(r1, c1);
        Tile b = board.at(r2, c2);
        if (a == null || b == null) {
            return;
        }
        swapTileA = a;
        swapTileB = b;
        swapFromA[0] = r1;
        swapFromA[1] = c1;
        swapFromB[0] = r2;
        swapFromB[1] = c2;
        reverting = revert;
        a.animRow = r1;
        a.animCol = c1;
        a.vy = 0;
        b.animRow = r2;
        b.animCol = c2;
        b.vy = 0;
        board.swapTiles(r1, c1, r2, c2);
        phase = Phase.SWAP;
        phaseTime = 0f;
    }

    private void endSwap() {
        if (swapTileA != null) {
            swapTileA.animRow = swapTileA.row;
            swapTileA.animCol = swapTileA.col;
            swapTileA.vy = 0;
        }
        if (swapTileB != null) {
            swapTileB.animRow = swapTileB.row;
            swapTileB.animCol = swapTileB.col;
            swapTileB.vy = 0;
        }

        if (reverting) {
            reverting = false;
            swapHadToy = false;
            phase = Phase.IDLE;
            idleTime = 0f;
            hint = null;
            return;
        }

        if (swapHadRainbow) {
            board.movesLeft--;
            cascade = 1;
            hint = null;
            swapHadToy = false;
            beginPop(rainbowClearCells(), new ArrayList<PendingSpawn>());
            return;
        }

        // A toy can be nudged into any neighbouring cell, so it can be walked down
        // to the shelf even when no match is made.
        if (swapHadToy) {
            swapHadToy = false;
            board.movesLeft--;
            cascade = 1;
            hint = null;
            phase = Phase.FALL;
            phaseTime = 0f;
            return;
        }

        List<Board.Cluster> clusters = board.findClusters();
        if (clusters.isEmpty()) {
            startSwapAnimation(swapCells[2], swapCells[3], swapCells[0], swapCells[1], true);
            return;
        }
        board.movesLeft--;
        cascade = 1;
        hint = null;
        startClear(clusters, swapCells[0], swapCells[1]);
    }

    private Set<Integer> rainbowClearCells() {
        Set<Integer> cells = new LinkedHashSet<>();
        if (rainbowTile == null || rainbowTile.special != Tile.Special.RAINBOW) {
            return cells;
        }
        if (otherTile != null && otherTile.special == Tile.Special.RAINBOW) {
            for (int r = 0; r < board.rows; r++) {
                for (int c = 0; c < board.cols; c++) {
                    if (board.grid[r][c] != null) {
                        cells.add(r * board.cols + c);
                    }
                }
            }
            return cells;
        }
        TileType target = otherTile == null ? null : otherTile.type;
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t != null && t.type == target) {
                    cells.add(r * board.cols + c);
                }
            }
        }
        cells.add(rainbowTile.index(board.cols));
        return cells;
    }

    private void startClear(List<Board.Cluster> clusters, int swapR, int swapC) {
        Set<Integer> cells = new LinkedHashSet<>();
        List<PendingSpawn> spawns = new ArrayList<>();
        for (Board.Cluster cl : clusters) {
            cells.addAll(cl.cells);
            Tile.Special special = specialFor(cl);
            if (special != Tile.Special.NONE) {
                int sr = cl.spawnR;
                int sc = cl.spawnC;
                if (swapR >= 0 && cl.cells.contains(swapR * board.cols + swapC)) {
                    sr = swapR;
                    sc = swapC;
                }
                spawns.add(new PendingSpawn(sr, sc, cl.type, special));
            }
        }
        beginPop(cells, spawns);
    }

    private Tile.Special specialFor(Board.Cluster cluster) {
        if (cluster.maxLen >= 5) {
            return Tile.Special.RAINBOW;
        }
        if (cluster.hasHorizontal && cluster.hasVertical) {
            return Tile.Special.BOMB;
        }
        if (cluster.maxLen == 4) {
            return cluster.hasHorizontal ? Tile.Special.COL : Tile.Special.ROW;
        }
        return Tile.Special.NONE;
    }

    private void beginPop(Set<Integer> cells, List<PendingSpawn> spawns) {
        expandSpecials(cells);

        // Toys are immune to matches, blasts and colour bombs.
        Set<Integer> targets = new LinkedHashSet<>();
        int gained = 0;
        float sx = 0f;
        float sy = 0f;
        int count = 0;
        for (int idx : cells) {
            Tile t = board.cell(idx);
            if (t == null || t.clearing || t.toy != null) {
                continue;
            }
            targets.add(idx);
            t.clearing = true;
            t.pop = 0.0;
            gained += 40 * Math.max(1, cascade);
            sx += boardX + (float) ((t.animCol + 0.5) * cell);
            sy += boardY + (float) ((t.animRow + 0.5) * cell);
            count++;
            burst(t);
        }
        board.score += gained;
        if (count > 0) {
            texts.add(new FloatText(sx / count, sy / count - dp(4), "+" + gained, INK));
        }
        popCells.clear();
        popCells.addAll(targets);
        popSpawns.clear();
        popSpawns.addAll(spawns);
        phase = Phase.POP;
        phaseTime = 0f;
    }

    private void expandSpecials(Set<Integer> cells) {
        List<Integer> queue = new ArrayList<>(cells);
        Set<Integer> processed = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            int idx = queue.remove(queue.size() - 1);
            if (!processed.add(idx)) {
                continue;
            }
            Tile t = board.cell(idx);
            if (t == null || t.special == Tile.Special.NONE) {
                continue;
            }
            List<Integer> extra = new ArrayList<>();
            switch (t.special) {
                case ROW:
                    for (int c = 0; c < board.cols; c++) {
                        extra.add(t.row * board.cols + c);
                    }
                    break;
                case COL:
                    for (int r = 0; r < board.rows; r++) {
                        extra.add(r * board.cols + t.col);
                    }
                    break;
                case BOMB:
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int r = t.row + dr;
                            int c = t.col + dc;
                            if (board.inBounds(r, c)) {
                                extra.add(r * board.cols + c);
                            }
                        }
                    }
                    break;
                case RAINBOW:
                    TileType common = mostCommonType();
                    if (common != null) {
                        for (int r = 0; r < board.rows; r++) {
                            for (int c = 0; c < board.cols; c++) {
                                Tile other = board.grid[r][c];
                                if (other != null && other.isMatchable() && other.type == common) {
                                    extra.add(r * board.cols + c);
                                }
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
            for (int e : extra) {
                if (cells.add(e)) {
                    queue.add(e);
                }
            }
        }
    }

    private TileType mostCommonType() {
        int[] counts = new int[TileType.values().length];
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t != null && t.isMatchable()) {
                    counts[t.type.index]++;
                }
            }
        }
        int best = -1;
        TileType bestType = null;
        for (TileType type : TileType.values()) {
            if (counts[type.index] > best) {
                best = counts[type.index];
                bestType = type;
            }
        }
        return bestType;
    }

    private void finishPop() {
        int jellyCleared = 0;
        for (int idx : popCells) {
            int r = idx / board.cols;
            int c = idx % board.cols;
            Tile t = board.grid[r][c];
            if (t != null) {
                t.clearing = false;
                t.pop = 0;
                board.grid[r][c] = null;
            }
            if (board.jelly[r][c] > 0) {
                board.jelly[r][c]--;
                jellyCleared++;
            }
        }
        if (jellyCleared > 0) {
            board.score += 60 * jellyCleared;
        }
        for (PendingSpawn spawn : popSpawns) {
            if (board.grid[spawn.row][spawn.col] == null) {
                Tile t = new Tile(spawn.type, spawn.row, spawn.col);
                t.special = spawn.special;
                t.animRow = spawn.row;
                t.animCol = spawn.col;
                board.grid[spawn.row][spawn.col] = t;
            }
        }
        popCells.clear();
        popSpawns.clear();
        board.applyGravity();
        cascade++;
        phase = Phase.FALL;
        phaseTime = 0f;
    }

    private void updateEffects(float dt) {
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0f) {
                it.remove();
                continue;
            }
            p.vy += 900f * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        for (Iterator<FloatText> it = texts.iterator(); it.hasNext(); ) {
            FloatText t = it.next();
            t.life -= dt;
            t.y -= dp(28) * dt;
            if (t.life <= 0f) {
                it.remove();
            }
        }
        if (toastTime > 0f) {
            toastTime -= dt;
        }
    }

    private void burst(Tile t) {
        float cx = boardX + (float) ((t.animCol + 0.5) * cell);
        float cy = boardY + (float) ((t.animRow + 0.5) * cell);
        for (int i = 0; i < 6; i++) {
            Particle p = new Particle();
            p.x = cx;
            p.y = cy;
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = dp(30) + random.nextDouble() * dp(90);
            p.vx = (float) (Math.cos(angle) * speed);
            p.vy = (float) (Math.sin(angle) * speed) - dp(24);
            p.max = 0.35f + random.nextFloat() * 0.3f;
            p.life = p.max;
            p.color = random.nextBoolean() ? t.type.light : t.type.base;
            p.size = dp(1.5f) + random.nextFloat() * dp(2.5f);
            particles.add(p);
        }
    }

    private void showHint() {
        if (phase != Phase.IDLE) {
            return;
        }
        hint = board.findHint();
        idleTime = 0f;
        if (hint == null) {
            board.shuffle();
            toast = "No moves left - board reshuffled";
            toastTime = 2.4f;
        }
        invalidate();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        if (mapMode) {
            handleMapTouch(event, x, y);
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (phase == Phase.WON || phase == Phase.LOST) {
                    advance();
                    return true;
                }
                if (mapButton.contains(x, y)) {
                    mapMode = true;
                    mapFocusPending = true;
                    mapTime = 0f;
                    toast = null;
                    toastTime = 0f;
                    invalidate();
                    return true;
                }
                if (hintButton.contains(x, y)) {
                    showHint();
                    return true;
                }
                if (restartButton.contains(x, y)) {
                    loadLevel(levelIndex);
                    return true;
                }
                if (phase != Phase.IDLE) {
                    return true;
                }
                {
                    int[] hit = cellAt(x, y);
                    if (hit == null) {
                        return true;
                    }
                    pressR = hit[0];
                    pressC = hit[1];
                    pressX = x;
                    pressY = y;
                    if (selectedR == pressR && selectedC == pressC) {
                        selectedR = -1;
                        selectedC = -1;
                    } else if (selectedR >= 0 && isAdjacent(selectedR, selectedC, pressR, pressC)) {
                        attemptSwap(selectedR, selectedC, pressR, pressC);
                    } else {
                        selectedR = pressR;
                        selectedC = pressC;
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (phase == Phase.IDLE && pressR >= 0) {
                    float dx = x - pressX;
                    float dy = y - pressY;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) > cell * 0.34f) {
                        int nr = pressR;
                        int nc = pressC;
                        if (Math.abs(dx) > Math.abs(dy)) {
                            nc += dx > 0 ? 1 : -1;
                        } else {
                            nr += dy > 0 ? 1 : -1;
                        }
                        if (board.inBounds(nr, nc)) {
                            attemptSwap(pressR, pressC, nr, nc);
                        }
                        pressR = -1;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressR = -1;
                return true;

            default:
                return true;
        }
    }

    /** The long trail scrolls, so the map takes taps for stops and drags for the view. */
    private void handleMapTouch(MotionEvent event, float x, float y) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mapDragLastY = y;
                mapDragDist = 0f;
                return;
            case MotionEvent.ACTION_MOVE:
                if (pendingLevel >= 0) {
                    return;
                }
                float dy = mapDragLastY - y;
                mapDragLastY = y;
                mapDragDist += Math.abs(dy);
                mapScroll += dy;
                mapScrollTarget += dy;
                clampMapScroll();
                invalidate();
                return;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mapDragDist < dp(9)) {
                    handleMapTap(x, y);
                }
                mapDragDist = 0f;
                return;
            default:
                return;
        }
    }

    private void handleMapTap(float x, float y) {
        if (pendingLevel >= 0) {
            if (dialogPlayButton.contains(x, y)) {
                int chosen = pendingLevel;
                pendingLevel = -1;
                startLevel(chosen);
            } else if (dialogCloseButton.contains(x, y)) {
                pendingLevel = -1;
            }
            return;
        }
        if (playButton.contains(x, y)) {
            pendingLevel = currentLevel();
            return;
        }
        if (y < mapViewTop || y > mapViewBottom) {
            return;
        }
        float worldY = y - mapViewTop + mapScroll;
        for (int i = 0; i < Level.LEVELS.length; i++) {
            if (Math.hypot(x - nodeX[i], worldY - nodeY[i]) > dp(34)) {
                continue;
            }
            if (i <= unlockedLevels) {
                pendingLevel = i;
            } else {
                toast = "Clear level " + (currentLevel() + 1) + " first";
                toastTime = 2f;
            }
            return;
        }
    }

    private int[] cellAt(float x, float y) {
        if (cell <= 0f) {
            return null;
        }
        int c = (int) Math.floor((x - boardX) / cell);
        int r = (int) Math.floor((y - boardY) / cell);
        if (r < 0 || r >= board.rows || c < 0 || c >= board.cols) {
            return null;
        }
        return new int[]{r, c};
    }

    private static boolean isAdjacent(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2) == 1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (mapMode) {
            if (running) {
                mapTime += dt;
                if (toastTime > 0f) {
                    toastTime -= dt;
                }
            }
            drawMap(canvas, w, h);
            if (running) {
                postInvalidateOnAnimation();
            }
            return;
        }

        if (running) {
            update(dt);
        }

        drawBackground(canvas, w, h);
        layoutBoard(w, h);
        drawBoard(canvas);
        drawHud(canvas, w);
        drawButtons(canvas, w, h);
        drawEffects(canvas);
        if (phase == Phase.WON || phase == Phase.LOST) {
            drawOverlay(canvas, w, h);
        }

        if (running) {
            postInvalidateOnAnimation();
        }
    }

    private void layoutBoard(int w, int h) {
        float side = dp(12);
        float header = topInset + dp(126);
        float footer = bottomInset + dp(104);
        float availW = w - side * 2f;
        float availH = Math.max(dp(140), h - header - footer);
        cell = Math.min(availW / level.cols, availH / level.rows);
        cell = Math.max(dp(22), cell);
        boardX = (w - cell * board.cols) / 2f;
        boardY = header + Math.max(0f, (availH - cell * board.rows) / 2f);
    }

    private void drawBoard(Canvas canvas) {
        float bw = cell * board.cols;
        float bh = cell * board.rows;
        float pad = dp(9);

        rect.set(boardX - pad, boardY - pad, boardX + bw + pad, boardY + bh + pad);
        paint.setShader(null);
        paint.setColor(0xCC1E3158);
        canvas.drawRoundRect(rect, dp(20), dp(20), paint);
        strokePaint.setColor(0xAAFFFFFF);
        strokePaint.setStrokeWidth(Math.max(1.5f, dp(2f)));
        canvas.drawRoundRect(rect, dp(20), dp(20), strokePaint);

        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                float x = boardX + c * cell;
                float y = boardY + r * cell;
                float inset = dp(2.5f);
                rect.set(x + inset, y + inset, x + cell - inset, y + cell - inset);
                paint.setColor(((r + c) % 2 == 0) ? 0x38FFFFFF : 0x1CFFFFFF);
                canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, paint);
                int layers = board.jelly[r][c];
                if (layers > 0) {
                    drawJelly(canvas, r, c, layers);
                }
            }
        }

        if (hint != null && phase == Phase.IDLE) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(phaseTime * 5.0);
            strokePaint.setColor(0xFFFFFFFF);
            strokePaint.setAlpha((int) (60 + 120 * pulse));
            strokePaint.setStrokeWidth(Math.max(2f, dp(2.4f)));
            strokeCell(canvas, hint[0], hint[1]);
            strokeCell(canvas, hint[2], hint[3]);
            strokePaint.setAlpha(255);
        }

        Canvas tileCanvas = canvas;
        tileCanvas.save();
        tileCanvas.clipRect(boardX - 3f, boardY - 3f, boardX + bw + 3f, boardY + bh + 3f);
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Tile t = board.grid[r][c];
                if (t != null) {
                    drawTile(tileCanvas, t);
                }
            }
        }
        tileCanvas.restore();

        if (level.hasToys()) {
            // The shelf under the board shows where toys are collected.
            paint.setShader(null);
            paint.setColor(0x66FFB37B);
            rect.set(boardX + cell * 0.16f, boardY + bh + dp(2), boardX + bw - cell * 0.16f, boardY + bh + dp(6));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
            paint.setColor(0x33FFB37B);
            rect.set(boardX + cell * 0.30f, boardY + bh + dp(7), boardX + bw - cell * 0.30f, boardY + bh + dp(9));
            canvas.drawRoundRect(rect, dp(1), dp(1), paint);
        }

        if (selectedR >= 0) {
            strokePaint.setColor(0xFFFFFFFF);
            strokePaint.setAlpha(235);
            strokePaint.setStrokeWidth(Math.max(2f, dp(2.4f)));
            strokeCell(canvas, selectedR, selectedC);
            strokePaint.setAlpha(255);
        }
    }

    private void strokeCell(Canvas canvas, int r, int c) {
        float inset = dp(2.5f);
        rect.set(boardX + c * cell + inset, boardY + r * cell + inset,
                boardX + (c + 1) * cell - inset, boardY + (r + 1) * cell - inset);
        float radius = cell * 0.22f;
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    private void drawJelly(Canvas canvas, int r, int c, int layers) {
        float pad = cell * 0.07f;
        float left = boardX + c * cell + pad;
        float top = boardY + r * cell + pad;
        float right = boardX + (c + 1) * cell - pad;
        float bottom = boardY + (r + 1) * cell - pad;
        float radius = cell * 0.26f;

        paint.setColor(0x22BFFFF4);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);

        if (layers >= 2) {
            paint.setColor(0x8C5FE2D2);
            canvas.drawRoundRect(rect, radius, radius, paint);
            strokePaint.setColor(0xD9C9FFF7);
            strokePaint.setStrokeWidth(Math.max(2f, cell * 0.035f));
            float inset = cell * 0.13f;
            rect.set(left + inset, top + inset, right - inset, bottom - inset);
            canvas.drawRoundRect(rect, radius * 0.7f, radius * 0.7f, strokePaint);
        } else {
            paint.setColor(0x665FE2D2);
            canvas.drawRoundRect(rect, radius, radius, paint);
            strokePaint.setColor(0xB3A8F5EA);
            strokePaint.setStrokeWidth(Math.max(2f, cell * 0.03f));
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
    }

    private void drawTile(Canvas canvas, Tile t) {
        float cx = boardX + (float) ((t.animCol + 0.5) * cell);
        float cy = boardY + (float) ((t.animRow + 0.5) * cell);
        float size = cell * 0.78f;
        int alpha = 255;
        float popScale = 1f;
        if (t.clearing) {
            float p = (float) Math.min(1.0, t.pop);
            popScale = 1f + 0.5f * p;
            alpha = (int) (255 * Math.max(0f, 1f - p));
        }

        paint.setShader(null);
        if (t.toy == null && t.special == Tile.Special.RAINBOW) {
            // The sprites bring their own ground shadow; only the rainbow disc needs one.
            paint.setColor(0x33000000);
            float shadowW = size * 0.34f * popScale;
            rect.set(cx - shadowW, cy + size * 0.22f, cx + shadowW, cy + size * 0.5f);
            canvas.drawOval(rect, paint);
        }

        canvas.save();
        canvas.translate(cx, cy);
        if (popScale != 1f) {
            canvas.scale(popScale, popScale);
        }

        if (t.toy != null) {
            // A warm halo marks a collectible as something other than a piece.
            paint.setShader(null);
            paint.setColor(0x1AFFE9BF);
            paint.setAlpha(alpha);
            canvas.drawCircle(0f, 0f, size * 0.58f, paint);
            strokePaint.setShader(null);
            strokePaint.setColor(0x80FFD79B);
            strokePaint.setAlpha(alpha);
            strokePaint.setStrokeWidth(Math.max(1.5f, size * 0.03f));
            canvas.drawCircle(0f, 0f, size * 0.58f, strokePaint);
            SpriteArt.drawToy(canvas, t.toy, size * SPRITE_FILL, alpha);
            if (t.row < board.rows - 1) {
                strokePaint.setColor(0x99000000);
                strokePaint.setStrokeWidth(Math.max(2f, size * 0.09f));
                canvas.drawLine(-size * 0.10f, size * 0.56f, 0f, size * 0.65f, strokePaint);
                canvas.drawLine(0f, size * 0.65f, size * 0.10f, size * 0.56f, strokePaint);
                strokePaint.setColor(0xFFF6EAD2);
                strokePaint.setStrokeWidth(Math.max(1.5f, size * 0.05f));
                canvas.drawLine(-size * 0.10f, size * 0.56f, 0f, size * 0.65f, strokePaint);
                canvas.drawLine(0f, size * 0.65f, size * 0.10f, size * 0.56f, strokePaint);
            }
        } else if (t.special == Tile.Special.RAINBOW) {
            drawRainbowTile(canvas, 0f, 0f, size, alpha);
        } else {
            SpriteArt.drawFruit(canvas, t.type, size * SPRITE_FILL, alpha);
            if (t.special != Tile.Special.NONE) {
                drawSpecialGlyph(canvas, t, 0f, 0f, size, alpha);
            }
        }
        canvas.restore();
    }

    private void drawRainbowTile(Canvas canvas, float cx, float cy, float size, int alpha) {
        paint.setShader(null);
        paint.setColor(0xFF171029);
        paint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, size * 0.48f, paint);

        strokePaint.setStrokeWidth(Math.max(2f, size * 0.1f));
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
        for (int k = 0; k < 10; k++) {
            float[] hsv = {(k * 36f) % 360f, 0.72f, 1f};
            strokePaint.setColor(Color.HSVToColor(alpha, hsv));
            rect.set(cx - size * 0.32f, cy - size * 0.32f, cx + size * 0.32f, cy + size * 0.32f);
            canvas.drawArc(rect, k * 36f + phaseTime * 70f, 25f, false, strokePaint);
        }
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(alpha);
        canvas.drawCircle(cx - size * 0.05f, cy - size * 0.08f, size * 0.06f, paint);
        paint.setAlpha(255);
    }

    private void drawSpecialGlyph(Canvas canvas, Tile t, float cx, float cy, float size, int alpha) {
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setAlpha(alpha);
        strokePaint.setStrokeWidth(Math.max(2f, size * 0.07f));
        switch (t.special) {
            case ROW:
                canvas.drawLine(cx - size * 0.23f, cy, cx + size * 0.23f, cy, strokePaint);
                canvas.drawLine(cx - size * 0.23f, cy, cx - size * 0.11f, cy - size * 0.10f, strokePaint);
                canvas.drawLine(cx - size * 0.23f, cy, cx - size * 0.11f, cy + size * 0.10f, strokePaint);
                canvas.drawLine(cx + size * 0.23f, cy, cx + size * 0.11f, cy - size * 0.10f, strokePaint);
                canvas.drawLine(cx + size * 0.23f, cy, cx + size * 0.11f, cy + size * 0.10f, strokePaint);
                break;
            case COL:
                canvas.drawLine(cx, cy - size * 0.23f, cx, cy + size * 0.23f, strokePaint);
                canvas.drawLine(cx, cy - size * 0.23f, cx - size * 0.10f, cy - size * 0.11f, strokePaint);
                canvas.drawLine(cx, cy - size * 0.23f, cx + size * 0.10f, cy - size * 0.11f, strokePaint);
                canvas.drawLine(cx, cy + size * 0.23f, cx - size * 0.10f, cy + size * 0.11f, strokePaint);
                canvas.drawLine(cx, cy + size * 0.23f, cx + size * 0.10f, cy + size * 0.11f, strokePaint);
                break;
            case BOMB:
                rect.set(cx - size * 0.20f, cy - size * 0.20f, cx + size * 0.20f, cy + size * 0.20f);
                canvas.drawOval(rect, strokePaint);
                for (int k = 0; k < 4; k++) {
                    double angle = k * Math.PI / 2.0;
                    canvas.drawCircle(cx + (float) Math.cos(angle) * size * 0.27f,
                            cy + (float) Math.sin(angle) * size * 0.27f,
                            Math.max(1.5f, size * 0.045f), strokePaint);
                }
                break;
            default:
                break;
        }
        strokePaint.setAlpha(255);
    }

    // ------------------------------------------------------------------ map

    private static int jellyCells(Level target) {
        int count = 0;
        for (int r = 0; r < target.rows; r++) {
            for (int c = 0; c < target.cols; c++) {
                if (target.jellyAt(r, c) > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private void traceStar(float cx, float cy, float radius) {
        starPath.reset();
        for (int i = 0; i < 10; i++) {
            float r = (i % 2 == 0) ? radius : radius * 0.46f;
            double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
            float px = cx + (float) Math.cos(angle) * r;
            float py = cy + (float) Math.sin(angle) * r;
            if (i == 0) {
                starPath.moveTo(px, py);
            } else {
                starPath.lineTo(px, py);
            }
        }
        starPath.close();
    }

    // ------------------------------------------------------------------ look and feel

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha * (color >>> 24) / 255));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Chunky cartoon lettering: a dark outline under a bright fill. y is the baseline. */
    private void outlinedText(Canvas canvas, String s, float x, float y, float size,
                              int fill, int outline, Paint.Align align, int alpha) {
        textPaint.setTypeface(heavyTypeface);
        textPaint.setTextAlign(align);
        textPaint.setTextSize(size);
        textPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeWidth(Math.max(2f, size * 0.24f));
        textPaint.setColor(withAlpha(outline, alpha));
        canvas.drawText(s, x, y, textPaint);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(withAlpha(fill, alpha));
        canvas.drawText(s, x, y, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void centeredText(Canvas canvas, String s, float cx, float cy, float size,
                              int fill, int outline) {
        textPaint.setTypeface(heavyTypeface);
        textPaint.setTextSize(size);
        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        outlinedText(canvas, s, cx, baseline, size, fill, outline, Paint.Align.CENTER, 255);
    }

    /** Flat text with no outline, for use on cream panels. */
    private void plainText(Canvas canvas, String s, float x, float cy, float size, int color,
                           Paint.Align align) {
        textPaint.setTypeface(boldTypeface);
        textPaint.setTextAlign(align);
        textPaint.setTextSize(size);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(color);
        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(s, x, baseline, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void woodPanel(Canvas canvas, RectF area, float radius) {
        paint.setShader(null);
        paint.setColor(0x44000000);
        box.set(area.left, area.top + dp(2.5f), area.right, area.bottom + dp(2.5f));
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setColor(WOOD_DARK);
        canvas.drawRoundRect(area, radius, radius, paint);
        float in = dp(2.2f);
        paint.setColor(WOOD_MID);
        box.set(area.left + in, area.top + in, area.right - in, area.bottom - in);
        canvas.drawRoundRect(box, radius * 0.8f, radius * 0.8f, paint);
        paint.setColor(0x33FFD9A0);
        box.set(area.left + in, area.top + in, area.right - in, area.centerY());
        canvas.drawRoundRect(box, radius * 0.8f, radius * 0.8f, paint);
    }

    private void woodCircle(Canvas canvas, float cx, float cy, float r) {
        paint.setShader(null);
        paint.setColor(0x44000000);
        canvas.drawCircle(cx, cy + dp(3), r, paint);
        paint.setColor(WOOD_DARK);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(WOOD_MID);
        canvas.drawCircle(cx, cy, r - dp(2.6f), paint);
        paint.setColor(0x33FFD9A0);
        box.set(cx - r * 0.78f, cy - r * 0.86f, cx + r * 0.78f, cy - r * 0.05f);
        canvas.drawOval(box, paint);
    }

    /** Chunky candy button: dark base, bright face, gloss and a light rim. */
    private void bigButton(Canvas canvas, RectF area, String label, int face, int base, float textSize) {
        float radius = area.height() * 0.36f;
        paint.setShader(null);
        paint.setColor(0x55000000);
        box.set(area.left, area.top + dp(5), area.right, area.bottom + dp(5));
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setColor(base);
        box.set(area.left, area.top + dp(3), area.right, area.bottom + dp(3));
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setColor(face);
        canvas.drawRoundRect(area, radius, radius, paint);
        paint.setColor(0x44FFFFFF);
        box.set(area.left + dp(5), area.top + dp(4), area.right - dp(5), area.centerY());
        canvas.drawRoundRect(box, radius * 0.7f, radius * 0.7f, paint);
        strokePaint.setColor(0xCCFFFFFF);
        strokePaint.setStrokeWidth(Math.max(1.5f, dp(2f)));
        canvas.drawRoundRect(area, radius, radius, strokePaint);
        centeredText(canvas, label, area.centerX(), area.centerY(), textSize, 0xFFFFFFFF, base);
    }

    private void stitch(Canvas canvas, RectF area, float radius, int color) {
        if (dash == null) {
            dash = new DashPathEffect(new float[]{dp(5), dp(4)}, 0f);
        }
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(Math.max(1.5f, dp(1.6f)));
        strokePaint.setPathEffect(dash);
        canvas.drawRoundRect(area, radius, radius, strokePaint);
        strokePaint.setPathEffect(null);
    }

    /** The teal dialog card shared by the goal card and the result card. */
    private void cardFrame(Canvas canvas, float left, float top, float right, float bottom) {
        float rad = dp(26);
        paint.setShader(null);
        paint.setColor(0x55000000);
        box.set(left, top + dp(6), right, bottom + dp(6));
        canvas.drawRoundRect(box, rad, rad, paint);
        paint.setColor(TEAL_DARK);
        box.set(left, top, right, bottom);
        canvas.drawRoundRect(box, rad, rad, paint);
        paint.setColor(TEAL);
        box.set(left + dp(4), top + dp(4), right - dp(4), bottom - dp(4));
        canvas.drawRoundRect(box, rad - dp(3), rad - dp(3), paint);
        paint.setColor(0x30FFFFFF);
        box.set(left + dp(4), top + dp(4), right - dp(4), top + (bottom - top) * 0.42f);
        canvas.drawRoundRect(box, rad - dp(3), rad - dp(3), paint);
    }

    /** Stitched ribbon that sits across the top edge of a card. */
    private void ribbon(Canvas canvas, float cx, float cy, float width, float height,
                        String label, float textSize) {
        float rad = height * 0.45f;
        paint.setShader(null);
        paint.setColor(0x55000000);
        box.set(cx - width / 2f, cy - height / 2f + dp(4), cx + width / 2f, cy + height / 2f + dp(4));
        canvas.drawRoundRect(box, rad, rad, paint);
        paint.setColor(TEAL_DARK);
        box.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
        canvas.drawRoundRect(box, rad, rad, paint);
        paint.setColor(0xFF3CB5A2);
        box2.set(box.left + dp(3), box.top + dp(3), box.right - dp(3), box.bottom - dp(3));
        canvas.drawRoundRect(box2, rad * 0.85f, rad * 0.85f, paint);
        paint.setColor(0x33FFFFFF);
        box2.set(box.left + dp(3), box.top + dp(3), box.right - dp(3), box.centerY());
        canvas.drawRoundRect(box2, rad * 0.85f, rad * 0.85f, paint);
        box2.set(box.left + dp(8), box.top + dp(8), box.right - dp(8), box.bottom - dp(8));
        stitch(canvas, box2, rad * 0.6f, 0xCCFFFFFF);
        centeredText(canvas, label, cx, cy, textSize, 0xFFFFFFFF, TEAL_DARK);
    }

    private void drawStar(Canvas canvas, float cx, float cy, float radius, boolean earned) {
        paint.setShader(null);
        traceStar(cx, cy + dp(1.6f), radius + dp(1.5f));
        paint.setColor(0x55000000);
        canvas.drawPath(starPath, paint);
        traceStar(cx, cy, radius);
        paint.setColor(earned ? 0xFFFFC82E : 0xFFD9CDB2);
        canvas.drawPath(starPath, paint);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(earned ? 0xFFB86B00 : 0xFF9C8F74);
        strokePaint.setStrokeWidth(Math.max(1.5f, radius * 0.16f));
        canvas.drawPath(starPath, strokePaint);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
        paint.setColor(earned ? 0xAAFFFFFF : 0x66FFFFFF);
        canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.22f, radius * 0.14f, paint);
    }

    private void drawJellyIcon(Canvas canvas, float cx, float cy, float size) {
        float half = size / 2f;
        box.set(cx - half, cy - half, cx + half, cy + half);
        paint.setShader(null);
        paint.setColor(0xFF2A9C8F);
        canvas.drawRoundRect(box, size * 0.28f, size * 0.28f, paint);
        paint.setColor(0xFF63E6D5);
        box2.set(box.left + size * 0.07f, box.top + size * 0.07f, box.right - size * 0.07f, box.bottom - size * 0.07f);
        canvas.drawRoundRect(box2, size * 0.22f, size * 0.22f, paint);
        paint.setColor(0x88FFFFFF);
        box2.set(box.left + size * 0.18f, box.top + size * 0.16f, box.centerX() + size * 0.05f, box.top + size * 0.38f);
        canvas.drawRoundRect(box2, size * 0.1f, size * 0.1f, paint);
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float r) {
        paint.setShader(null);
        paint.setColor(0xFF2FA845);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(0xFF4FD266);
        canvas.drawCircle(cx, cy, r * 0.82f, paint);
        iconPath.reset();
        iconPath.moveTo(cx - r * 0.45f, cy);
        iconPath.lineTo(cx - r * 0.1f, cy + r * 0.38f);
        iconPath.lineTo(cx + r * 0.5f, cy - r * 0.34f);
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeWidth(Math.max(2f, r * 0.3f));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(iconPath, strokePaint);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
    }

    private static final class Goal {
        /** Null means the jelly goal. */
        final Toy toy;
        final int left;

        Goal(Toy toy, int left) {
            this.toy = toy;
            this.left = left;
        }
    }

    private List<Goal> goals() {
        List<Goal> out = new ArrayList<>();
        if (level.hasJelly) {
            out.add(new Goal(null, board.jellyLeft()));
        }
        for (Toy toy : Toy.values()) {
            if (level.toyCounts[toy.index] > 0) {
                out.add(new Goal(toy, board.toysLeftOf(toy)));
            }
        }
        return out;
    }

    private void drawGoalIcon(Canvas canvas, Toy toy, float cx, float cy, float size) {
        if (toy == null) {
            drawJellyIcon(canvas, cx, cy, size * 0.8f);
        } else {
            canvas.save();
            canvas.translate(cx, cy);
            SpriteArt.drawToy(canvas, toy, size, 255);
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ scenery

    private static void cloud(Canvas c, Paint p, float x, float y, float s) {
        c.drawCircle(x, y, s * 0.5f, p);
        c.drawCircle(x + s * 0.46f, y + s * 0.1f, s * 0.4f, p);
        c.drawCircle(x - s * 0.46f, y + s * 0.12f, s * 0.34f, p);
        c.drawRoundRect(x - s * 0.8f, y + s * 0.18f, x + s * 0.88f, y + s * 0.52f, s * 0.2f, s * 0.2f, p);
    }

    private static void bush(Canvas c, Paint p, float x, float y, float r) {
        p.setColor(0x33000000);
        c.drawOval(x - r * 0.95f, y + r * 0.55f, x + r * 0.95f, y + r * 1.12f, p);
        p.setColor(0xFF2E8B34);
        c.drawCircle(x, y, r, p);
        p.setColor(0xFF45AA3C);
        c.drawCircle(x - r * 0.16f, y - r * 0.2f, r * 0.74f, p);
        p.setColor(0xFF66C84F);
        c.drawCircle(x - r * 0.34f, y - r * 0.42f, r * 0.3f, p);
    }

    private static void flower(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setColor(color);
        for (int k = 0; k < 5; k++) {
            double a = k * Math.PI * 2.0 / 5.0;
            c.drawCircle(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r, r * 0.8f, p);
        }
        p.setColor(0xFFFFE066);
        c.drawCircle(x, y, r * 0.7f, p);
    }

    private Bitmap buildGameBackground(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(11);

        p.setShader(new LinearGradient(0f, 0f, 0f, h * 0.5f, 0xFF4FB4EE, 0xFFD2F1FF, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, w, h, p);
        p.setShader(null);

        p.setColor(0xDDFFFFFF);
        cloud(c, p, w * 0.2f, h * 0.15f, w * 0.17f);
        cloud(c, p, w * 0.74f, h * 0.1f, w * 0.14f);
        cloud(c, p, w * 0.52f, h * 0.24f, w * 0.1f);

        // Far hills with a windmill.
        Path hill = new Path();
        hill.moveTo(0f, h * 0.3f);
        hill.cubicTo(w * 0.25f, h * 0.22f, w * 0.55f, h * 0.35f, w, h * 0.26f);
        hill.lineTo(w, h);
        hill.lineTo(0f, h);
        hill.close();
        p.setColor(0xFF9BDB74);
        c.drawPath(hill, p);

        float mx = w * 0.14f;
        float my = h * 0.285f;
        float ms = w * 0.075f;
        Path tower = new Path();
        tower.moveTo(mx - ms * 0.5f, my);
        tower.lineTo(mx + ms * 0.5f, my);
        tower.lineTo(mx + ms * 0.32f, my - ms * 1.5f);
        tower.lineTo(mx - ms * 0.32f, my - ms * 1.5f);
        tower.close();
        p.setColor(0xFFF3E6C8);
        c.drawPath(tower, p);
        p.setColor(0xFF4A7BD0);
        Path roof = new Path();
        roof.moveTo(mx - ms * 0.42f, my - ms * 1.5f);
        roof.lineTo(mx, my - ms * 2.05f);
        roof.lineTo(mx + ms * 0.42f, my - ms * 1.5f);
        roof.close();
        c.drawPath(roof, p);
        p.setColor(0xFF7A4A22);
        c.drawRoundRect(mx - ms * 0.12f, my - ms * 0.42f, mx + ms * 0.12f, my, ms * 0.08f, ms * 0.08f, p);
        Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG);
        blade.setStyle(Paint.Style.STROKE);
        blade.setStrokeCap(Paint.Cap.ROUND);
        blade.setStrokeWidth(Math.max(2f, ms * 0.14f));
        blade.setColor(0xFFFFFFFF);
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4.0 + k * Math.PI / 2.0;
            c.drawLine(mx, my - ms * 1.2f, mx + (float) Math.cos(a) * ms * 1.1f,
                    my - ms * 1.2f + (float) Math.sin(a) * ms * 1.1f, blade);
        }
        p.setColor(0xFF7A4A22);
        c.drawCircle(mx, my - ms * 1.2f, ms * 0.1f, p);

        bush(c, p, w * 0.82f, h * 0.285f, w * 0.045f);
        bush(c, p, w * 0.9f, h * 0.3f, w * 0.036f);
        bush(c, p, w * 0.5f, h * 0.315f, w * 0.032f);

        // Near meadow.
        Path meadow = new Path();
        meadow.moveTo(0f, h * 0.4f);
        meadow.cubicTo(w * 0.3f, h * 0.34f, w * 0.7f, h * 0.45f, w, h * 0.37f);
        meadow.lineTo(w, h);
        meadow.lineTo(0f, h);
        meadow.close();
        p.setShader(new LinearGradient(0f, h * 0.34f, 0f, h, 0xFF78CE4F, 0xFF4BA83A, Shader.TileMode.CLAMP));
        c.drawPath(meadow, p);
        p.setShader(null);

        p.setColor(0x1EFFFFFF);
        for (int i = 0; i < 9; i++) {
            float ox = rnd.nextFloat() * w;
            float oy = h * (0.45f + rnd.nextFloat() * 0.55f);
            c.drawOval(ox - w * 0.2f, oy - h * 0.012f, ox + w * 0.2f, oy + h * 0.012f, p);
        }

        // Side bushes frame the board, flowers fill the grass below it.
        bush(c, p, w * 0.03f, h * 0.5f, w * 0.07f);
        bush(c, p, w * 0.97f, h * 0.6f, w * 0.08f);
        bush(c, p, w * 0.02f, h * 0.8f, w * 0.08f);
        bush(c, p, w * 0.98f, h * 0.9f, w * 0.07f);
        int[] petals = {0xFFFFFFFF, 0xFFFF8FB8, 0xFFFFD84D, 0xFFB79BFF};
        for (int i = 0; i < 40; i++) {
            float fx = rnd.nextFloat() * w;
            float fy = h * (0.42f + rnd.nextFloat() * 0.56f);
            flower(c, p, fx, fy, dp(2.4f), petals[i % petals.length]);
        }
        return bmp;
    }

    /** One tile of scenery; the map repeats it along the scrolling trail. */
    private Bitmap buildMapTile(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(5);

        p.setColor(0x24FFFFFF);
        for (int i = 0; i < 3; i++) {
            float ox = rnd.nextFloat() * w;
            float oy = dp(80) + rnd.nextFloat() * (h - dp(160));
            c.drawOval(ox - w * 0.22f, oy - dp(9), ox + w * 0.22f, oy + dp(9), p);
        }

        // Bushes along both edges of the tile.
        for (int i = 0; i < 7; i++) {
            boolean left = i % 2 == 0;
            float bx = left ? rnd.nextFloat() * w * 0.15f : w - rnd.nextFloat() * w * 0.15f;
            float by = dp(70) + rnd.nextFloat() * (h - dp(140));
            bush(c, p, bx, by, dp(20) + rnd.nextFloat() * dp(16));
        }

        int[] petals = {0xFFFFFFFF, 0xFFFF8FB8, 0xFFFFD84D, 0xFFB79BFF};
        for (int i = 0; i < 16; i++) {
            float fx = rnd.nextFloat() * w;
            float fy = dp(60) + rnd.nextFloat() * (h - dp(120));
            if (Math.abs(fx - w / 2f) < w * 0.20f) {
                continue;
            }
            flower(c, p, fx, fy, dp(2.4f), petals[i % petals.length]);
        }
        return bmp;
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        if (gameBg == null || gameBgW != w || gameBgH != h) {
            gameBg = buildGameBackground(w, h);
            gameBgW = w;
            gameBgH = h;
        }
        canvas.drawBitmap(gameBg, 0f, 0f, null);
    }

    // ------------------------------------------------------------------ playing screen

    private void drawHud(Canvas canvas, int w) {
        float pad = dp(10);
        float y0 = topInset + dp(8);
        float panelH = dp(70);
        float cr = dp(34);
        float cx = w / 2f;
        float cy = y0 + panelH / 2f;
        List<Goal> goals = goals();

        // Target panel with the remaining goals.
        rect.set(pad, y0, cx - cr + dp(6), y0 + panelH);
        woodPanel(canvas, rect, dp(14));
        centeredText(canvas, "Target", rect.centerX() - dp(3), y0 + dp(12), dp(11), CREAM, OUTLINE);
        int count = goals.size();
        float avail = rect.width() - dp(18);
        float slot = Math.min(dp(46), avail / Math.max(1, count));
        float startX = rect.left + dp(7) + (avail - slot * count) / 2f + slot / 2f;
        float iconSize = Math.min(slot * 0.85f, dp(30));
        for (int i = 0; i < count; i++) {
            Goal g = goals.get(i);
            float gx = startX + slot * i;
            drawGoalIcon(canvas, g.toy, gx, y0 + dp(33), iconSize);
            if (g.left <= 0) {
                drawCheck(canvas, gx, y0 + dp(57), dp(8));
            } else {
                centeredText(canvas, Integer.toString(g.left), gx, y0 + dp(57), dp(14), 0xFFFFFFFF, OUTLINE);
            }
        }

        // Score and moves.
        float rl = cx + cr - dp(6);
        float half = (panelH - dp(4)) / 2f;
        rect.set(rl, y0, w - pad, y0 + half);
        woodPanel(canvas, rect, dp(12));
        centeredText(canvas, "Score", rect.centerX() + dp(3), rect.top + dp(9), dp(9.5f), CREAM, OUTLINE);
        centeredText(canvas, Integer.toString(board.score), rect.centerX() + dp(3), rect.top + dp(23), dp(15), 0xFFFFFFFF, OUTLINE);
        rect.set(rl, y0 + half + dp(4), w - pad, y0 + panelH);
        woodPanel(canvas, rect, dp(12));
        centeredText(canvas, "Moves", rect.centerX() + dp(3), rect.top + dp(9), dp(9.5f), CREAM, OUTLINE);
        int moves = Math.max(0, board.movesLeft);
        centeredText(canvas, Integer.toString(moves), rect.centerX() + dp(3), rect.top + dp(23), dp(15),
                moves <= 3 ? 0xFFFF7A6B : 0xFFFFFFFF, OUTLINE);

        // Medallion in the middle showing the main goal.
        woodCircle(canvas, cx, cy, cr);
        paint.setShader(null);
        paint.setColor(0xFFFFE9B8);
        canvas.drawCircle(cx, cy, cr - dp(6.5f), paint);
        paint.setColor(0xFFFFD28A);
        canvas.drawCircle(cx, cy + dp(2), cr - dp(11), paint);
        if (!goals.isEmpty()) {
            drawGoalIcon(canvas, goals.get(0).toy, cx, cy, dp(44));
        }

        // Progress toward clearing every goal.
        int total = jellyCells(level) + level.toyTotal();
        int left = board.jellyLeft() + board.toysLeft();
        float progress = total <= 0 ? 0f : 1f - left / (float) total;
        progress = Math.max(0f, Math.min(1f, progress));
        shownProgress += (progress - shownProgress) * 0.14f;
        if (Math.abs(progress - shownProgress) < 0.002f) {
            shownProgress = progress;
        }

        float barY = y0 + panelH + dp(20);
        float barL = pad + dp(44);
        float barR = w - pad - dp(14);
        float barH = dp(13);
        centeredText(canvas, "Lv " + (levelIndex + 1), pad + dp(18), barY, dp(12), 0xFFFFFFFF, OUTLINE);
        paint.setShader(null);
        paint.setColor(0x44000000);
        rect.set(barL, barY - barH / 2f + dp(2), barR, barY + barH / 2f + dp(2));
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
        paint.setColor(WOOD_DARK);
        rect.set(barL, barY - barH / 2f, barR, barY + barH / 2f);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
        paint.setColor(0xFF5A3418);
        rect.set(barL + dp(2), barY - barH / 2f + dp(2), barR - dp(2), barY + barH / 2f - dp(2));
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
        float fillW = (barR - barL - dp(4)) * shownProgress;
        if (fillW > dp(4)) {
            paint.setColor(0xFFFFA81F);
            rect.set(barL + dp(2), barY - barH / 2f + dp(2), barL + dp(2) + fillW, barY + barH / 2f - dp(2));
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint);
            paint.setColor(0x66FFFFFF);
            rect.set(barL + dp(4), barY - barH / 2f + dp(3), barL + dp(2) + fillW - dp(2), barY - dp(0.5f));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }
        float[] marks = {1f / 3f, 2f / 3f, 1f};
        for (float m : marks) {
            drawStar(canvas, barL + (barR - barL) * m, barY, dp(10), shownProgress >= m - 0.004f);
        }
    }

    private void drawButtons(Canvas canvas, int w, int h) {
        float r = dp(25);
        float labelCy = h - bottomInset - dp(18);
        float cy = labelCy - dp(9) - r - dp(6);
        float[] xs = {w * 0.22f, w * 0.5f, w * 0.78f};
        String[] labels = {"Map", "Hint", "Restart"};
        RectF[] hits = {mapButton, hintButton, restartButton};
        for (int i = 0; i < 3; i++) {
            float x = xs[i];
            hits[i].set(x - r - dp(14), cy - r - dp(6), x + r + dp(14), labelCy + dp(12));
            woodCircle(canvas, x, cy, r);
            drawButtonIcon(canvas, i, x, cy);
            centeredText(canvas, labels[i], x, labelCy, dp(11.5f), 0xFFFFFFFF, OUTLINE);
        }
    }

    private void drawButtonIcon(Canvas canvas, int which, float cx, float cy) {
        paint.setShader(null);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        if (which == 0) {
            strokePaint.setColor(CREAM);
            strokePaint.setStrokeWidth(dp(3));
            canvas.drawLine(cx - dp(7), cy + dp(11), cx - dp(7), cy - dp(11), strokePaint);
            iconPath.reset();
            iconPath.moveTo(cx - dp(6), cy - dp(11));
            iconPath.lineTo(cx + dp(10), cy - dp(5));
            iconPath.lineTo(cx - dp(6), cy + dp(1));
            iconPath.close();
            paint.setColor(0xFFFF6B5E);
            canvas.drawPath(iconPath, paint);
        } else if (which == 1) {
            paint.setColor(0xFFFFE066);
            canvas.drawCircle(cx, cy - dp(3), dp(8.5f), paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx - dp(3), cy - dp(6), dp(2.2f), paint);
            paint.setColor(CREAM);
            rect.set(cx - dp(4.5f), cy + dp(5), cx + dp(4.5f), cy + dp(10.5f));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        } else {
            float rr = dp(9);
            strokePaint.setColor(CREAM);
            strokePaint.setStrokeWidth(dp(3.2f));
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            canvas.drawArc(rect, -50f, 270f, false, strokePaint);
            double a = Math.toRadians(220.0);
            float px = cx + (float) Math.cos(a) * rr;
            float py = cy + (float) Math.sin(a) * rr;
            float tx = (float) -Math.sin(a);
            float ty = (float) Math.cos(a);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);
            iconPath.reset();
            iconPath.moveTo(px + tx * dp(6.5f), py + ty * dp(6.5f));
            iconPath.lineTo(px + nx * dp(5.5f), py + ny * dp(5.5f));
            iconPath.lineTo(px - nx * dp(5.5f), py - ny * dp(5.5f));
            iconPath.close();
            paint.setColor(CREAM);
            canvas.drawPath(iconPath, paint);
        }
        strokePaint.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawEffects(Canvas canvas) {
        for (Particle p : particles) {
            paint.setShader(null);
            paint.setColor(p.color);
            paint.setAlpha((int) (255 * Math.max(0f, p.life / p.max)));
            canvas.drawCircle(p.x, p.y, p.size, paint);
        }
        paint.setAlpha(255);

        for (FloatText t : texts) {
            int alpha = (int) (255 * Math.max(0f, t.life / t.max));
            outlinedText(canvas, t.text, t.x, t.y, dp(15), t.color, OUTLINE, Paint.Align.CENTER, alpha);
        }

        if (toast != null && toastTime > 0f) {
            int alpha = (int) (255 * Math.min(1f, Math.max(0f, toastTime)));
            outlinedText(canvas, toast, getWidth() / 2f, getHeight() - bottomInset - dp(96), dp(13),
                    0xFFFFFFFF, OUTLINE, Paint.Align.CENTER, alpha);
        }
    }

    private void drawOverlay(Canvas canvas, int w, int h) {
        boolean won = phase == Phase.WON;
        boolean last = levelIndex >= Level.lastIndex();

        paint.setShader(null);
        paint.setColor(0xAA0B2A24);
        canvas.drawRect(0f, 0f, w, h, paint);

        float cardW = Math.min(w - dp(44), dp(320));
        float cardH = dp(318);
        float left = (w - cardW) / 2f;
        float top = (h - cardH) / 2f;
        cardFrame(canvas, left, top, left + cardW, top + cardH);

        String title;
        if (!won) {
            title = "Out of moves";
        } else if (last) {
            title = "All levels cleared!";
        } else {
            title = "Level complete!";
        }
        ribbon(canvas, w / 2f, top, cardW * 0.82f, dp(50), title, dp(19));

        int earned = won ? starsEarned() : 0;
        for (int s = 0; s < 3; s++) {
            float sx = w / 2f + (s - 1) * dp(60);
            float sy = top + dp(84) - (s == 1 ? dp(10) : 0f);
            paint.setColor(0xFF2E9E8F);
            canvas.drawCircle(sx, sy, dp(25), paint);
            paint.setColor(0xFFBFEDE5);
            canvas.drawCircle(sx, sy, dp(22.5f), paint);
            drawStar(canvas, sx, sy, dp(17), s < earned);
        }

        float panelTop = top + dp(128);
        float panelBottom = top + cardH - dp(88);
        rect.set(left + dp(18), panelTop, left + cardW - dp(18), panelBottom);
        paint.setColor(WOOD_MID);
        canvas.drawRoundRect(rect, dp(16), dp(16), paint);
        paint.setColor(CREAM);
        rect.set(left + dp(22), panelTop + dp(4), left + cardW - dp(22), panelBottom - dp(4));
        canvas.drawRoundRect(rect, dp(13), dp(13), paint);

        String status;
        if (won) {
            status = "Score " + board.score;
        } else if (board.jellyLeft() > 0 && board.toysLeft() > 0) {
            status = "Jelly " + board.jellyLeft() + " \u00B7 Toys " + board.toysLeft();
        } else if (board.toysLeft() > 0) {
            status = "Toys left: " + board.toysLeft();
        } else {
            status = "Jelly left: " + board.jellyLeft();
        }
        String line2;
        if (!won) {
            line2 = "Tap to try this board again";
        } else if (last) {
            line2 = "Tap to see your map";
        } else {
            line2 = "Next stop: " + Level.LEVELS[levelIndex + 1].name;
        }
        float mid = (panelTop + panelBottom) / 2f;
        plainText(canvas, status, w / 2f, mid - dp(10), dp(20), OUTLINE, Paint.Align.CENTER);
        plainText(canvas, line2, w / 2f, mid + dp(16), dp(13), 0xFF8A5A30, Paint.Align.CENTER);

        float pulse = 0.5f + 0.5f * (float) Math.sin(phaseTime * 3.4f);
        float grow = dp(3) * pulse;
        rect.set(w / 2f - dp(84) - grow, top + cardH - dp(74) - grow * 0.4f,
                w / 2f + dp(84) + grow, top + cardH - dp(26) + grow * 0.4f);
        bigButton(canvas, rect, won ? "Continue" : "Try again", ORANGE, ORANGE_DARK, dp(18));
    }

    // ------------------------------------------------------------------ map

    private void drawMap(Canvas canvas, int w, int h) {
        layoutMap(w, h);
        float viewH = Math.max(dp(120), mapViewBottom - mapViewTop);
        if (mapFocusPending) {
            mapScrollTarget = nodeY[currentLevel()] - viewH * 0.70f;
            clampMapScroll();
            mapScroll = mapScrollTarget;
            mapFocusPending = false;
        }
        if (mapTile == null || mapTileW != w) {
            mapTile = buildMapTile(w, (int) dp(520));
            mapTileW = w;
            mapTileH = mapTile.getHeight();
        }

        mapScroll += (mapScrollTarget - mapScroll) * 0.25f;
        clampMapScroll();

        canvas.save();
        canvas.clipRect(0f, mapViewTop, w, mapViewBottom);
        canvas.translate(0f, mapViewTop - mapScroll);
        drawMapBackdrop(canvas, w);
        int tiles = (int) Math.ceil(mapWorldH / (float) mapTileH) + 1;
        for (int i = 0; i < tiles; i++) {
            float ty = i * mapTileH;
            if (ty > mapScroll + viewH) {
                break;
            }
            if (ty + mapTileH < mapScroll) {
                continue;
            }
            if (i % 2 == 0) {
                canvas.drawBitmap(mapTile, 0f, ty, null);
            } else {
                // Mirrored copies keep the repeated scenery from looking stamped.
                canvas.save();
                canvas.translate(0f, ty + mapTileH);
                canvas.scale(1f, -1f);
                canvas.drawBitmap(mapTile, 0f, 0f, null);
                canvas.restore();
            }
        }
        drawMapPond(canvas, w);
        drawMapTrail(canvas, w);

        for (int i = 0; i < Level.LEVELS.length; i++) {
            float y = nodeY[i];
            if (y < mapScroll - dp(60) || y > mapScroll + viewH + dp(60)) {
                continue;
            }
            drawMapNode(canvas, i);
        }
        drawMapToken(canvas);
        canvas.restore();

        drawMapHud(canvas, w);
        drawMapFooter(canvas, w, h);
        if (pendingLevel >= 0) {
            drawGoalsDialog(canvas, w, h);
        }
    }

    private void drawMapBackdrop(Canvas canvas, int w) {
        if (mapGradient == null || mapGradientH != mapWorldH) {
            mapGradient = new LinearGradient(0f, 0f, 0f, mapWorldH,
                    0xFF8DDB5A, 0xFF55B53A, Shader.TileMode.CLAMP);
            mapGradientH = mapWorldH;
        }
        paint.setShader(mapGradient);
        canvas.drawRect(0f, 0f, w, mapWorldH, paint);
        paint.setShader(null);
    }

    private void drawMapPond(Canvas canvas, int w) {
        float pondTop = mapWorldH - dp(86);
        Path pond = new Path();
        pond.moveTo(0f, pondTop + dp(10));
        pond.cubicTo(w * 0.25f, pondTop - dp(18), w * 0.6f, pondTop + dp(22), w, pondTop - dp(4));
        pond.lineTo(w, mapWorldH);
        pond.lineTo(0f, mapWorldH);
        pond.close();
        paint.setShader(null);
        paint.setColor(0xFFF1DDA2);
        canvas.save();
        canvas.translate(0f, -dp(8));
        canvas.drawPath(pond, paint);
        canvas.restore();
        paint.setColor(0xFF47B2EA);
        canvas.drawPath(pond, paint);
        paint.setColor(0x44FFFFFF);
        for (int i = 0; i < 5; i++) {
            float ox = w * (0.12f + 0.19f * i);
            float oy = pondTop + dp(22) + (i % 2) * dp(14);
            canvas.drawOval(ox - dp(16), oy - dp(3), ox + dp(16), oy + dp(3), paint);
        }
    }

    private void drawMapTrail(Canvas canvas, int w) {
        int n = Level.LEVELS.length;
        Path trail = new Path();
        trail.moveTo(nodeX[0], nodeY[0] + dp(76));
        trail.lineTo(nodeX[0], nodeY[0]);
        for (int i = 1; i < n - 1; i++) {
            trail.quadTo(nodeX[i], nodeY[i], (nodeX[i] + nodeX[i + 1]) / 2f, (nodeY[i] + nodeY[i + 1]) / 2f);
        }
        trail.lineTo(nodeX[n - 1], nodeY[n - 1]);

        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(dp(34));
        strokePaint.setColor(0xFFB98A4E);
        canvas.drawPath(trail, strokePaint);
        strokePaint.setStrokeWidth(dp(28));
        strokePaint.setColor(0xFFF2DCA6);
        canvas.drawPath(trail, strokePaint);
        strokePaint.setStrokeWidth(dp(14));
        strokePaint.setColor(0x33FFFFFF);
        canvas.drawPath(trail, strokePaint);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawMapHud(Canvas canvas, int w) {
        float pad = dp(10);
        float y = topInset + dp(10);
        float height = dp(32);
        int earned = 0;
        for (int stars : bestStars) {
            earned += stars;
        }

        paint.setShader(null);
        paint.setColor(0xCC4A2C12);
        rect.set(pad + dp(14), y, pad + dp(104), y + height);
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint);
        strokePaint.setColor(0x66FFE0A8);
        strokePaint.setStrokeWidth(Math.max(1f, dp(1.4f)));
        canvas.drawRoundRect(rect, height / 2f, height / 2f, strokePaint);
        centeredText(canvas, earned + "/" + (Level.LEVELS.length * 3), rect.centerX() + dp(10), rect.centerY(),
                dp(14), 0xFFFFFFFF, OUTLINE);
        drawStar(canvas, pad + dp(18), y + height / 2f, dp(15), true);

        float titleW = dp(124);
        rect.set(w - pad - titleW, y, w - pad, y + height);
        woodPanel(canvas, rect, height / 2f);
        centeredText(canvas, "Jelly Match", rect.centerX(), rect.centerY(), dp(14), 0xFFFFFFFF, OUTLINE);
    }

    /** Pre-level card: stars earned, the level's goals and a play button. */
    private void drawGoalsDialog(Canvas canvas, int w, int h) {
        Level target = Level.LEVELS[pendingLevel];

        paint.setShader(null);
        paint.setColor(0xAA0B2A24);
        canvas.drawRect(0f, 0f, w, h, paint);

        float cardW = Math.min(w - dp(40), dp(320));
        float cardH = dp(354);
        float left = (w - cardW) / 2f;
        float top = (h - cardH) / 2f;
        cardFrame(canvas, left, top, left + cardW, top + cardH);
        ribbon(canvas, w / 2f, top, cardW * 0.58f, dp(48), "Level " + (pendingLevel + 1), dp(20));

        float ccx = left + cardW - dp(24);
        float ccy = top + dp(26);
        dialogCloseButton.set(ccx - dp(22), ccy - dp(22), ccx + dp(22), ccy + dp(22));
        paint.setColor(0xFFC0392B);
        canvas.drawCircle(ccx, ccy, dp(13), paint);
        paint.setColor(0xFFFF6B5E);
        canvas.drawCircle(ccx, ccy - dp(1), dp(11.5f), paint);
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeWidth(Math.max(2f, dp(2.4f)));
        canvas.drawLine(ccx - dp(4.5f), ccy - dp(4.5f), ccx + dp(4.5f), ccy + dp(4.5f), strokePaint);
        canvas.drawLine(ccx + dp(4.5f), ccy - dp(4.5f), ccx - dp(4.5f), ccy + dp(4.5f), strokePaint);

        for (int s = 0; s < 3; s++) {
            float sx = w / 2f + (s - 1) * dp(60);
            float sy = top + dp(80) - (s == 1 ? dp(10) : 0f);
            paint.setColor(0xFF2E9E8F);
            canvas.drawCircle(sx, sy, dp(25), paint);
            paint.setColor(0xFFBFEDE5);
            canvas.drawCircle(sx, sy, dp(22.5f), paint);
            drawStar(canvas, sx, sy, dp(17), s < bestStars[pendingLevel]);
        }

        centeredText(canvas, target.name + " \u00B7 " + target.moves + " moves", w / 2f, top + dp(122),
                dp(13), 0xFFFFFFFF, TEAL_DARK);

        float panelTop = top + dp(142);
        float panelBottom = top + cardH - dp(92);
        rect.set(left + dp(16), panelTop, left + cardW - dp(16), panelBottom);
        paint.setColor(WOOD_MID);
        canvas.drawRoundRect(rect, dp(18), dp(18), paint);
        paint.setColor(0xFFFFE2C2);
        rect.set(left + dp(20), panelTop + dp(4), left + cardW - dp(20), panelBottom - dp(4));
        canvas.drawRoundRect(rect, dp(15), dp(15), paint);

        float tagW = dp(104);
        rect.set(w / 2f - tagW / 2f, panelTop - dp(13), w / 2f + tagW / 2f, panelTop + dp(13));
        woodPanel(canvas, rect, dp(12));
        centeredText(canvas, "Goals", w / 2f, rect.centerY(), dp(14), CREAM, OUTLINE);

        String hint = target.hasJelly && target.hasToys() ? "Clear the jelly and collect the toys"
                : target.hasToys() ? "Bring the toys to the bottom" : "Clear every jelly cell";
        plainText(canvas, hint, w / 2f, panelTop + dp(30), dp(12), 0xFF8A5A30, Paint.Align.CENTER);

        List<String> labels = new ArrayList<>();
        List<Toy> icons = new ArrayList<>();
        int jelly = jellyCells(target);
        if (jelly > 0) {
            labels.add("Jelly cells  \u00D7" + jelly);
            icons.add(null);
        }
        for (Toy toy : Toy.values()) {
            int count = target.toyCounts[toy.index];
            if (count > 0) {
                labels.add(toy.label + "  \u00D7" + count);
                icons.add(toy);
            }
        }
        float rowH = Math.min(dp(30), (panelBottom - panelTop - dp(54)) / Math.max(1, labels.size()));
        float rowY = panelTop + dp(56) + rowH / 2f;
        float rowLeft = left + dp(48);
        for (int i = 0; i < labels.size(); i++) {
            drawGoalIcon(canvas, icons.get(i), rowLeft + dp(14), rowY, Math.min(dp(26), rowH - dp(2)));
            plainText(canvas, labels.get(i), rowLeft + dp(40), rowY, dp(14), OUTLINE, Paint.Align.LEFT);
            rowY += rowH;
        }

        dialogPlayButton.set(left + dp(38), top + cardH - dp(76), left + cardW - dp(38), top + cardH - dp(24));
        bigButton(canvas, dialogPlayButton, "Play", ORANGE, ORANGE_DARK, dp(22));
    }

    /** Places the level stops on a long serpentine trail, bottom to top. */
    private void layoutMap(int w, int h) {
        int n = Level.LEVELS.length;
        float mid = w / 2f;
        float amp = Math.min(w * 0.26f, w / 2f - dp(56));
        float spacing = dp(94);
        mapWorldH = dp(190) + spacing * (n - 1) + dp(150);
        for (int i = 0; i < n; i++) {
            nodeX[i] = mid + amp * (float) Math.sin(i * 0.95f - 0.4f);
            nodeY[i] = mapWorldH - dp(200) - i * spacing;
        }
        mapViewTop = topInset + dp(52);
        mapViewBottom = h - bottomInset - dp(122);
    }

    /** Keeps the scrolling view inside the trail, centring it when it fits. */
    private void clampMapScroll() {
        float viewH = Math.max(dp(120), mapViewBottom - mapViewTop);
        float max = mapWorldH - viewH;
        if (max <= 0f) {
            mapScrollTarget = max * 0.5f;
            mapScroll = mapScrollTarget;
            return;
        }
        mapScrollTarget = Math.max(0f, Math.min(max, mapScrollTarget));
        mapScroll = Math.max(0f, Math.min(max, mapScroll));
    }

    private void drawMapNode(Canvas canvas, int index) {
        float x = nodeX[index];
        float y = nodeY[index];
        float r = dp(22);
        boolean done = index < unlockedLevels;
        boolean current = index == currentLevel();
        boolean locked = !done && !current;

        paint.setShader(null);
        if (current) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(mapTime * 3f);
            paint.setColor(Color.argb((int) (60 + 50 * pulse), 0xFF, 0xC8, 0x2E));
            canvas.drawCircle(x, y, r * (1.5f + 0.08f * pulse), paint);
        }

        paint.setColor(0x44000000);
        canvas.drawCircle(x, y + dp(3.5f), r, paint);
        paint.setColor(locked ? 0xFF6E767F : 0xFF626B75);
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(locked ? 0xFF9BA3AC : 0xFFBBC3CB);
        canvas.drawCircle(x, y, r - dp(2.6f), paint);
        paint.setColor(locked ? 0xFF8A929B : 0xFFA6AFB8);
        canvas.drawCircle(x, y + dp(1.5f), r - dp(6.5f), paint);
        paint.setColor(0x66FFFFFF);
        box.set(x - r * 0.7f, y - r * 0.8f, x + r * 0.1f, y - r * 0.2f);
        canvas.drawOval(box, paint);

        if (current) {
            strokePaint.setColor(0xFFFFC82E);
            strokePaint.setStrokeWidth(Math.max(2f, dp(3f)));
            canvas.drawCircle(x, y, r + dp(1.5f), strokePaint);
        }

        centeredText(canvas, Integer.toString(index + 1), x, y + dp(1), dp(17),
                locked ? 0xFFE3E8EC : 0xFFFFFFFF, locked ? 0xFF4A525A : 0xFF3F464E);

        if (done && bestStars[index] > 0) {
            float starY = y + r + dp(10);
            for (int s = 0; s < 3; s++) {
                drawStar(canvas, x + (s - 1) * dp(14), starY - (s == 1 ? 0f : dp(2)), dp(6.2f), s < bestStars[index]);
            }
        }
    }

    /** One of your fruit sprites bobbing over the stop the player is on. */
    private void drawMapToken(Canvas canvas) {
        int index = currentLevel();
        float x = nodeX[index];
        float y = nodeY[index];
        float bob = (float) Math.sin(mapTime * 2.6f) * dp(3f);
        TileType[] types = TileType.values();
        TileType type = types[index % types.length];
        canvas.save();
        canvas.translate(x, y - dp(40) + bob);
        SpriteArt.drawFruit(canvas, type, dp(44), 255);
        canvas.restore();
    }

    private void drawMapFooter(Canvas canvas, int w, int h) {
        float pad = dp(26);
        int index = currentLevel();
        Level next = Level.LEVELS[index];
        float height = dp(54);
        float y = h - bottomInset - dp(20) - height;
        playButton.set(pad, y, w - pad, y + height);

        centeredText(canvas, next.name + " \u00B7 " + next.moves + " moves", w / 2f, y - dp(18), dp(13),
                0xFFFFFFFF, OUTLINE);
        if (toast != null && toastTime > 0f) {
            int alpha = (int) (255 * Math.min(1f, Math.max(0f, toastTime)));
            outlinedText(canvas, toast, w / 2f, y - dp(40), dp(13), 0xFFFFE066, OUTLINE,
                    Paint.Align.CENTER, alpha);
        }

        boolean allDone = unlockedLevels >= Level.LEVELS.length;
        bigButton(canvas, playButton, "Play level " + (index + 1),
                allDone ? 0xFF4BC96B : ORANGE, allDone ? 0xFF1E7F3A : ORANGE_DARK, dp(21));
    }

    // ------------------------------------------------------------------ helpers

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double easeOut(double t) {
        double inv = 1.0 - t;
        return 1.0 - inv * inv;
    }

    private static final class PendingSpawn {
        final int row;
        final int col;
        final TileType type;
        final Tile.Special special;

        PendingSpawn(int row, int col, TileType type, Tile.Special special) {
            this.row = row;
            this.col = col;
            this.type = type;
            this.special = special;
        }
    }

    private static final class FloatText {
        final float x;
        float y;
        final String text;
        final int color;
        float life;
        final float max;

        FloatText(float x, float y, String text, int color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
            this.max = 0.9f;
            this.life = max;
        }
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        int color;
        float life;
        float max;
    }
}

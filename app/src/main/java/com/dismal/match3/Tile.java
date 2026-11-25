package com.dismal.match3;

/** One piece on the board. Model position is (row, col); the view animates animRow/animCol. */
public final class Tile {

    public enum Special { NONE, ROW, COL, BOMB, RAINBOW }

    public TileType type;
    public Special special = Special.NONE;

    /** Non-null for a collectible toy: it falls with gravity but never matches. */
    public Toy toy;

    public int row;
    public int col;

    /** Visual position in grid units, used for falling and swapping. */
    public double animRow;
    public double animCol;
    public double vy;

    /** True while the piece is popping before it is removed. */
    public boolean clearing;
    public double pop;

    public Tile(TileType type, int row, int col) {
        this.type = type;
        this.row = row;
        this.col = col;
        this.animRow = row;
        this.animCol = col;
    }

    /** Toys and colour bombs never take part in a colour match. */
    public boolean isMatchable() {
        return toy == null && special != Special.RAINBOW;
    }

    public int index(int cols) {
        return row * cols + col;
    }
}

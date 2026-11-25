package com.dismal.match3;

/** The collectible toys that drop from the top of the board. */
public enum Toy {

    YOYO("Yoyo", 0xFFEF5A4C, 0xFF8C221C, 0),
    BUBBLES("Bubbles", 0xFF4FB6E4, 0xFF17587A, 1),
    SPRING("Spring toy", 0xFFC3CCDD, 0xFF5A6478, 2),
    BALL("Bouncy ball", 0xFFE8342C, 0xFF7A0A10, 3);

    public final String label;
    public final int base;
    public final int dark;
    public final int index;

    Toy(String label, int base, int dark, int index) {
        this.label = label;
        this.base = base;
        this.dark = dark;
        this.index = index;
    }
}

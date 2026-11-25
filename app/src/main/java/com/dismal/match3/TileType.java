package com.dismal.match3;

/**
 * The fruit pieces. Every fruit has a unique silhouette as well as a unique
 * colour, so the board stays readable without relying on hue alone.
 */
public enum TileType {

    APPLE("Apple", 0xFFDD3B47, 0xFFFF8A84, 0xFF8C1620, 0),
    LEMON("Lemon", 0xFFF3C22E, 0xFFFFEDA0, 0xFFBE860C, 1),
    PEAR("Pear", 0xFF8CC63F, 0xFFD6F39A, 0xFF4E7D18, 2),
    ORANGE("Orange", 0xFFF08A28, 0xFFFFC778, 0xFFB4510A, 3),
    GRAPE("Grape", 0xFF8C5AD6, 0xFFD0B4FF, 0xFF54258C, 4),
    BLUEBERRY("Blueberry", 0xFF3A86D8, 0xFF9BD6FF, 0xFF1A4C8A, 5);

    public final String label;
    public final int base;
    public final int light;
    public final int dark;
    public final int index;

    TileType(String label, int base, int light, int dark, int index) {
        this.label = label;
        this.base = base;
        this.light = light;
        this.dark = dark;
        this.index = index;
    }
}

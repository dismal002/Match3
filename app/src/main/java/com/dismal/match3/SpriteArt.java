package com.dismal.match3;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;

/**
 * Draws the hand painted sprites shipped in assets/sprites. Every bitmap is
 * decoded once, pre-scaled to a small working size and then filtered down to the
 * tile size, so the art stays crisp on every screen density.
 */
final class SpriteArt {

    /** Longest edge kept in memory per sprite, in pixels. */
    private static final int TARGET_PX = 320;

    private static final Bitmap[] FRUITS = new Bitmap[TileType.values().length];
    private static final Bitmap[] TOYS = new Bitmap[Toy.values().length];

    private static final Paint BITMAP_PAINT =
            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Rect SRC = new Rect();
    private static final RectF DST = new RectF();

    private SpriteArt() {
    }

    static void load(AssetManager assets) {
        if (FRUITS[0] != null) {
            return;
        }
        for (TileType type : TileType.values()) {
            FRUITS[type.index] = load(assets, fruitFile(type));
        }
        for (Toy toy : Toy.values()) {
            TOYS[toy.index] = load(assets, toyFile(toy));
        }
    }

    private static String fruitFile(TileType type) {
        switch (type) {
            case APPLE:
                return "apple.png";
            case LEMON:
                return "lemon.png";
            case PEAR:
                return "pear.png";
            case ORANGE:
                return "orange.png";
            case GRAPE:
                return "grapes.png";
            case BLUEBERRY:
            default:
                return "blueberry.png";
        }
    }

    private static String toyFile(Toy toy) {
        switch (toy) {
            case YOYO:
                return "yoyo.png";
            case BUBBLES:
                return "bubbles_styled.png";
            case SPRING:
                return "slinky.png";
            case BALL:
            default:
                return "red_ball.png";
        }
    }

    private static Bitmap load(AssetManager assets, String name) {
        Bitmap raw;
        try (InputStream in = assets.open("sprites/" + name)) {
            raw = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        int w = raw.getWidth();
        int h = raw.getHeight();
        float scale = (float) TARGET_PX / Math.max(w, h);
        if (scale >= 1f) {
            return raw;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(raw,
                Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
        if (scaled != raw) {
            raw.recycle();
        }
        return scaled;
    }

    static void drawFruit(Canvas canvas, TileType type, float size, int alpha) {
        draw(canvas, FRUITS[type.index], size, alpha);
    }

    static void drawToy(Canvas canvas, Toy toy, float size, int alpha) {
        draw(canvas, TOYS[toy.index], size, alpha);
    }

    /** Draws one sprite centred on the canvas origin, fitted into a square of {@code size}. */
    private static void draw(Canvas canvas, Bitmap bitmap, float size, int alpha) {
        if (bitmap == null || alpha <= 0) {
            return;
        }
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        float scale = size / Math.max(bw, bh);
        float halfW = bw * scale * 0.5f;
        float halfH = bh * scale * 0.5f;
        SRC.set(0, 0, bw, bh);
        DST.set(-halfW, -halfH, halfW, halfH);
        BITMAP_PAINT.setAlpha(alpha);
        canvas.drawBitmap(bitmap, SRC, DST, BITMAP_PAINT);
        BITMAP_PAINT.setAlpha(255);
    }
}

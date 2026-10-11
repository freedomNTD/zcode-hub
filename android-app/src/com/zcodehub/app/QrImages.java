package com.zcodehub.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 从相册图片里解二维码：缩样到合理尺寸后，4 个旋转方向 × 双二值化依次尝试。
 * 手机截图/照片里的二维码方向不定，所以旋转全试。
 */
public class QrImages {

    private static final int MAX_SIZE = 1400;

    public static String decode(Context context, Uri uri) {
        Bitmap bitmap = loadSampled(context, uri);
        if (bitmap == null) return null;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return decodeRgb(pixels, w, h);
        } finally {
            bitmap.recycle();
        }
    }

    /** 供 SelfTest 复用：直接吃 RGB 像素 */
    public static String decodeRgb(int[] pixels, int width, int height) {
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);

        LuminanceSource cur = new RGBLuminanceSource(width, height, pixels);
        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean global : new boolean[]{false, true}) {
                try {
                    BinaryBitmap bitmap = new BinaryBitmap(
                            global ? new GlobalHistogramBinarizer(cur) : new HybridBinarizer(cur));
                    Result result = reader.decodeWithState(bitmap);
                    if (result != null && result.getText() != null && result.getText().length() > 0) {
                        return result.getText();
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        reader.reset();
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                cur = cur.rotateCounterClockwise();
            } catch (Throwable t) {
                break;
            }
        }
        return null;
    }

    private static Bitmap loadSampled(Context context, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIZE || bounds.outHeight / sample > MAX_SIZE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        } catch (Exception e) {
            return null;
        }
    }
}

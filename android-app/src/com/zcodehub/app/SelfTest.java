package com.zcodehub.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 自检：现场生成二维码，分别走两条解码链路，验证扫码相关代码本身是通的。
 *
 *   NV21 路径 —— 相机实时取景用的（ScanActivity.onPreviewFrame）
 *   RGB  路径 —— 相册选图用的（QrImages.decode，Bitmap.getPixels）
 *
 * 触发方式（不需要给用户看）：
 *   adb shell "am start -n com.zcodehub.app/.MainActivity --ez selftest true"
 */
public class SelfTest {

    public static String run() {
        final String expect = "https://zcode.z.ai/remote/v4?sid=d_selftest123&hash=AbCdEf%2B1%2F2%3D&mid=m&name=selftest";
        StringBuilder out = new StringBuilder();

        out.append("NV21 ").append(nv21Path(expect));
        out.append(" / RGB ").append(rgbPath(expect));

        if (!out.toString().contains("FAIL") && !out.toString().contains("MISMATCH")) {
            try {
                DeviceStore.Device device = DeviceStore.parseLink(expect);
                out.append(" / 解析 sid=").append(device.sid).append(" relay=").append(device.relayWsUrl());
            } catch (Exception e) {
                out.append(" / 解析 FAIL: ").append(e.getMessage());
            }
        }
        return out.toString();
    }

    /** 相机同款：矩阵 → NV21（Y 黑白 + UV 灰）→ PlanarYUVLuminanceSource */
    private static String nv21Path(String expect) {
        try {
            BitMatrix matrix = encode(expect);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            byte[] nv21 = new byte[width * height * 3 / 2];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    nv21[y * width + x] = (byte) (matrix.get(x, y) ? 0 : 255);
                }
            }
            for (int i = width * height; i < nv21.length; i++) nv21[i] = (byte) 128;

            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false);
            String text = decode(source);
            if (text == null) return "FAIL: 没解出内容";
            return expect.equals(text) ? "PASS" : "MISMATCH";
        } catch (Exception e) {
            return "FAIL: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    /** 相册同款：矩阵 → RGB 像素数组 → RGBLuminanceSource（QrImages.decodeRgb） */
    private static String rgbPath(String expect) {
        try {
            BitMatrix matrix = encode(expect);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            String text = QrImages.decodeRgb(pixels, width, height);
            if (text == null) return "FAIL: 没解出内容";
            return expect.equals(text) ? "PASS" : "MISMATCH";
        } catch (Exception e) {
            return "FAIL: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static BitMatrix encode(String text) throws Exception {
        return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480);
    }

    private static String decode(PlanarYUVLuminanceSource source) {
        try {
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            MultiFormatReader reader = new MultiFormatReader();
            reader.setHints(hints);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            return result == null ? null : result.getText();
        } catch (Exception e) {
            return null;
        }
    }
}

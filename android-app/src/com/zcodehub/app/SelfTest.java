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
 * 自检：用 ZXing 现场生成一个二维码 → 转成相机输出的 NV21 格式 →
 * 走和 ScanActivity 取景框完全相同的解码路径，验证扫码链路本身是通的。
 *
 * 触发方式（不需要给用户看）：
 *   adb shell "am start -n com.zcodehub.app/.MainActivity --ez selftest true"
 */
public class SelfTest {

    public static String run() {
        final String expect = "https://zcode.z.ai/remote/v4?sid=d_selftest123&hash=AbCdEf%2B1%2F2%3D&mid=m&name=selftest";
        try {
            BitMatrix matrix = new QRCodeWriter().encode(expect, BarcodeFormat.QR_CODE, 480, 480);
            int width = matrix.getWidth();
            int height = matrix.getHeight();

            // Y 平面：黑=0 白=255；UV 平面填 128（灰度）
            byte[] nv21 = new byte[width * height * 3 / 2];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    nv21[y * width + x] = (byte) (matrix.get(x, y) ? 0 : 255);
                }
            }
            for (int i = width * height; i < nv21.length; i++) nv21[i] = (byte) 128;

            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            MultiFormatReader reader = new MultiFormatReader();
            reader.setHints(hints);

            Result result = reader.decodeWithState(bitmap);
            if (result == null || result.getText() == null) return "FAIL: 没解出内容";
            if (!expect.equals(result.getText())) return "MISMATCH: " + result.getText();

            // 顺手验证解析
            DeviceStore.Device device = DeviceStore.parseLink(result.getText());
            return "PASS sid=" + device.sid + " hash=" + device.hash + " relay=" + device.relayWsUrl();
        } catch (Exception e) {
            return "FAIL: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }
}

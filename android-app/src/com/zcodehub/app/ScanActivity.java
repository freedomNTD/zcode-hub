package com.zcodehub.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 扫码页：实时取景 + ZXing 解码（纯原生，不依赖 WebView 和任何服务器）。
 * 结果通过 EXTRA_QR 返回给 MainActivity。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_QR = "qr";
    private static final int REQ_CAMERA = 3001;

    private SurfaceView previewView;
    private SurfaceHolder surfaceHolder;
    private TextView hint;
    private Camera camera;
    private Camera.Size previewSize;
    private MultiFormatReader reader;
    private int frameCount;
    private volatile boolean handled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new SurfaceView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("把 ZCode 桌面端「手机远控」里的二维码放进取景框");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(0, 32, 0, 32);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        root.addView(hint, lp);

        setContentView(root);
        previewView.getHolder().addCallback(this);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader = new MultiFormatReader();
        reader.setHints(hints);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                hint.setText("没有摄像头权限，无法扫码。可以去系统设置里给应用授权。");
            } else if (surfaceHolder != null) {
                // surfaceCreated 早于授权，那时候开不了相机，这里补开
                openCamera(surfaceHolder);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceHolder = null;
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return; // 等权限结果
        }
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size best = null;
            for (Camera.Size size : params.getSupportedPreviewSizes()) {
                if (size.width <= 1280 && (best == null || size.width > best.width)) best = size;
            }
            if (best != null) {
                params.setPreviewSize(best.width, best.height);
                previewSize = best;
            } else {
                previewSize = params.getPreviewSize();
            }
            try {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } catch (Exception ignored) {
            }
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);

            int bufferSize = previewSize.width * previewSize.height * 3 / 2;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            hint.setText("对准二维码，识别成功会自动返回");
        } catch (Exception e) {
            hint.setText("打不开摄像头：" + e.getMessage() + "\n可以直接用「粘贴链接」添加设备。");
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        frameCount++;
        if (handled || cam == null) return;
        if (frameCount % 4 != 0) { // 降频解码，别把预览卡住
            cam.addCallbackBuffer(data);
            return;
        }
        try {
            int width = previewSize != null ? previewSize.width : 640;
            int height = previewSize != null ? previewSize.height : 480;
            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            if (result != null && result.getText() != null && result.getText().length() > 0) {
                handled = true;
                Intent out = new Intent();
                out.putExtra(EXTRA_QR, result.getText());
                setResult(RESULT_OK, out);
                finish();
                return;
            }
        } catch (Exception ignored) {
            // NotFoundException 是常态，继续下一帧
        } finally {
            try {
                reader.reset();
            } catch (Exception ignored) {
            }
            cam.addCallbackBuffer(data);
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        super.onDestroy();
    }
}

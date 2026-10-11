package com.zcodehub.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 扫码页：实时取景 + ZXing 解码（纯原生）。
 *
 * 识别策略（按顺序、每帧换花样，直到解出）：
 *   1) 中心正方形裁剪（面积约为全帧 1/4~1/8，快且抗噪）× 双二值化 × 旋转 0°/90°
 *   2) 每两轮插入一次全帧尝试（应付二维码几乎占满屏幕的近距离场景）
 * 另有取景框引导、手电筒、点按对焦，用于提高一次成功率。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_QR = "qr";
    private static final int REQ_CAMERA = 3001;
    private static final int FRAME_STRIDE = 3;      // 每 3 帧解一次
    private static final long DECODE_BUDGET_MS = 250; // 单次解码预算，超了就换下一帧

    private SurfaceView previewView;
    private FramingView framingView;
    private TextView hint;
    private Button torchButton;
    private Camera camera;
    private Camera.Size previewSize;
    private MultiFormatReader reader;
    private SurfaceHolder surfaceHolder;
    private int frameCount;
    private int passCount;
    private volatile boolean handled;
    private boolean torchOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new SurfaceView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        framingView = new FramingView(this);
        root.addView(framingView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout bottom = new FrameLayout(this);
        bottom.setBackgroundColor(0xB3000000);
        bottom.setPadding(0, 24, 0, 24 + navInset());
        hint = new TextView(this);
        hint.setText("把二维码放进方框，识别成功会自动返回");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        torchButton = new Button(this);
        torchButton.setText("💡");
        torchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        torchButton.setAllCaps(false);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | Gravity.END;
        tlp.setMargins(0, 0, 32, 64 + navInset());
        torchButton.setLayoutParams(tlp);
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });
        root.addView(torchButton, tlp);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottom, blp);

        setContentView(root);
        previewView.getHolder().addCallback(this);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader = new MultiFormatReader();
        reader.setHints(hints);

        // 点按对焦（连拍对焦在屏幕二维码上偶尔拉风箱）
        previewView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    focusAt(event.getX(), event.getY());
                }
                return false;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void log(String message) {
        Log.d("zcode-scan", message);
    }

    private int navInset() {
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            camera.cancelAutoFocus();
            Camera.Parameters p = camera.getParameters();
            if (p.getMaxNumFocusAreas() > 0) {
                int fx = (int) (x / previewView.getWidth() * 2000 - 1000);
                int fy = (int) (y / previewView.getHeight() * 2000 - 1000);
                Camera.Area area = new Camera.Area(new Rect(Math.max(-1000, fx - 100),
                        Math.max(-1000, fy - 100), Math.min(1000, fx + 100), Math.min(1000, fy + 100)), 1000);
                p.setFocusAreas(Collections.singletonList(area));
                p.setMeteringAreas(Collections.singletonList(area));
                camera.setParameters(p);
            }
            camera.autoFocus(null);
        } catch (Exception ignored) {
        }
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            java.util.List<String> modes = p.getSupportedFlashModes();
            if (modes == null || (!modes.contains(Camera.Parameters.FLASH_MODE_TORCH)
                    && !modes.contains(Camera.Parameters.FLASH_MODE_ON))) {
                hint.setText("这台设备没有闪光灯");
                return;
            }
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            torchButton.setText(torchOn ? "关灯" : "💡");
        } catch (Exception e) {
            hint.setText("闪光灯不可用：" + e.getMessage());
        }
    }

    /* ---------------- 相机 ---------------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        log("surfaceCreated");
        openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceHolder = holder;
        // 权限弹窗/窗口变化会让 surface 重建，正在跑的预览要接到新 surface 上
        if (camera != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            log("surfaceChanged: 重启预览");
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception e) {
                log("surfaceChanged 重启失败: " + e.getMessage());
            }
        } else {
            log("surfaceChanged: 重新开相机");
            openCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        log("surfaceDestroyed");
        surfaceHolder = null;
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) {
            log("openCamera: 已在运行，跳过");
            return;
        }
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            log("openCamera: surface 不可用，等下一次回调");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("openCamera: 还没有相机权限，等授权回调");
            return;
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
            log("相机已启动 " + previewSize.width + "x" + previewSize.height);
            hint.setText("对准二维码，识别成功会自动返回");
        } catch (Exception e) {
            hint.setText("打不开摄像头：" + e.getMessage() + "\n可以用「添加 → 粘贴配对链接」或「从相册选二维码」。");
        }
    }

    /* ---------------- 解码 ---------------- */

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        frameCount++;
        if (frameCount % 30 == 1) log("onPreviewFrame #" + frameCount);
        if (handled || cam == null || previewSize == null) return;
        if (frameCount % FRAME_STRIDE != 0) {
            cam.addCallbackBuffer(data);
            return;
        }
        int width = previewSize.width;
        int height = previewSize.height;
        passCount++;

        long start = System.currentTimeMillis();
        Result result = null;

        // 1) 中心正方形裁剪：小、快、抗噪
        int side = (int) (Math.min(width, height) * 0.8f);
        int left = (width - side) / 2;
        int top = (height - side) / 2;
        result = decodeWithRetries(new PlanarYUVLuminanceSource(
                data, width, height, left, top, side, side, false), 2);

        // 2) 每两轮试一次全帧（二维码几乎占满屏幕的近距离场景）
        if (result == null && System.currentTimeMillis() - start < DECODE_BUDGET_MS) {
            result = decodeWithRetries(new PlanarYUVLuminanceSource(
                    data, width, height, 0, 0, width, height, false), 1);
        }

        cam.addCallbackBuffer(data);

        if (result != null && result.getText() != null && result.getText().length() > 0) {
            handled = true;
            Intent out = new Intent();
            out.putExtra(EXTRA_QR, result.getText());
            setResult(RESULT_OK, out);
            finish();
        }
    }

    /** 双二值化 × 多旋转依次尝试；rotations 是最多尝试几个旋转方向 */
    private Result decodeWithRetries(PlanarYUVLuminanceSource source, int rotations) {
        PlanarYUVLuminanceSource cur = source;
        for (int i = 0; i < Math.max(1, rotations); i++) {
            for (boolean global : new boolean[]{false, true}) {
                try {
                    BinaryBitmap bitmap = new BinaryBitmap(
                            global ? new GlobalHistogramBinarizer(cur) : new HybridBinarizer(cur));
                    Result r = reader.decodeWithState(bitmap);
                    if (r != null && r.getText() != null) return r;
                } catch (Exception ignored) {
                } finally {
                    try {
                        reader.reset();
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                cur = (PlanarYUVLuminanceSource) cur.rotateCounterClockwise();
            } catch (Throwable t) {
                break;
            }
        }
        return null;
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

    /** 取景框：四角暗化 + 中央亮框，引导用户把码放正中间 */
    private class FramingView extends View {
        private final Paint dark = new Paint();
        private final Paint line = new Paint();
        private final Rect box = new Rect();

        FramingView(Context context) {
            super(context);
            dark.setColor(0x99000000);
            line.setColor(0xFFE8EAF0);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            int side = (int) (Math.min(w, h - navInset()) * 0.72f);
            int left = (w - side) / 2;
            int top = (h - navInset() - side) / 2;
            box.set(left, top, left + side, top + side);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), box.top, dark);
            canvas.drawRect(0, box.bottom, getWidth(), getHeight(), dark);
            canvas.drawRect(0, box.top, box.left, box.bottom, dark);
            canvas.drawRect(box.right, box.top, getWidth(), box.bottom, dark);
            canvas.drawRect(box, line);
        }
    }
}

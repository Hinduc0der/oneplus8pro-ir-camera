package com.irmono.cam;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {
    private CameraManager cm;
    private final List<String> ids = new ArrayList<>();
    private int idx = 0;
    private String camId;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private TextureView texture;
    private TextView label;
    private Button shot, next, video;
    private Handler bg;
    private HandlerThread bgThread;
    private Size jpegSize;
    private Size videoSize;
    private Surface previewSurface;
    private boolean opening = false;

    private MediaRecorder recorder;
    private boolean recording = false;
    private Surface recorderSurface;
    private Uri videoUri;
    private ParcelFileDescriptor videoPfd;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(this);
        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));

        label = new TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setBackgroundColor(0x99000000);
        label.setPadding(28, 28, 28, 28);
        label.setText("...");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(label, lp);

        shot = new Button(this);
        shot.setText("СНЯТЬ");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = 90;
        root.addView(shot, sp);

        video = new Button(this);
        video.setText("ВИДЕО");
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-2, -2);
        vp.gravity = Gravity.BOTTOM | Gravity.START;
        vp.bottomMargin = 90;
        vp.leftMargin = 28;
        root.addView(video, vp);

        next = new Button(this);
        next.setText("СЕНСОР ▸");
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2);
        np.gravity = Gravity.BOTTOM | Gravity.END;
        np.bottomMargin = 90;
        np.rightMargin = 28;
        root.addView(next, np);
        setContentView(root);

        shot.setOnClickListener(v -> { if (!recording) capture(); });
        video.setOnClickListener(v -> { if (recording) stopVideo(); else startVideo(); });
        next.setOnClickListener(v -> {
            if (recording || ids.isEmpty()) return;
            idx = (idx + 1) % ids.size();
            camId = ids.get(idx);
            reopen();
        });

        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            List<String> all = Arrays.asList(cm.getCameraIdList());
            ids.addAll(all);
            camId = chooseTarget(all);
            idx = ids.indexOf(camId);
            if (idx < 0) idx = 0;
        } catch (Exception e) {
            toast("getCameraIdList: " + e);
        }

        bgThread = new HandlerThread("cam");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCam(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });

        List<String> need = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != 0)
            need.add(android.Manifest.permission.CAMERA);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0)
            need.add(android.Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty())
            requestPermissions(need.toArray(new String[0]), 1);
    }

    private String chooseTarget(List<String> all) {
        if (all.contains("4")) return "4";
        String best = null;
        for (String id : all) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean bw = false;
                if (caps != null)
                    for (int x : caps)
                        if (x == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) bw = true;
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK && !bw) best = id;
            } catch (Exception e) { /* ignore */ }
        }
        if (best != null) return best;
        return all.isEmpty() ? "0" : all.get(all.size() - 1);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (texture.isAvailable()) openCam();
    }

    private void reopen() { closeCam(); openCam(); }

    private void openCam() {
        if (device != null || opening) return;
        if (camId == null) { toast("нет камеры"); return; }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != 0) { toast("нет разрешения CAMERA"); return; }
        opening = true;
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] js = m.getOutputSizes(ImageFormat.JPEG);
            jpegSize = js[0];
            for (Size s : js) if (s.getWidth() * s.getHeight() > jpegSize.getWidth() * jpegSize.getHeight()) jpegSize = s;
            videoSize = chooseVideoSize(m.getOutputSizes(MediaRecorder.class));
            runOnUiThread(() -> label.setText("cam " + camId + "   " + jpegSize.getWidth() + "x" + jpegSize.getHeight()));
            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::save, bg);
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { opening = false; device = d; startPreview(); }
                public void onDisconnected(CameraDevice d) { opening = false; d.close(); }
                public void onError(CameraDevice d, int e) { opening = false; d.close(); toast("openCamera error " + e); }
            }, bg);
        } catch (Exception e) {
            opening = false;
            toast("openCam: " + e);
        }
    }

    private Size chooseVideoSize(Size[] vs) {
        if (vs == null || vs.length == 0) return null;
        Size pick = null;
        for (Size s : vs) {
            if (s.getWidth() > 1920) continue;
            if (pick == null || s.getWidth() * s.getHeight() > pick.getWidth() * pick.getHeight()) pick = s;
        }
        if (pick != null) return pick;
        Size small = vs[0];
        for (Size s : vs) if (s.getWidth() * s.getHeight() < small.getWidth() * small.getHeight()) small = s;
        return small;
    }

    private void startPreview() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            StreamConfigurationMap m = cm.getCameraCharacteristics(camId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] ps = m.getOutputSizes(SurfaceTexture.class);
            Size pv = ps[0];
            for (Size s : ps)
                if (s.getWidth() <= 1920 && s.getWidth() * s.getHeight() > pv.getWidth() * pv.getHeight()) pv = s;
            st.setDefaultBufferSize(pv.getWidth(), pv.getHeight());
            previewSurface = new Surface(st);
            List<Surface> outs = Arrays.asList(previewSurface, reader.getSurface());
            device.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(previewSurface);
                        s.setRepeatingRequest(b.build(), null, bg);
                    } catch (Exception e) { toast("preview: " + e); }
                }
                public void onConfigureFailed(CameraCaptureSession s) { toast("session fail"); }
            }, bg);
        } catch (Exception e) { toast("startPreview: " + e); }
    }

    private void capture() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, 90);
            session.capture(b.build(), null, bg);
            toast("снимаю...");
        } catch (Exception e) { toast("capture: " + e); }
    }

    private void save(ImageReader ir) {
        try (Image img = ir.acquireNextImage()) {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String name = "IRMONO_" + System.currentTimeMillis() + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/IRMono");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(data); }
            runOnUiThread(() -> toast("сохранено: DCIM/IRMono/" + name));
        } catch (Exception e) {
            runOnUiThread(() -> toast("save: " + e));
        }
    }

    // ───────────────────────── video ─────────────────────────

    private void startVideo() {
        if (recording || device == null) { toast("камера не готова"); return; }
        if (videoSize == null) { toast("этот сенсор не умеет видео"); return; }
        try {
            setupRecorder();
            recorderSurface = recorder.getSurface();
            try { if (session != null) session.close(); } catch (Exception e) {}
            session = null;
            List<Surface> outs = Arrays.asList(previewSurface, recorderSurface);
            device.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.addTarget(previewSurface);
                        b.addTarget(recorderSurface);
                        s.setRepeatingRequest(b.build(), null, bg);
                        recorder.start();
                        recording = true;
                        runOnUiThread(() -> { video.setText("СТОП ■"); label.setText("cam " + camId + "   ● запись " + videoSize.getWidth() + "x" + videoSize.getHeight()); });
                    } catch (Exception e) { toast("rec start: " + e); abortRecorder(); }
                }
                public void onConfigureFailed(CameraCaptureSession s) { toast("rec session fail"); abortRecorder(); }
            }, bg);
        } catch (Exception e) {
            toast("startVideo: " + e);
            abortRecorder();
        }
    }

    private void setupRecorder() throws Exception {
        boolean audio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0;
        String name = "IRMONO_" + System.currentTimeMillis() + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/IRMono");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (videoUri == null) throw new Exception("MediaStore insert == null");
        videoPfd = getContentResolver().openFileDescriptor(videoUri, "w");

        recorder = new MediaRecorder();
        if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoPfd.getFileDescriptor());
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44100);
        }
        recorder.setOrientationHint(90);
        recorder.prepare();
    }

    private void stopVideo() {
        recording = false;
        boolean ok = true;
        try { if (session != null) session.stopRepeating(); } catch (Exception e) {}
        try { if (recorder != null) recorder.stop(); } catch (Exception e) { ok = false; }
        try { if (recorder != null) recorder.reset(); } catch (Exception e) {}
        try { if (recorder != null) recorder.release(); } catch (Exception e) {}
        recorder = null;
        try { if (videoPfd != null) videoPfd.close(); } catch (Exception e) {}
        videoPfd = null;
        if (videoUri != null) {
            if (ok) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(videoUri, cv, null, null);
                toast("видео сохранено: DCIM/IRMono");
            } else {
                getContentResolver().delete(videoUri, null, null);
                toast("видео слишком короткое — отменено");
            }
        }
        videoUri = null;
        recorderSurface = null;
        runOnUiThread(() -> video.setText("ВИДЕО"));
        // вернуться к превью + фото
        try { if (session != null) session.close(); } catch (Exception e) {}
        session = null;
        if (device != null) startPreview();
    }

    private void abortRecorder() {
        recording = false;
        try { if (recorder != null) { recorder.reset(); recorder.release(); } } catch (Exception e) {}
        recorder = null;
        try { if (videoPfd != null) videoPfd.close(); } catch (Exception e) {}
        videoPfd = null;
        if (videoUri != null) { try { getContentResolver().delete(videoUri, null, null); } catch (Exception e) {} }
        videoUri = null;
        recorderSurface = null;
        runOnUiThread(() -> video.setText("ВИДЕО"));
        if (device != null) startPreview();
    }

    private void closeCam() {
        if (recording) { try { if (recorder != null) { recorder.stop(); recorder.reset(); recorder.release(); } } catch (Exception e) {}
            recorder = null; recording = false;
            if (videoUri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                try { getContentResolver().update(videoUri, cv, null, null); } catch (Exception e) {}
                videoUri = null;
            }
            try { if (videoPfd != null) videoPfd.close(); } catch (Exception e) {}
            videoPfd = null;
        }
        try { if (session != null) session.close(); } catch (Exception e) {}
        try { if (device != null) device.close(); } catch (Exception e) {}
        try { if (reader != null) reader.close(); } catch (Exception e) {}
        session = null; device = null; reader = null;
    }

    @Override protected void onPause() { closeCam(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (texture != null && texture.isAvailable()) openCam(); }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }
}

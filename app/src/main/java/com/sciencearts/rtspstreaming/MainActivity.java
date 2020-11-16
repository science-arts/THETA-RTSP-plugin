package com.sciencearts.rtspstreaming;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;
import com.sciencearts.rtspstreaming.rtsp.RtspServer;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends PluginActivity implements SurfaceTexture.OnFrameAvailableListener {
    static final String TAG ="MainActivity";
    private static final boolean VERBOSE = true;

    private CustomApplication app;
    private Context con;

    private GLSurfaceView mGLView;
    private SurfaceViewRenderer mRenderer;
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private int mCameraPreviewWidth, mCameraPreviewHeight;
    private Spinner mSpinner;
    private List<SpinnerItem> mSpinnerItems;

    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    private RtspServer.RtspListener mRtspServerListener = new RtspServer.RtspListener() {
        @Override
        public void play() {
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.changeRecordingState(true);
                }
            });
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    changeStreamingLED();
                }
            });
        }
        @Override
        public void closed() {
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.changeRecordingState(false);
                    mRenderer.stopRecording();
                }
            });

            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // RTSP Server Thread is stop. Start new thread for next request.
                    app.getRtspServer().open();
                    changeReadyLED();

                    while (mRenderer.isRecording()) {
                        try {
                            Thread.sleep(100);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }
        @Override
        public void setResolution(final String resolutionStr) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String[] wh = resolutionStr.split("x");
                    if (wh.length == 1) {
                        Log.d(TAG, "set resolution fail.");
                        return;
                    }

                    int desireWidth = -1;
                    int desireHeight = -1;
                    try {
                        desireWidth = Integer.parseInt(wh[0]);
                        desireHeight = Integer.parseInt(wh[1]);
                    } catch (Exception e) {
                        Log.d(TAG, "set resolution fail.");
                        return;
                    }

                    int spinnerItemNum = mSpinner.getAdapter().getCount();
                    int desireItemIndex = -1;
                    for (int i = 0; i < spinnerItemNum; i++) {
                        Camera.Size cameraSize = ((SpinnerItem)mSpinner.getAdapter().getItem(i)).getSize();
                        if (cameraSize.width == desireWidth && cameraSize.height == desireHeight) {
                            desireItemIndex = i;
                            break;
                        }
                    }

                    mSpinner.setSelection(desireItemIndex, false);
                }
            });
        }

        @Override
        public void error(){
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    changeErrorLED();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        app = (CustomApplication)getApplication();

        con = getApplicationContext();

        changeStartupLED();

        notificationCameraClose();

        app.getRtspServer().setCommandListener(mRtspServerListener);
        app.getRtspServer().open();

        mCameraHandler = new CameraHandler(this);
        mGLView = findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setEGLContextClientVersion(2);
        mRenderer = new SurfaceViewRenderer(mCameraHandler, sVideoEncoder, app.getRtspServer());
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        mSpinner = findViewById(R.id.spinner);

        // set callback for pressing buttons
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {
                if (VERBOSE) Log.d(TAG, "onKeyLongPress");

                if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    changeEndLED();
                    callRecordingApp();
                    finishAndRemoveTask();
                }
            }

            @Override
            public void onKeyDown(int keyCode, KeyEvent event) {
                if (VERBOSE) Log.d(TAG, "onKeyDown");
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent event) {
                if (VERBOSE) Log.d(TAG, "onKeyUp");
            }
        });

        changeReadyLED();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (mCamera == null) {
                openCamera(null);
                mGLView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
                    }
                });
                if (mSpinner.getAdapter() == null){
                    setSpinnerItem();
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        }

        mGLView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.release();
            }
        });
        mGLView.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy start.");
        super.onDestroy();
        mCameraHandler.invalidateHandler();
        notificationCameraOpen();
        notificationSuccess();
    }

    /**
     * Call the shooting application when the distribution application ends.
     */
    @SuppressLint("WrongConstant")
    private void callRecordingApp() {
        con.sendBroadcastAsUser(new Intent("com.theta360.devicelibrary.receiver.ACTION_BOOT_BASIC"), android.os.Process.myUserHandle());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", this.getPackageName(), null));
            this.startActivity(intent);
            finish();
        } else {
            openCamera(null);
            mGLView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
                }
            });
            if (mSpinner.getAdapter() == null){
                setSpinnerItem();
            }
        }
    }

    private void openCamera(Camera.Size desireSize) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }
        if (VERBOSE) Log.d(TAG, "try open camera.");

        Camera.CameraInfo info = new Camera.CameraInfo();

        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            if (VERBOSE) Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parameters = mCamera.getParameters();
        parameters.set("RIC_PROC_STITCHING", "RicStaticStitching");
        if (desireSize == null || (desireSize.width == 1920 && desireSize.height == 960)) {
            parameters.setPreviewSize(1920, 960);
            parameters.set("RIC_SHOOTING_MODE", "RicStillPreview1920");
        } else if (desireSize.width == 640 && desireSize.height == 320) {
            parameters.setPreviewSize(640, 320);
            parameters.set("RIC_SHOOTING_MODE", "RicStillPreview640");
        } else if (desireSize.width == 1024 && desireSize.height == 512) {
            parameters.setPreviewSize(1024, 512);
            parameters.set("RIC_SHOOTING_MODE", "RicMoviePreview1024");
        } else if (desireSize.width == 3840 && desireSize.height == 1920) {
            parameters.setPreviewSize(3840, 1920);
            parameters.set("RIC_SHOOTING_MODE", "RicMoviePreview3840");
        }
        mCamera.setParameters(parameters);
        mCamera.setDisplayOrientation(180);

        Camera.Size mCameraPreviewSize = mCamera.getParameters().getPreviewSize();
        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;

        if (VERBOSE) Log.d(TAG, "open camera done.");
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            if (VERBOSE) Log.d(TAG, "release camera done.");
        }
    }

    private void handleSetSurfaceTexture(SurfaceTexture st) {
        if (VERBOSE) Log.d(TAG, "set new SurfaceTexture to camera");
        st.setOnFrameAvailableListener(this);

        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        mGLView.requestRender();
    }

    public class SpinnerItem {
        Camera.Size mSize;
        String mText;

        public SpinnerItem(Camera.Size size, String text) {
            mSize = size;
            mText = text;
        }

        public Camera.Size getSize(){
            return mSize;
        }

        @Override
        public String toString(){
            return mText;
        }
    }

    public void setSpinnerItem(){
        mSpinnerItems = new ArrayList<>();
        mSpinnerItems.add(new SpinnerItem(mCamera.new Size(640, 320), "640" + "x" + "320"));
        mSpinnerItems.add(new SpinnerItem(mCamera.new Size(1024, 512), "1024" + "x" + "512"));
        mSpinnerItems.add(new SpinnerItem(mCamera.new Size(1920, 960), "1920" + "x" + "960"));
        mSpinnerItems.add(new SpinnerItem(mCamera.new Size(3840, 1920), "3840" + "x" + "1920"));

        ArrayAdapter<SpinnerItem> adp = new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item, mSpinnerItems);
        mSpinner.setAdapter(adp);
        mSpinner.setSelection(2);

        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean initialSelected = true;
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (!initialSelected) {
                    releaseCamera();
                    openCamera(((SpinnerItem)adapterView.getItemAtPosition(i)).getSize());
                    mGLView.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "set preview size: " + mCameraPreviewWidth + "x" + mCameraPreviewHeight);
                            mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
                            mRenderer.initializeTexture();
                        }
                    });
                }
                initialSelected = false;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

    }

    public void changeStartupLED() {
        notificationLedHide(LedTarget.LED4); // Camera
        notificationLedHide(LedTarget.LED5); // Video
        notificationLedHide(LedTarget.LED6); // LIVE
        notificationLedHide(LedTarget.LED7); // Recording
        notificationLedHide(LedTarget.LED8); // Error
    }

    public void changeReadyLED() {
        notificationLedShow(LedTarget.LED5); // Video
        notificationLedHide(LedTarget.LED6); // LIVE
        notificationLedHide(LedTarget.LED8); // Error
    }

    public void changeStreamingLED() {
        notificationLedShow(LedTarget.LED5); // Video
        notificationLedShow(LedTarget.LED6); // LIVE
        notificationLedHide(LedTarget.LED8); // Error
    }

    public void changeErrorLED() {
        notificationLedShow(LedTarget.LED5); // Video
        notificationLedHide(LedTarget.LED6); // LIVE
        notificationLedBlink(LedTarget.LED8, LedColor.BLUE, 1000); // Error
    }

    private void changeEndLED() {
        notificationLedHide(LedTarget.LED5); // Video
        notificationLedHide(LedTarget.LED6); // LIVE
        notificationLedHide(LedTarget.LED8); // Error
    }

    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        private WeakReference<MainActivity> mWeakActivity;

        public CameraHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<>(activity);
        }

        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;

            MainActivity activity = mWeakActivity.get();
            if (activity == null) {
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}

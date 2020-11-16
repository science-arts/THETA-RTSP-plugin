package com.sciencearts.rtspstreaming;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.sciencearts.rtspstreaming.gles.Drawable2d;
import com.sciencearts.rtspstreaming.gles.FullFrameRect;
import com.sciencearts.rtspstreaming.gles.Texture2dProgram;
import com.sciencearts.rtspstreaming.rtsp.RtspServer;

import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class SurfaceViewRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "SurfaceViewRenderer";
    private static final boolean VERBOSE = true;

    private MainActivity.CameraHandler mCameraHandler;
    private TextureMovieEncoder mVideoEncoder;

    private FullFrameRect mFullScreen;

    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    private SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;

    private RtspServer mRtspServer;

    public SurfaceViewRenderer(MainActivity.CameraHandler cameraHandler, TextureMovieEncoder movieEncoder, RtspServer rtspServer) {
        mCameraHandler = cameraHandler;
        mVideoEncoder = movieEncoder;
        mTextureId = -1;
        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;
        mRecordingEnabled = false;
        mRecordingStatus = -1;
        mRtspServer = rtspServer;
    }

    private void initializeFrame(){
        Log.d(TAG, "initializeFrame start.");
        Texture2dProgram texture2dProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
        Drawable2d drawable2d = new Drawable2d(Drawable2d.Prefab.FULL_MIRROR_RECTANGLE);
        mFullScreen = new FullFrameRect(texture2dProgram, drawable2d);
        Log.d(TAG, "initializeFrame done.");
    }

    public void initializeTexture() {
        Log.d(TAG, "initializeTexture start.");
        mTextureId = mFullScreen.createTextureObject();
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                MainActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
        Log.d(TAG, "initializeTexture done.");
    }

    public void release() {
        Log.d(TAG, "release start.");
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);
            mFullScreen = null;
        }
        mIncomingWidth = mIncomingHeight = -1;
        Log.d(TAG, "release done.");
    }

    public void changeRecordingState(boolean isRecording) {
        mRecordingEnabled = isRecording;
    }

    public void stopRecording(){
        if (VERBOSE) Log.d(TAG, "STOP recording");
        mVideoEncoder.stopRecording();
        mRecordingStatus = RECORDING_OFF;
    }

    public boolean isRecording() {
        return (mRecordingStatus != -1) && (mRecordingStatus != RECORDING_OFF);
    }

    public void setCameraPreviewSize(int width, int height) {
        mIncomingWidth = width;
        mIncomingHeight = height;
        mIncomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }
        initializeFrame();
        initializeTexture();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        mSurfaceTexture.updateTexImage();

        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    if (VERBOSE) Log.d(TAG, "try START recording");

                    if (!mVideoEncoder.isRecording()) {
                        if (VERBOSE) Log.d(TAG, "START recording");

                        mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                                new VideoEncoderCore.VideoEncodeListener() {
                                    @Override
                                    public void getData(ByteBuffer encodedData) {
                                        mRtspServer.sendH264Nal(encodedData);
                                    }
                                },mIncomingWidth, mIncomingHeight, 2000000, EGL14.eglGetCurrentContext()));
                        mRecordingStatus = RECORDING_ON;
                    }

                    break;
                case RECORDING_RESUMED:
                    if (VERBOSE) Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:

                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    if (VERBOSE) Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }

        if (mVideoEncoder.isRecording()) {
            mVideoEncoder.setTextureId(mTextureId);
            mVideoEncoder.frameAvailable(mSurfaceTexture);
        }

        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            return;
        }

        if (mIncomingSizeUpdated) {
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(mTextureId, mSTMatrix);
    }
}

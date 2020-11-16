package com.sciencearts.rtspstreaming;

import com.sciencearts.rtspstreaming.rtsp.RtspServer;

public class CustomApplication extends android.app.Application {
    private RtspServer mRtspServer;

    @Override
    public void onCreate() {
        super.onCreate();
        mRtspServer = new RtspServer();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        mRtspServer.shutdown();
    }

    public RtspServer getRtspServer() {
        return mRtspServer;
    }
}

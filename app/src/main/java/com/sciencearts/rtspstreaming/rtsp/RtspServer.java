package com.sciencearts.rtspstreaming.rtsp;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RtspServer implements AutoCloseable {
    static final String TAG ="RtspServer";
    private static final boolean VERBOSE = true;

    private boolean mTerminate;

    private ExecutorService mRtspExecutorService;
    private String mHostAddress;
    private int mPort = 8554;
    ServerSocket mServerSocket;
    Socket mSocket;
    BufferedReader mReader;
    PrintWriter mWriter;

    private int mTimeout = 60;
    private int mCsec = 0;
    private String mSessionId = "";

    int[] mRtpServerPorts = new int[]{8001,8002};
    String mClientIpAddress;
    int[] mRtpClientPorts;
    RtpServer mRtpServer;

    Timer mTimeoutTimer;
    long mPrevRequestTimeMillis;

    public RtspServer(){
        mRtspExecutorService = Executors.newSingleThreadExecutor();
        mRtspExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mHostAddress = InetAddress.getLocalHost().getHostAddress();
                    InetSocketAddress inetSocketAddress = new InetSocketAddress(mPort);
                    mServerSocket = new ServerSocket();
                    mServerSocket.bind(inetSocketAddress);
                } catch (Exception e) {
                    mListener.error();
                    e.printStackTrace();
                }
            }
        });
    }

    public interface RtspListener {
        void play();
        void closed();
        void setResolution(String resolutionStr);
        void error();
    }
    RtspListener mListener;
    public void setCommandListener(RtspListener commandListener) {
        mListener = commandListener;
    }

    public void sendH264Nal(ByteBuffer h264Nal){
        if (mRtpServer != null) {
            mRtpServer.enqueueForSend(h264Nal);
        }
    }

    String readChunk() {
        StringBuilder builder = new StringBuilder();
        char[] buf = new char[1024];
        int numRead;

        try {
            while (0 <= (numRead = mReader.read(buf)) && !mTerminate) {
                builder.append(buf, 0, numRead);
                if (builder.substring(builder.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            if (numRead == -1) {
                mTerminate = true;
                close();
            }
        } catch (IOException e) {
            e.printStackTrace();
            close();
        }
        return builder.toString();
    }

    void Decode(String request) {
        String[] lines = request.split("\r\n");

        // Save current time for timeout check
        mPrevRequestTimeMillis = System.currentTimeMillis();

        for (int i = 1; i < lines.length; i++) {
            int pos = lines[i].indexOf(":");
            String header = lines[i].substring(0, pos).trim();
            String contents = lines[i].substring(pos + 1).trim();
            if (header.equalsIgnoreCase("CSeq")) {
                mCsec = Integer.parseInt(contents);
            }

            if (lines[0].startsWith("SETUP")) {
                if (header.equalsIgnoreCase("Transport")) {
                    String[] innerContents = contents.split(";");
                    for (int j = 0; j < innerContents.length; j++) {
                        pos = innerContents[j].indexOf("client_port=");
                        if (pos == 0) {
                            String[] portStrs = innerContents[j].substring(12).split("-");
                            mRtpClientPorts = new int[portStrs.length];
                            for (int k = 0; k < portStrs.length; k++) {
                                mRtpClientPorts[k] = Integer.parseInt(portStrs[k]);
                            }
                        }
                    }
                }
            }
        }

        if (lines[0].startsWith("OPTION")) {
            String path = lines[0].split(" ")[1];
            try {
                URI uri = new URI(path);
                String query = uri.getQuery();
                if (null != query && !query.isEmpty()) {
                    String[] pairs = query.split("&");
                    for (String pair : pairs) {
                        int idx = pair.indexOf("=");
                        if ("resolution".equalsIgnoreCase(pair.substring(0, idx).trim())) {
                            mListener.setResolution(pair.substring(idx + 1).trim());
                        }
                    }
                }
            } catch (Exception e) {
                mListener.error();
                e.printStackTrace();
            }
        }


        if (lines[0].startsWith("DESCRIBE")) {
            if (VERBOSE) Log.d(TAG, "receive DESCRIBE");
            String replyContents =
                    "v=0" + "\r\n" +
                            "o=- 0000000000 000000000 IN IP4 " + mHostAddress + "\r\n" +
                            "s=RICOH THETA live cast" + "\r\n" +
                            "i=RICOH THETA live cast" + "\r\n" +
                            "c=IN IP4 " + mHostAddress + "\r\n" +
                            "t=0 0" + "\r\n" +
                            "a=recvonly" + "\r\n" +
                            "a=range:npt=now-" + "\r\n" +
                            "m=video 0 RTP/AVP 96" + "\r\n" +
                            "a=control:rtsp://" + mHostAddress + ":" + mPort + "/video" + "\r\n" +
                            "a=rtpmap:96 H264/" + Constants.SAMPLING_RATE + "\r\n" +
                            "a=fmtp:96 packetization-mode=1" + "\r\n\r\n";

            String reply =
                    "RTSP/1.0 200 OK" + "\r\n" +
                            "CSeq: " + mCsec + "\r\n" +
                            "Content-Type: application/sdp" + "\r\n" +
                            "Content-length: " + replyContents.length() + "\r\n\r\n";

            mWriter.print(reply + replyContents);
            mWriter.flush();
            if (VERBOSE) Log.d(TAG, "reply DESCRIBE : " + reply + replyContents);
        } else if (lines[0].startsWith("OPTION")) {
            if (VERBOSE) Log.d(TAG, "receive OPTION");
            String reply =
                    "RTSP/1.0 200 OK\r\n" +
                            "CSeq: " + mCsec + "\r\n" +
                            "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY\r\n\r\n";

            mWriter.print(reply);
            mWriter.flush();
            if (VERBOSE) Log.d(TAG, "reply OPTION : " + reply);
        } else if (lines[0].startsWith("SETUP")) {
            if (VERBOSE) Log.d(TAG, "receive SETUP");
            mSessionId = getAlphaNumeric(12);
            mClientIpAddress = ((InetSocketAddress) mSocket.getRemoteSocketAddress()).getAddress().getHostAddress();

            mTimeoutTimer = new Timer();
            mTimeoutTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (System.currentTimeMillis() - mPrevRequestTimeMillis > mTimeout * 1000) {
                            close();
                        }
                    } catch (Exception e) {
                        mListener.error();
                        e.printStackTrace();
                    }
                }
            }, 1000, 1000);

            try {
                mRtpServer = new RtpServer(new InetSocketAddress(mClientIpAddress, mRtpClientPorts[0]), mRtpServerPorts[0]);
                if (VERBOSE) Log.d(TAG, "rtp server start");
                mRtpServer.start();
            } catch (Exception e) {
                mListener.error();
                e.printStackTrace();
            }

            String reply =
                    "RTSP/1.0 200 OK\r\n" +
                            "CSeq: " + mCsec + "\r\n" +
                            "Session: " + mSessionId + "; timeout=" + mTimeout + "\r\n" +
                            "Transport: RTP/AVP;unicast;client_port=" + mRtpClientPorts[0] + "-" + mRtpClientPorts[1] + ";server_port=" + mRtpServerPorts[0] + "-" + mRtpServerPorts[1] + "\r\n\r\n";

            mWriter.print(reply);
            mWriter.flush();
            if (VERBOSE) Log.d(TAG, "reply SETUP : " + reply);
        } else if (lines[0].startsWith("PLAY")) {
            if (VERBOSE) Log.d(TAG, "receive PLAY");
            String reply =
                    "RTSP/1.0 200 OK" + "\r\n" +
                            "CSeq: " + mCsec + "\r\n" +
                            "Session: " + mSessionId + "; timeout=" + mTimeout + "\r\n" +
                            "Transport: RTP/AVP;unicast;client_port="  + mRtpClientPorts[0] + "-" + mRtpClientPorts[1] + ";server_port="  + mRtpServerPorts[0] + "-" + mRtpServerPorts[1] + "\r\n\r\n";
            mWriter.print(reply);
            mWriter.flush();
            mListener.play();
            if (VERBOSE) Log.d(TAG, "reply PLAY : " + reply);
        } else if (lines[0].startsWith("TEARDOWN")) {
            if (VERBOSE) Log.d(TAG, "receive TEARDOWN");

            close();
            String reply =
                    "RTSP/1.0 200 OK\r\n" +
                            "CSeq: " + mCsec + "\r\n\r\n";
            mWriter.print(reply);
            mWriter.flush();
            if (VERBOSE) Log.d(TAG, "reply TEARDOWN : " + reply);
        } else {
            String reply =
                    "RTSP/1.0 405 Method Not Allowed\r\n" +
                            "CSeq: " + mCsec + "\r\n" +
                            "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n";
            mWriter.print(reply);
            mWriter.flush();
            if (VERBOSE) Log.d(TAG, "reply : " + reply);
        }
    }

    public String getAlphaNumeric(int len) {
        char[] ch = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
                'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
                'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
                'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
                'w', 'x', 'y', 'z' };

        char[] c=new char[len];
        Random random=new Random();
        for (int i = 0; i < len; i++) {
            c[i]=ch[random.nextInt(ch.length)];
        }

        return new String(c);
    }

    public void open(){
        if (VERBOSE) Log.d(TAG, "start open");
        mPrevRequestTimeMillis = System.currentTimeMillis();

        mRtspExecutorService.execute(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        System.out.println("Rtsp Server Socket accept.");
                        mSocket = mServerSocket.accept();
                        System.out.println("Rtsp Server Socket accepted.");
                        mReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));
                        mWriter = new PrintWriter(mSocket.getOutputStream(), true);
                        mTerminate = false;
                        while (!mTerminate) {
                            String request = readChunk();
                            Decode(request);
                        }
                        System.out.println("Rtsp Server Terminated.");
                    } catch (SocketException e) {
                        // if server socket close this block done.
                        close();
                        System.out.println("If you close the server socket," +
                                "this Socket closed Exception is an expected Exception.");
                        e.printStackTrace();
                    } catch (IOException e) {
                        mListener.error();
                        close();
                        e.printStackTrace();
                    }
                    mListener.closed();
                }
            });
    }

    @Override
    public void close(){
        if (VERBOSE) Log.d(TAG, "start close.");
        try{
            if(mTimeoutTimer != null){
                mTimeoutTimer.cancel();
                mTimeoutTimer.purge();
            }
            if (mRtpServer != null){
                mRtpServer.close();
                mRtpServer = null;
            }
            if (mSocket != null) {
                mSocket.close();
            }
            mTerminate = true;
        }
        catch (IOException e){
            e.printStackTrace();
        }
        if (VERBOSE) Log.d(TAG, "rtsp server close done.");
    }

    public void shutdown(){
        if (VERBOSE) Log.d(TAG, "start shutdown.");
        mRtspExecutorService.shutdown();
        close();
//        try{
//            if (mServerSocket != null) {
//                if (!mServerSocket.isClosed()) {
//                    mServerSocket.close();
//                }
//            }
//        }
//        catch (IOException e){
//            e.printStackTrace();
//        }
        if (VERBOSE) Log.d(TAG, "shutdown done.");
    }
}

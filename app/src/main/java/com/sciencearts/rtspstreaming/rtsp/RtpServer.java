package com.sciencearts.rtspstreaming.rtsp;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

class RtpServer extends Thread implements AutoCloseable {
    private static final String TAG = "RtpServer";

    AtomicBoolean mTerminate = new AtomicBoolean(false);

    BlockingQueue<ByteBuffer> mQueue = new LinkedBlockingQueue<>();
    DatagramChannel mChannel;
    InetSocketAddress mTargetSocketAddress;
    RtpPacketSender mRtpPacketSender = new RtpPacketSender();

    public RtpServer(InetSocketAddress targetSocketAddress, int rtpServerPort){
        try {
            mChannel = DatagramChannel.open();
            mChannel.socket().bind(new InetSocketAddress(rtpServerPort));
            this.mTargetSocketAddress = targetSocketAddress;
        } catch (Exception e) {
            e.printStackTrace();
            close();
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "RTP server closing.");
        try {
            mTerminate.set(true);
            mQueue.put(ByteBuffer.allocate(0));
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "RTP server closed.");
    }

    public void enqueueForSend(ByteBuffer h264Nal){
        try {
            mQueue.put(h264Nal);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (!mTerminate.get()) {
            try {
                ByteBuffer h264Nal = mQueue.take();
                if (!mTerminate.get() && h264Nal.limit() != 0) {
                    mRtpPacketSender.send(mChannel, mTargetSocketAddress, h264Nal);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try {
            if (mChannel != null) {
                mChannel.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "RTP server terminated");
    }
}

class RtpPacketSender{
    ByteBuffer mRtpPacketBuffer = ByteBuffer.allocate(1400);
    short mSeqNumber;
    int mTimestamp;
    int mSsid = 0x12340000;
    static final int INCRIMENT_UNIT = Constants.SAMPLING_RATE / Constants.FPS;

    public RtpPacketSender() {
        mSeqNumber = (short)(new Random().nextInt());
        mTimestamp = 0;
        mSsid = new Random().nextInt();
    }

    public void send(DatagramChannel channel, InetSocketAddress targetSocketAddress, ByteBuffer h264NalByteBuffer){

        int limit = h264NalByteBuffer.limit();

        // Skip the start code and go to NAL unit head
        h264NalByteBuffer.position(4);

        byte type = (byte)(h264NalByteBuffer.get() & (byte)0x01F);

        h264NalByteBuffer.limit(5);

        mTimestamp += INCRIMENT_UNIT;

        // Use RTP Payload Format for H.264 Video.
        // Encapsulate all RTP Packet in Fragmentation Unit (NAL Unit Type: 28)
        // for ease of implementation.
        // Large size data is divided and sent by the while statement
        // so that UDP packet is not fragmented.
        boolean first = true;
        while (h264NalByteBuffer.limit() < limit) {
            mSeqNumber++;

            mRtpPacketBuffer.clear();

            int newLimit;
            if ((h264NalByteBuffer.limit() +  mRtpPacketBuffer.capacity() - 14) < limit) {
                newLimit = (h264NalByteBuffer.limit() +  mRtpPacketBuffer.capacity() - 14);
            } else {
                newLimit = limit;
            }

            mRtpPacketBuffer.put((byte)0x80);         // version | padding | extension | CSRC Count
            if (newLimit == limit) {
                mRtpPacketBuffer.put((byte)0xE0);         // marker(true) | PT(DynamicRTP-Type-96)
            } else {
                mRtpPacketBuffer.put((byte)0x60);         // marker(false) | PT(DynamicRTP-Type-96)
            }
            mRtpPacketBuffer.putShort(mSeqNumber);
            mRtpPacketBuffer.putInt(mTimestamp);
            mRtpPacketBuffer.putInt(mSsid);

            h264NalByteBuffer.limit(newLimit);

            mRtpPacketBuffer.put((byte)0x5C);    // F | NRI(10) | TYPE(28)
            byte fuHeader = (byte)((byte)0x1F & type);
            if (first) {
                fuHeader = (byte) (fuHeader | (byte)0x80);      // set start bit
                first = false;
            }
            if (newLimit == limit) {
                fuHeader = (byte) (fuHeader | (byte)0x40);      // set end bit
            }
            mRtpPacketBuffer.put(fuHeader);
            mRtpPacketBuffer.put(h264NalByteBuffer);

            try {
                mRtpPacketBuffer.flip();
                channel.send(mRtpPacketBuffer, targetSocketAddress);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

package com.tv.screenmirror.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.LinearLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import com.android.grafika.CircularEncoderBuffer;

@SuppressLint("NewApi")
public class VideoWindow extends LinearLayout implements SurfaceHolder.Callback{

    LayoutInflater mInflater;
    public static SurfaceView surfaceView; //bsp

    private int mWidth = 1080;
    private int mHeight = 1920;

    MediaCodec decoder;
    ByteBuffer[] decoderInputBuffers = null;
    ByteBuffer[] decoderOutputBuffers = null;
    MediaFormat decoderOutputFormat = null;
    boolean decoderConfigured = false;

    CircularEncoderBuffer encBuffer;

    //private static final String TAG = "lajart";

    private boolean firstIFrameAdded = false;

    public VideoWindow(Context context) {
        super(context);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public VideoWindow (Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoWindow (Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void inflateSurfaceView() {
        surfaceView = (SurfaceView) findViewById(R.id.video_surface_view);
        surfaceView.getHolder().addCallback(this);
        encBuffer = new CircularEncoderBuffer((int)(1024 * 1024 * 2), 60, 7);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            decoder = MediaCodec.createDecoderByType("video/avc");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    doDecoderThingie();
                }
            }).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }
    static int count=0;
    public void doDecoderThingie() {
        boolean outputDone = false;

        while(!decoderConfigured) {
        }

        //if (MainActivity.DEBUG) Log.d(TAG, "Decoder Configured");

        while(!firstIFrameAdded) {}

        int index = encBuffer.getFirstIndex();
        if (index < 0) {
            //Log.e(TAG, "CircularBuffer Error");
            return;
        }
        ByteBuffer encodedFrames;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!outputDone) {
            encodedFrames = encBuffer.getChunk(index, info);
            encodedFrames.limit(info.size + info.offset);
            encodedFrames.position(info.offset);

            try {
                index = encBuffer.getNextIntCustom(index);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            int inputBufIndex = decoder.dequeueInputBuffer(-1);
            if (inputBufIndex >= 0) {
                ByteBuffer inputBuf = decoder.getInputBuffer(inputBufIndex);
                inputBuf.clear();
                inputBuf.put(encodedFrames);
                decoder.queueInputBuffer(inputBufIndex, 0, info.size,
                        info.presentationTimeUs, info.flags);
            }

            if (decoderConfigured) {
                int decoderStatus = decoder.dequeueOutputBuffer(info, 600000);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //if (MainActivity.DEBUG) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    //if (MainActivity.DEBUG) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // this happens before the first frame is returned
                    decoderOutputFormat = decoder.getOutputFormat();
                    //Log.d(TAG, "decoder output format changed: " +
                     //       decoderOutputFormat);
                } else {
                    count++;
                    //Log.d(TAG, "decode a frame "+count);
                    decoder.releaseOutputBuffer(decoderStatus, true);
                }
            }
        }
    }

    static int addframecount = 0;
    public void setData(ByteBuffer encodedFrames, MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            //Log.d(TAG, "Configuring Decoder");
            MediaFormat format =
                    MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
            //Log.d(TAG, "Configuring Decoder.. " + "video/avc" + mWidth + mHeight);
            format.setByteBuffer("csd-0", encodedFrames);
            //Log.d(TAG, "Configuring Decoder...");
            try {
                // wait some time bsp
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            decoder.configure(format, surfaceView.getHolder().getSurface(),
                    null, 0);
            //Log.d(TAG, "Decoder start");
            decoder.start();
            //Log.d(TAG, "Decoder start ok");
            decoderInputBuffers = decoder.getInputBuffers();
            decoderOutputBuffers = decoder.getOutputBuffers();
            decoderConfigured = true;
            //Log.d(TAG, "decoder configured (" + info.size + " bytes)");
            //return;
        }
        encBuffer.add(encodedFrames, info.flags, info.presentationTimeUs);
        addframecount++;
        //Log.d(TAG, "add a frame to dec "+addframecount);
        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            firstIFrameAdded = true;
        }
    }
}

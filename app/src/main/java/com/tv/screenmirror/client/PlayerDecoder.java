package com.tv.screenmirror.client;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Point;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.os.Process;

import com.android.grafika.CircularEncoderBuffer;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.http.WebSocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Created by Libin on 2017/9/18.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
//public abstract class PlayerDecoder implements TextureView.SurfaceTextureListener{
public class PlayerDecoder{
    private String TAG = "iCastingDecoder";
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;

    private boolean VideoDecoderConfigured = false;
    private boolean AudioDecoderConfigured = false;

    private MediaCodec.BufferInfo mVideoInfo = new MediaCodec.BufferInfo();
    private MediaCodec.BufferInfo mAudioInfo = new MediaCodec.BufferInfo();
    private long mAudioSamplePTS;

    private boolean enableAudio = true;

    Context mContext;
    Lock Videolock = new ReentrantLock();
    Lock Audiolock = new ReentrantLock();

    Lock mcloselock = new ReentrantLock();
    private int mVideoDecUsed = 0;
    private int mAudioDecUsed = 0;
    int deviceWidth;
    int deviceHeight;
    Point videoResolution = new Point();
    Boolean AudioDataReady = false;
    Boolean AudioPlayReady = false;

    private int VideoStreamRecvNum = -1;
    private int AudioStreamRecvNum = -1;

    boolean outputDone = false;
    private boolean firstIFrameAdded;
    private static boolean mDecodeWork = false;

    Surface mSurface = null;
    private String mRemoteIp = null;
    //private int mWatchDog = 0;
    private int mFrameWidth = 1280;
    private int mFrameHeigh = 720;
    private int mRotate = 0;
    private long mRotateUs = 0;
    private int mPreRotate = 0;
    //private int defaultFamerate = 30;
    private int defaultFamerate = 60;
    private long comsumed_ts = 0;


    private int msetclose = 0;

    private  WebSocket mVideoDataSocket = null;
    private  WebSocket mControlSocket = null;
    private  WebSocket mAudioSocket = null;

    private int mWatchDogStatus = 0;
    private final int mEcodeBitRate = (int) (1024 * 1024 * 4);
    private Boolean isOsVersion = false;
    private boolean VideofeedStatus = false;
    private boolean VideoDecoderErrorFlag = false;

    private boolean AudiofeedStatus = false;
    private boolean AudioDecoderErrorFlag = false;
    private boolean isMTKDecoderFound =false;
    private AudioPlayer mAudioPlayer = null;
    private Boolean isIOSInitializeVideoCodec =false;

    private LinkedBlockingQueue<FrameInfo> VideoList = null;
    private LinkedBlockingQueue<FrameInfo> AudioList = null;

    private Boolean enableAudioSync =false;
    private long lastAudioTs = -1;
    private long AudioPlayTime = 0;
    private String mCodecType = MediaFormat.MIMETYPE_VIDEO_AVC ;

    private long audio_play_ts = 0;
    private long audio_recv_ts = 0;
    private int TVRotation = 0;
    private int TVRotationStart = 0;

    public interface DecoderListener {
        public void setStaus(String Status, PlayerDecoder decoder);
        public void SetUIHw(int w, int h, int rotate, int TVRotate, PlayerDecoder decoder);
    }
    public boolean enableListBuffer = true;
    public DecoderListener mListener = null;

    public void setListener(DecoderListener tlistener){
        mListener = tlistener;
    }

    public static boolean getRunStatus(){
        return mDecodeWork;
    }

    public PlayerDecoder(WebSocket socketData,
                         WebSocket socketAudio,
                         WebSocket socketCtrl,
                         int EncodeCodecType,
                         //String ip,
                         //Context context,
                         DecoderListener obj
    ){

        mVideoDataSocket = socketData;
        mAudioSocket = socketAudio;
        mControlSocket = socketCtrl;
        //mRemoteIp = ip;
        //Log.d(TAG, "==========> IP="+ip);
        //mContext = context;
        mListener = obj;

        if(EncodeCodecType == ClientActivity.CODEC_AVC_FLAG){
            mCodecType = MediaFormat.MIMETYPE_VIDEO_AVC ;
        }

        if(EncodeCodecType == ClientActivity.CODEC_HEVC_FLAG){
            mCodecType = MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        VideoList= new LinkedBlockingQueue<FrameInfo>();
        AudioList= new LinkedBlockingQueue<FrameInfo>();
    }

    public PlayerDecoder(WebSocket socket){
        mVideoDataSocket = socket;
    }

//    public String getRemoteIp(){
//        return mRemoteIp;
//    }

    public void setSurface(Surface surface){
        mSurface = surface;

        StartDecoderThread();
    }

    public void OnDestroySurface(){
        MirServerStop();
        int timeout=20;
        while( (VideofeedStatus == true) || (VideoThingieStatus==true))
        {
            if(timeout <= 0){
                break;
            }
            waitTimes(100);
            timeout --;
        }
    }

    public  int GetWatchDogStatus(){
        return mWatchDogStatus;
    }

    public  void IncreaseWatchDog(){
        mWatchDogStatus++ ;
    }

    public  void  ClearWatchDog(){
        mWatchDogStatus = 0 ;
    }

    public  void sendDog(long comsumed_ts) {
        if (mControlSocket != null) {
            mControlSocket.send("dog"+ comsumed_ts);//modified by Huazhu Sun
        }
    }

    public  void sendStartData() {
        if (mControlSocket != null) {
            Log.d(TAG, "send SENDTDATA:"+ mControlSocket);
            mControlSocket.send("SENDTDATA");
        }
    }
    public  void sendVersion() {
        if (mControlSocket != null) {
            Log.d(TAG, "send VERSION:"+ mControlSocket);
            //mControlSocket.send("VERSION:testVersion_1");
            mControlSocket.send("DecoderStatus:OK");
        }
    }
    public  void closeControlSocket()
    {
        if (mControlSocket != null) {
            Log.d(TAG, "Close control websocket");

            mControlSocket.close();
            mControlSocket = null;
        } else {
            Log.d(TAG, "mControlSocket is already null");
        }
    }

    public void closeDataSocket()
    {
        if (mVideoDataSocket != null) {
            Log.d(TAG, "Close Data websocket");

            mVideoDataSocket.close();
            mVideoDataSocket = null;
        } else {
            Log.d(TAG, "mVideoDataSocket is already null");
        }
    }

    public void closeAudioSocket()
    {
        if (mAudioSocket != null) {
            Log.d(TAG, "Close Audio websocket");

            mAudioSocket.close();
            mAudioSocket = null;
        } else {
            Log.d(TAG, "mAudioSocket is already null");
        }
    }



    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2; // AAC LC
        int freqIdx = 4; // 44.1KHz
        int chanCfg = 1; // CPE

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    private void checkDecoderStatus(){
        int n = MediaCodecList.getCodecCount();
        for (int i = 0; i < n; ++i) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            String[] supportedTypes = info.getSupportedTypes();
            if(info.isEncoder()){
                continue;
            }
            boolean mime_support = false;
            for (int j = 0; j < supportedTypes.length; ++j) {
                Log.d(TAG, "codec info:" + info.getName()+" supportedTypes:" + supportedTypes[j]);
            }
        }
    }
    /*public boolean setSocketCallback(){*/
    public boolean StartDecoderThread() {
        Log.d(TAG, "StartDecoderThread123");
        checkDecoderStatus();
        try {
            VideoConfigInit();
            //mVideoDecoder = MediaCodec.createDecoderByType("video/avc");
            Log.d(TAG,"mCodecType:"+mCodecType);

            if(mVideoDecoder != null)
            {
                Log.d(TAG,"wangweicai mVideoDecoder != null,so stop");
                mVideoDecoder.stop();
                mVideoDecoder =null;
            }

            mVideoDecoder = MediaCodec.createDecoderByType(mCodecType);
        } catch (IOException e) {
            Log.d(TAG, "StartDecoderThread create Video Decoder Error");
            e.printStackTrace();
            return false;
        }

        mAudioPlayer = new AudioPlayer(44100,  AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mAudioPlayer.init();

        AudioDecoderConfigured =true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                doVideoDecoderThingie();
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                doVideoDecoderFeed();
            }
        }).start();
        if(enableAudio == true) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    doAudioPlay();
                }
            }).start();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendDog();
            }
        }).start();
        //Close callback
        mVideoDataSocket.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception e) {
                try {
                    if (e != null)
                        e.printStackTrace();
                } finally {
                    Log.d(TAG, "data socket closed");
                    mVideoDataSocket = null;
                    MirServerStop();
                }
            }
        });
        if(mAudioSocket != null) {
            mAudioSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception e) {
                    try {
                        if (e != null)
                            e.printStackTrace();
                    } finally {
                        Log.d(TAG, "Audio socket closed");
                        mAudioSocket = null;
                        MirServerStop();
                    }
                }
            });
        }

        mControlSocket.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception e) {
                try {
                    if (e != null)
                        e.printStackTrace();
                } finally {
                    Log.d(TAG, "control socket closed");
                    mControlSocket = null;
                    MirServerStop();
                }
            }
        });

        mVideoDataSocket.setDataCallback(new DataCallback() {
            @Override
            public void onDataAvailable(DataEmitter dataEmitter, ByteBufferList byteBufferList) {
                ++VideoStreamRecvNum;
                ClearWatchDog();
                //Log.d(TAG, "i=" + VideoStreamRecvNum);
                ByteBuffer b = byteBufferList.getAll();
                if(outputDone) {
                    byteBufferList.recycle();
                    return;
                }
//
                //         Log.d(TAG, "b length is:"+b.array().length);
                int offset = 0;
                for(int i=0;i<b.array().length;i++){
                    if(b.array()[i] == '.'){
                        offset=i;
                        break;
                    }
                }

                byte[] hdata = new byte[offset];
                System.arraycopy(b.array(), 0, hdata, 0, offset);
                String temp = new String(hdata);
                //Log.d(TAG, "Received video header = " + temp);
                String[] infoStringParts = temp.split(",");

                mVideoInfo.set(Integer.parseInt(infoStringParts[0]), Integer.parseInt(infoStringParts[1]),
                        Long.parseLong(infoStringParts[2]), Integer.parseInt(infoStringParts[3]));//offset, size, us, flag

                int vdata_len= b.array().length - offset - 1;
                byte[] vdata = new byte[vdata_len];;
                System.arraycopy(b.array(), offset + 1, vdata, 0, vdata_len);
                ByteBuffer c =  ByteBuffer.wrap(vdata);

                c.position(0);
                //Log.d(TAG, "c size "+c.array().length+",Received pps header = " + c.array()[0]+","+ c.array()[1]+","+ c.array()[2]+","+ c.array()[3]+":====");
                setVideoData(c, mVideoInfo);

                byteBufferList.recycle();
            }
        });

        mVideoDataSocket.setStringCallback(new WebSocket.StringCallback() {
            public void onStringAvailable(String s) {
                Log.d(TAG, "Recv Video String msg:" + s);

                if(s.startsWith("sethw:")){
                    //设置分辨率
                    Log.d(TAG, "recv msg sethw:" + s);
                    String setwh = s.split(":")[1];
                    String vals[] = setwh.split("x");
                    mFrameWidth =Integer.parseInt(vals[0]);
                    mFrameHeigh = Integer.parseInt(vals[1]);
                    setUiHw();
                }else if(s.startsWith("rotationsethw:")){
                    Log.d(TAG, "recv rotationsethw:" + s);
                    String setwhr = s.split(":")[1];
                    String vals[] = setwhr.split("x");
                    mFrameWidth = Integer.parseInt(vals[0]);
                    mFrameHeigh = Integer.parseInt(vals[1]);

                }else if(s.startsWith("rotation:")){
                    //重新设置分辨率：
                    Log.d(TAG, "recv rotation:" + s);
                    isOsVersion =true;
                    String setwhr = s.split(":")[1];
                    String vals[] = setwhr.split(",");
                    mRotate = Integer.parseInt(vals[2]);
                    mRotateUs= Long.parseLong(vals[3]);

                    if(mRotate == 0) {
                        mFrameWidth = Integer.parseInt(vals[0]);
                        mFrameHeigh = Integer.parseInt(vals[1]);
                    }
                    else{
                        mFrameWidth = Integer.parseInt(vals[1]);
                        mFrameHeigh = Integer.parseInt(vals[0]);
                    }
                    //setUiHw();
                    //通知UI，Decoder设置
                }else if (s.equals("live")){
                    mWatchDogStatus = 0;
                }
                else if(s.startsWith("TVRotation:")){
                    String TVRotate = s.split(":")[1];
                    TVRotation = Integer.parseInt(TVRotate);
                    Log.d(TAG, "TVRotation:" + TVRotation);
                    setUiHw();
                }
            }
        });
        if(mAudioSocket != null) {
            mAudioSocket.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter dataEmitter, ByteBufferList byteBufferList) {
                    ++AudioStreamRecvNum;
                    ClearWatchDog();

                    //Log.d(TAG, "Audio onDataAvailable");
                    ByteBuffer b = byteBufferList.getAll();
                    //if (outputDone) {
                    if((enableAudio == false)||outputDone){
                        byteBufferList.recycle();
                        return;
                    }

                    //Log.d(TAG, "Audio b length is:"+b.array().length);
                    int offset = 0;
                    for(int i=0;i<b.array().length;i++){
                        if(b.array()[i] == '.'){
                            offset=i;
                            break;
                        }
                    }
//                    Log.d(TAG, "Audio b offset is:"+offset);
//                    Log.d(TAG, "Audio b remaining is:"+b.remaining());
                    byte[] hdata = new byte[offset];
                    System.arraycopy(b.array(), 0, hdata, 0, offset);
                    String temp = new String(hdata);
//                    Log.d(TAG, "Audio Received String header = " + temp);
                    mAudioSamplePTS = Long.parseLong(temp);

                    int vdata_len= b.array().length - offset - 1;

                    byte[] vdata = new byte[vdata_len];;
                    System.arraycopy(b.array(), offset + 1, vdata, 0, vdata_len);
                    ByteBuffer c =  ByteBuffer.wrap(vdata);

                    c.position(0);
                    setAudioData(c, mAudioSamplePTS);
                    audio_recv_ts = mAudioSamplePTS;
                    Log.d(TAG, "AudioData Received ts = " + audio_recv_ts);
                    byteBufferList.recycle();
                }
            });

            mAudioSocket.setStringCallback(new WebSocket.StringCallback() {
                public void onStringAvailable(String s) {
                    Log.d(TAG, "Recv Audio String msg:" + s);
                }
            });
        }
        mControlSocket.setStringCallback(new WebSocket.StringCallback() {
            public void onStringAvailable(String s) {
                //Log.d(TAG, "Recv Control String msg:" + s);
                if (s.equals("bye")) {
                    Log.d(TAG, "Recv control bye msg");
                    MirServerStop();
                }else if(s.startsWith("sethw:")){//iOs first
                    //设置分辨率
                    Log.d(TAG, "Recv control sethw msg:" + s);

                    String setwhr = s.split(":")[1];
                    String vals[] = setwhr.split("x");
                    mFrameWidth = Integer.parseInt(vals[0]);
                    mFrameHeigh = Integer.parseInt(vals[1]);
                    setUiHw();
                }else if(s.startsWith("rotationsethw:")){
                    //重新设置分辨率：
                    Log.d(TAG, "Recv control rotationsethw msg:" + s);
                    String setwh = s.split(":")[1];
                    String vals[] = setwh.split("x");
                }
                else if(s.startsWith("enableAudioSync:")){
                    //重新设置分辨率：
                    String AudioSync = s.split(":")[1];
                    Log.d(TAG, "Recv control enableAudioSync msg:" + AudioSync);
                    int AudioSyncStatus  = Integer.parseInt(AudioSync);
                    if(AudioSyncStatus != 0) {
                        Log.d(TAG, "set enableAudioSync true");
                        enableAudioSync =  true;
                    }
                    else {
                        Log.d(TAG, "set enableAudioSync false");
                        enableAudioSync =  false;
                    }
                }
                else if(s.startsWith("rotation:")){//same as TV rotation in Data Socket but removed here
                    String rotation = s.split(":")[1];
                    //重新设置分辨率：
                    Log.d(TAG, "Recv control rotationsethw msg" + rotation);
                    mRotate = Integer.parseInt(rotation);
                    setUiHw();
                }
                else if (s.equals("live")){
                    mWatchDogStatus = 0;
                }
            }
        });

        sendVersion();
        sendStartData();
        return true;
    }



    private void addVideoDecoder(){
        Videolock.lock();
        mVideoDecUsed += 1;
        Videolock.unlock();
    }
    private void addAudioDecoder(){
        Audiolock.lock();
        mAudioDecUsed += 1;
        Audiolock.unlock();
    }

    @SuppressLint("NewApi")
    private void stopVideoDecoder(){
        if (mVideoDecoder != null) {
            Log.d(TAG, "stop video decoder ");
            try{

                if(VideoDecoderConfigured == true) {
                    if (!VideoDecoderErrorFlag) {
                        int inputBufIndex = mVideoDecoder.dequeueInputBuffer(-1);
                        if (inputBufIndex >= 0) {
                            mVideoDecoder.queueInputBuffer(inputBufIndex, 0, 0,
                                    0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            Log.d(TAG, "Video failed queueInputBuffer");
                            return;
                        }

                        waitTimes(20);
                        Log.d(TAG, "Video QueueInputBuffer end");
                    }

                    int decoderStatus;
                    MediaCodec.BufferInfo tinfo = new MediaCodec.BufferInfo();
                    //force crash if jamed when stop
                    while (!outputDone) {
                        decoderStatus = mVideoDecoder.dequeueOutputBuffer(tinfo, 20000);
                        if (decoderStatus >= 0) {
                            if ((tinfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.d(TAG, "Video get end of stream flag with size:" + tinfo.size);
                                break;
                            }

                            if (tinfo.size != 0) {
                                Log.d(TAG, "Video Get unrendered buffer");
                                mVideoDecoder.releaseOutputBuffer(decoderStatus, false);
                            }
                        }
                    }

                    Log.d(TAG, "video dequeueOutputBuffer end");

                    Log.d(TAG, "Video Decoder stopped");
                    waitTimes(500);
                    mVideoDecoder.stop();
                    mVideoDecoder.reset();
                }
                mVideoDecoder.release();
                mVideoDecoder = null;
            }catch(Exception e){
                Log.d(TAG, "stopVideoDecoder Error:"+e);
                if(mVideoDecoder != null){
                    mVideoDecoder.reset();
                    mVideoDecoder.release();
                    mVideoDecoder = null;
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private void freeVideoDecoder(){
        Videolock.lock();
        Log.d(TAG, "freeVideoDecoder mVideoDecUsed:" + mVideoDecUsed);
        if (mVideoDecUsed > 0){
            mVideoDecUsed  -= 1;
        }

        if(mVideoDecUsed == 0) {
            stopVideoDecoder();
            Log.d(TAG, "set Delete CMD :" + this);
            Log.d(TAG, "Delete:" + this);
            mListener.setStaus("delete", this);
            Videolock.unlock();
        }
        else{
            Videolock.unlock();
        }
    }

    private void VideoConfigInit(){
        Log.d(TAG,"=======VideoConfigInit");
        VideoDecoderConfigured = false;
        firstIFrameAdded = false;
        // isIOSInitializeVideoCodec = false;
    }
    private void AudioConfigInit(){
        AudioDecoderConfigured = false;
        AudioDataReady =false;
        AudioPlayReady = false;
    }

    private void waitTimes(long times){
        try {
            Thread.sleep(times);
        }catch (InterruptedException e){
        }
    }

    public class AudioPlayer {
        private int mFrequency;// 采样率
        private int mChannel;// 声道
        private int mSampBit;// 采样精度
        private AudioTrack mAudioTrack;

        public AudioPlayer(int frequency, int channel, int sampbit) {
            this.mFrequency = frequency;
            this.mChannel = channel;
            this.mSampBit = sampbit;
        }

        /**
         * 初始化
         */
        public void init() {
            if (mAudioTrack != null) {
                release();
            }
            // 获得构建对象的最小缓冲区大小
            int minBufSize = AudioTrack.getMinBufferSize(mFrequency, mChannel, mSampBit);
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    mFrequency, mChannel, mSampBit, minBufSize, AudioTrack.MODE_STREAM);
            mAudioTrack.play();
        }

        /**
         * 释放资源
         */
        private void release() {
            if (mAudioTrack != null) {
                mAudioTrack.stop();
                mAudioTrack.release();
            }
        }

        /**
         * 将解码后的pcm数据写入audioTrack播放
         *
         * @param data   数据
         * @param offset 偏移
         * @param length 需要播放的长度
         */
        public void play(byte[] data, int offset, int length) {
            if (data == null || data.length == 0) {
                return;
            }
            try {
                mAudioTrack.write(data, offset, length);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private void doAudioPlay(){
        int index = 0;
        Log.d(TAG, "doAudioPlay Enter with index "+ index);
        ByteBuffer encodedFrame;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        FrameInfo audioFrame;
        while (!outputDone) {
            if(AudioDataReady == false){
                waitTimes(1);
                continue;
            }


            audioFrame = AudioList.poll();
            if(audioFrame == null){
                Log.d(TAG, "doAudioPlay get AudioList with null");
                continue;
            }
            encodedFrame= audioFrame.encodedFrame;
            info.presentationTimeUs = audioFrame.ptsUsec;
            info.flags = audioFrame.flags;
            info.offset = 0;
            info.size =  encodedFrame.limit() - encodedFrame.position();
            //Log.e(TAG, "AudioList pts "+audioFrame.ptsUsec+",flags:"+info.flags+"size:"+info.size);


            byte[] outData =new byte[encodedFrame.remaining()];
            encodedFrame.get(outData, 0, outData.length);
            //Log.d(TAG, "doAudioPlay get with outData.length: "+outData.length);

            if(outData.length == 0){
                waitTimes(1);
                continue;
            }
            Log.d(TAG, "play audio when recv:"+ audio_recv_ts+",while play with ts:"+info.presentationTimeUs);
            // if( (audio_recv_ts - info.presentationTimeUs ) < 2000){
            if(true){
                mAudioPlayer.play(outData, 0, outData.length);
                lastAudioTs = info.presentationTimeUs;
                AudioPlayTime =System.currentTimeMillis();
                Log.d(TAG, "Now Audio time:"+ AudioPlayTime+", with ts:"+lastAudioTs);
            }

            AudioPlayReady = true;

            while (!outputDone) {
                if (AudioList.isEmpty() == false) {
                    break;
                }
                waitTimes(1);
            }

        }
        Log.e(TAG, "Audio  Player exit");
        AudioList.clear();
        AudioList = null;
        mAudioPlayer.release();
        mAudioPlayer=null;
    }

    public void doVideoDecoderFeed() {

        while (!VideoDecoderConfigured || !firstIFrameAdded) {
            if (outputDone) {
                freeVideoDecoder();
                return;
            }
            waitTimes(10);
        }

        int index = 0;
        Log.d(TAG, "doVideoDecoderFeed Enter with index "+ index);
        addVideoDecoder();
        ByteBuffer encodedFrames;
        FrameInfo videoFrame;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        VideoDecoderErrorFlag = false;
        VideofeedStatus =false;
        int inputBufIndex = 0;
        while (!outputDone) {
            if (!VideoDecoderConfigured) {
                waitTimes(1);
                continue;
            }

            VideofeedStatus = true;
            //Log.d(TAG, "start dequeue input buffer");
            try {
                inputBufIndex = mVideoDecoder.dequeueInputBuffer(-1);
                if (inputBufIndex < 0) {
                    VideofeedStatus = false;
                    waitTimes(1);
                    continue;
                }
            }
            catch (IllegalStateException  e) {
                Log.e(TAG, "dequeueInputBuffer error");
                e.printStackTrace();
                VideoDecoderErrorFlag = true;
                VideofeedStatus = false;
                break;
            }


            try{
                videoFrame = VideoList.poll();
                if(videoFrame == null){
                    Log.d(TAG, "doVideoDecoderFeed get videoList with null");
                    mVideoDecoder.queueInputBuffer(inputBufIndex, 0, 0,
                            0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    VideoDecoderErrorFlag = true;
                    VideofeedStatus= false;
                    break;
                }
                encodedFrames= videoFrame.encodedFrame;
                info.presentationTimeUs = videoFrame.ptsUsec;
                info.flags = videoFrame.flags;
                info.offset = 0;
                info.size =  encodedFrames.limit() - encodedFrames.position();
                //Log.e(TAG, "VideoList pts "+videoFrame.ptsUsec+",flags:"+info.flags+",size:"+info.size);
            } catch (Exception e) {
                Log.e(TAG, "encodedFrames error");
                e.printStackTrace();
                Log.d(TAG, "info.size:"+info.size+" info.offset:"+info.offset);
                mVideoDecoder.queueInputBuffer(inputBufIndex, 0, 0,
                        0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                VideoDecoderErrorFlag = true;
                VideofeedStatus= false;
                break;
            }

            try {

                ByteBuffer[] inputBuf = mVideoDecoder.getInputBuffers();
                inputBuf[inputBufIndex].clear();
                inputBuf[inputBufIndex].put(encodedFrames);
                mVideoDecoder.queueInputBuffer(inputBufIndex, 0, info.size,  info.presentationTimeUs * 1000, 0);
                if(info.presentationTimeUs >= mRotateUs){
                    if(mPreRotate != mRotate)
                    {
                        setUiHw();
                        mPreRotate = mRotate;
                    }
                }
                //Log.d(TAG, "end queue input buffer");
                comsumed_ts = info.presentationTimeUs;//added by Huazhu Sun

            } catch (IllegalStateException e) {
                Log.e(TAG, "queueInputBuffer error");
                e.printStackTrace();
                VideofeedStatus= false;
                VideoDecoderErrorFlag = true;
                break;
            }

            //waitTimes(5);
            VideofeedStatus= false;
            while (!outputDone) {
                if (VideoList.isEmpty() == false) {
                    break;
                }
                waitTimes(1);
            }
        }
        Log.d(TAG, "doVideoDecoderFeed exit");
        VideofeedStatus = false;
        VideoList.clear();
        VideoList = null;
        MirServerStop();
        freeVideoDecoder();
    }


    private boolean VideoThingieStatus =false;
    public void doVideoDecoderThingie() {
        while (!VideoDecoderConfigured || !firstIFrameAdded) {
            if (outputDone) {
                freeVideoDecoder();
                return;
            }
            waitTimes(1);
        }

        addVideoDecoder();
        Log.d(TAG, "doVideoDecoderThingie enter");
        //ByteBuffer encodedFrames;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!outputDone) {
            if (!VideoDecoderConfigured) {
                waitTimes(1);
                continue;
            }

            VideoThingieStatus =true;
            //Log.d(TAG, "start dequeue output buffer");
            int decoderStatus;
            try {
                decoderStatus = mVideoDecoder.dequeueOutputBuffer(info, 1000);
                if(decoderStatus < 0)
                {
                    VideoThingieStatus =false;
                    continue;

                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "dequeueOutputBuffer error");
                e.printStackTrace();
                VideoThingieStatus =false;
                break;
            }

            // Log.d(TAG, "end queue output buffer ts:"+info.presentationTimeUs);
            try {
                int indexTodisp = decoderStatus;


                if ((enableAudioSync == false) || (AudioPlayReady == false)) {
                    //Log.d(TAG, "without audio sync ");
                    while ((decoderStatus = mVideoDecoder
                            .dequeueOutputBuffer(info, 0)) >= 0) {
                        mVideoDecoder.releaseOutputBuffer(indexTodisp, false);
                        indexTodisp = decoderStatus;
                        if (outputDone) {
                            break;
                        }
                    }
                    //Log.d(TAG, "releaseOutputBuffer with render: "+ indexTodisp);
                    mVideoDecoder.releaseOutputBuffer(indexTodisp, true);
                } else {
                    //***********start for audio sync

                    //currentTs = lastAudioTs
                    long VideoPlayTime = System.currentTimeMillis();
                    long DeltaPlay = VideoPlayTime - AudioPlayTime;
                    long DeltaTs = info.presentationTimeUs /1000 - lastAudioTs;
                    //
                    long TsDelay = DeltaPlay - DeltaTs;//If tsdelta too small, means  lastVideoTs is too low, then we should skip the render
                    //Log.d(TAG, "with ts Before DeltaTs:" + DeltaTs + ",DeltaPlay:" + DeltaPlay + ",TsDelay:" + TsDelay);
                    if (TsDelay < 20) {
                        mVideoDecoder.releaseOutputBuffer(indexTodisp, true);

                        VideoPlayTime = System.currentTimeMillis();
                        DeltaPlay = VideoPlayTime - AudioPlayTime;
                        DeltaTs = info.presentationTimeUs /1000 - lastAudioTs;
                        TsDelay = DeltaTs - DeltaPlay;
                        //Log.d(TAG, "with ts After Render DeltaTs:" + DeltaTs + ",DeltaPlay:" + DeltaPlay + ",TsDelay:" + TsDelay);

                        if (TsDelay > 0 ) {
                            waitTimes(TsDelay);
                        }
                    } else {
                        mVideoDecoder.releaseOutputBuffer(indexTodisp, false);
                        VideoPlayTime = System.currentTimeMillis();
                        DeltaPlay = VideoPlayTime - AudioPlayTime;
                        DeltaTs = info.presentationTimeUs /1000 - lastAudioTs;
                        TsDelay = DeltaTs - DeltaPlay;
                        // Log.d(TAG, "with ts After Skip DeltaTs:" + DeltaTs + ",DeltaPlay:" + DeltaPlay + ",TsDelay:" + TsDelay);
                    }
                    //**********end for audio sync
                }
            }
            catch (IllegalStateException e) {
                Log.e(TAG, "dequeueOutputBuffer & release error");
                e.printStackTrace();
                VideoThingieStatus =false;
                break;
            }
            VideoThingieStatus =false;
        }

        VideoThingieStatus =false;
        Log.d(TAG, "doVideoDecoderThingie free");
        MirServerStop();//maybe removed
        freeVideoDecoder();
    }

    private void setUiHw(){
        Log.d(TAG,"setUiHw:"+TVRotation);
        mListener.SetUIHw(mFrameWidth, mFrameHeigh,mRotate, TVRotation, this);
    }

    @SuppressLint("NewApi")
    private void setVideoData(ByteBuffer encodedFrame, MediaCodec.BufferInfo info) {
        if(VideoList == null) {
            return;
        }

        if((isOsVersion == true) &&(firstIFrameAdded == true)){
            if(mRotate != mPreRotate)
            {
                Log.d(TAG,"SetUiHW for rotation");
                setUiHw();
                mPreRotate = mRotate;
            }
        }


        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                Log.d(TAG, "Configuring Decoder1:" + info.size);
                Log.d(TAG, "Configuring Decoder2:" + encodedFrame.remaining());

                if (VideoDecoderConfigured == true) {
                    Log.d(TAG, "disable Configuring enable flag");
                    VideoDecoderConfigured = false;
                    while (!outputDone) {
                        if ((VideofeedStatus == false) && (VideoThingieStatus == false))
                            break;

                        Log.d(TAG, "Configuring Decoder status VideofeedStatus:" + VideofeedStatus + ",VideoThingieStatus:" + VideoThingieStatus + ",outputDone:" + outputDone);
                        waitTimes(20);
                    }

                    Log.d(TAG, "disable decoder now");
                    stopVideoDecoder();
                    setUiHw();
                    Log.d(TAG, "decoder stopped for restart");
                    try {
                        mVideoDecoder = MediaCodec.createDecoderByType(mCodecType);
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                Log.d(TAG,"mCodecType is "+mCodecType+",mFrameWidth:"+mFrameWidth+",Height:"+mFrameHeigh);
                MediaFormat format = MediaFormat.createVideoFormat(mCodecType,
                        mFrameWidth, mFrameHeigh);
                byte[] bytes = new byte[encodedFrame.remaining()];
                encodedFrame.get(bytes, 0, bytes.length);
                if (info.size < 7) {
                    Log.d(TAG, "BAD info");
                    return;
                }
                encodedFrame.position(0);
                boolean ppsFound = false;
                int startpos;
                //here we default assume the first is sps(csd-0) and the following is pps
                for (startpos = 3; startpos < (info.size - 5); startpos++) {

                    if ((bytes[startpos] == 0x0)
                            && (bytes[startpos + 1] == 0x0)
                            && (bytes[startpos + 2] == 0x0)
                            && (bytes[startpos + 3] == 0x1)
                            && ((bytes[startpos + 4] & 0x1f) == 0x8)
                    ) {

                        //if encodedFrame.get(i+4) & 0x1f is 8 , find pps
                        Log.d(TAG, "pps found");
                        ppsFound = true;
                        break;
                    }
                }
                Log.d(TAG, "now b12.length " + startpos);
                /*temp remove to save in buffer by Huazhu Sun*/
                if (ppsFound == true) {
                    byte[] b12 = new byte[(bytes.length - startpos)];
                    for (int i = 0; i < b12.length; i++) {
                        b12[i] = bytes[startpos + i];
                    }

                    ByteBuffer sps = ByteBuffer.wrap(bytes, 0, startpos);
                    ByteBuffer pps = ByteBuffer.wrap(b12, 0, info.size - startpos);

                    format.setByteBuffer("csd-0", sps);

                    format.setByteBuffer("csd-1", pps);
                } else {
                    format.setByteBuffer("csd-0", encodedFrame);
                }

                mVideoDecoder.configure(format, mSurface, null, 0);
                mVideoDecoder.start();
//                  Log.d(TAG, String.format("color format:" + mVideoDecoder.format);
                VideoDecoderConfigured = true;
                Log.d(TAG, "video decoder configured (" + info.size + " bytes)");

                return;

        }
        //Log.d(TAG, "encVideoBuffer add start");
        FrameInfo videoFrame = new FrameInfo(encodedFrame,  info.flags, info.presentationTimeUs);
        //Log.d(TAG, "encodedFrame pos:"+encodedFrame.position()+",limit:"+encodedFrame.limit());
        VideoList.add(videoFrame);

        //Log.d(TAG, "encVideoBuffer add end");
        if ((info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0) {

            firstIFrameAdded = true;
            Log.d(TAG, "First I-Frame added");
        }
    }

    private void setAudioData(ByteBuffer encodedFrame, long audiots) {
        if(AudioList == null)
            return;

        FrameInfo audioFrame = new FrameInfo(encodedFrame, 0, audiots);
        AudioList.add(audioFrame);

        AudioDataReady =true;
    }

    private void sendDog() {
        waitTimes(1000);
        while (!outputDone) {
            if(mControlSocket.isOpen() == false){
                Log.d(TAG, "Client close ====>" + this);
                MirServerStop();
                break;
            }
            waitTimes(1000);//wait 1.5 second
            IncreaseWatchDog();
            sendDog(comsumed_ts);
            if(GetWatchDogStatus() > 30){
                Log.d(TAG, "Disconnect Not Data");
                MirServerStop();
                break;
            }
        }
    }

    public void  MirServerStop() {
        //Send Bye to Server
        mcloselock.lock();
        if (msetclose == 0){
            Log.d(TAG, "close:" + this);
            mListener.setStaus("close", this);
            msetclose = 1;
        }else{
            mcloselock.unlock();
            return;
        }
        mcloselock.unlock();
        outputDone = true;
        //stopBt();
        if(mVideoDataSocket!=null) {
            Log.d(TAG, "MirServerStop:mVideoDataSocket:" + mVideoDataSocket);
            //mVideoDataSocket.send("bye");
            mVideoDataSocket.close();
            mVideoDataSocket = null;
        }
        if(mAudioSocket!=null) {
            Log.d(TAG, "MirServerStop:AudioSocket:" + mAudioSocket);
            //mVideoDataSocket.send("bye");
            mAudioSocket.close();
            mAudioSocket = null;
        }

        if(mControlSocket!=null) {
            Log.d(TAG, "MirServerStop:mControlSocket:" + mControlSocket);
            //mVideoDataSocket.send("bye");
            mControlSocket.close();
            mControlSocket = null;
        }
    }
}

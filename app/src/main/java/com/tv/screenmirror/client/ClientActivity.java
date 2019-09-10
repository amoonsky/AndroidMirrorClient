package com.tv.screenmirror.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.grafika.CircularEncoderBuffer;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;

@SuppressLint("NewApi")
//public class ClientActivity extends Activity implements SurfaceHolder.Callback, View.OnTouchListener{
public class ClientActivity extends Activity implements TextureView.SurfaceTextureListener, View.OnTouchListener{
    //SurfaceView surfaceView;
    TextureView mTextureView;
    Surface mSurface=null;
    MediaCodec decoder;
    boolean decoderConfigured = false;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    //CircularEncoderBuffer encBuffer = new CircularEncoderBuffer((int)(1024 * 1024 * 2), 60, 7);

    private WebSocket webSocketTouch = null;

    String address;

    int deviceWidth;
    int deviceHeight;
    Point videoResolution = new Point();

    int i = -1;
    String[] infoStringParts;
    boolean outputDone = false;
    private boolean firstIFrameAdded;
    boolean mRotate = false;

//new added
    private static String TAG = "ClientActivity";
    public static String mRemoteIp;

    private Handler mHandler;
    private Context mContext;

    private AsyncHttpServer mControlServer = null;
    private int mControlPort = 21100;
    public static WebSocket mControlSocket = null;

    private AsyncHttpServer mDataServer = null;
    private int mDataPort    = 21099;
    public static WebSocket mDataSocket = null;


    private AsyncHttpServer mAudioServer = null;
    private int mAudioPort    = 21096;
    public static WebSocket mAudioSocket = null;

    private boolean isFriendReady = false;

    static public WebSocket getDataWebSocket(){
        return mDataSocket;
    }

    static public WebSocket getAudioWebSocket(){
        return mAudioSocket;
    }

    static public WebSocket getControlWebSocket(){
        return mControlSocket;
    }

    private Boolean singleCastSupport=true;
    static public String getRemoteIp(){
        return mRemoteIp;
    }
    final private String MirServerVersion="3.0";
    public static int CODEC_AVC_FLAG  =1;
    public static int CODEC_HEVC_FLAG =1<< 1;
    private static int mEcoderCodecType = CODEC_AVC_FLAG;
    static public int getEncodeCodecType(){
        return mEcoderCodecType;
    }

    int mViewWidth = 1080;
    int mViewHeight = 1080;

    private int CodecSupportType = 0;

    private void CheckDecoderSupportCodec() {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            // 判断是否为编码器，是则直接进入下一次循环
            if (codecInfo.isEncoder()) {
                continue;
            }
            // 如果是解码器，判断是否支持Mime类型
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    CodecSupportType|=CODEC_AVC_FLAG;
                    Log.d(TAG,"AVC Supported");
                    continue;
                }

                if (types[j].equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    CodecSupportType|=CODEC_HEVC_FLAG;
                    Log.d(TAG,"HEVC Supported");
                    continue;
                }
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        deviceWidth = dm.widthPixels;
        deviceHeight = dm.heightPixels;
        Log.d(TAG, "device wxh:"+deviceWidth+","+deviceHeight);
//        if (ConstantS.ISMAXScreenRatio) {//全面屏
//            mHeight = deviceHeight + ViewUtil.getBottomStatusHeightNew(mContext);
//        }

        address = getIntent().getStringExtra(AddressInputDialog.KEY_ADDRESS_EXTRA);
        Log.d(TAG, "ip is "+address);
        hideSystemUI();
        setContentView(R.layout.activity_client);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
       // surfaceView = (SurfaceView) findViewById(R.id.main_surface_view);
        //surfaceView.getHolder().addCallback(this);
        //surfaceView.setOnTouchListener(this);
        mTextureView = (TextureView) findViewById(R.id.main_texture_view);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setOnTouchListener(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        outputDone = false;
        CheckDecoderSupportCodec();
        initDecoderListener();

    }


    //  end by Huazhu Sun for cmd channel
    private AsyncHttpServer setSocket(AsyncHttpServer server, AsyncHttpServer.WebSocketRequestCallback  normalCallback,CompletedCallback errCallback, int port){
        if(server == null){
            server = new AsyncHttpServer();
            server.websocket("/", null, normalCallback);
            server.setErrorCallback(errCallback);
            server.listen(port);
            return server;
        }
        return server;
    }
    //public int onStartCommand(Intent intent, int flags, int startId) {
    public boolean onStartSocket() {
        if(mControlServer == null) {
            mControlServer = new AsyncHttpServer();
            mControlServer.websocket("/", null, ControlsocketCallback);
            mControlServer.setErrorCallback(mControlCallback);
            mControlServer.listen(mControlPort);
            Log.d(TAG, "Control Server Listen");
        }

        if(mDataServer == null) {
            //create Data socket
            mDataServer = new AsyncHttpServer();
            mDataServer.websocket("/", null, DatasocketCallback);
            mDataServer.setErrorCallback(mDataCallback);
            mDataServer.listen(mDataPort);
            Log.d(TAG, "Data Server Listen");
        }

        if(mAudioServer == null) {
            //create Audio socket
            mAudioServer = new AsyncHttpServer();
            mAudioServer.websocket("/", null, AudiosocketCallback);
            mAudioServer.setErrorCallback(mAudioCallback);
            mAudioServer.listen(mAudioPort);
            Log.d(TAG, "Audio Server Listen");

        }
        return true;
    }

    private CompletedCallback mControlCallback = new CompletedCallback(){
        public void onCompleted(Exception ex){
            //Log.d(TAG, "MirControlServer Error:"+ex);
            if (mControlServer != null){
                mControlServer.stop();
                mControlServer = null;
            }

            if(ex != null) {
                Log.d(TAG, "Data Server Listen err " +ex);
                return;
            }

            if(mControlServer == null) {
                mControlServer = new AsyncHttpServer();
                mControlServer.websocket("/", null, ControlsocketCallback);
                mControlServer.setErrorCallback(mControlCallback);

                mControlServer.listen(mControlPort);
                Log.d(TAG, "Control Server retry Listen");
            }
        };
    };

    private CompletedCallback mDataCallback = new CompletedCallback(){
        public void onCompleted(Exception ex){
            //Log.d(TAG, "MirDataServer Error:"+ex);

            if (mDataServer != null) {
                mDataServer.stop();
                mDataServer = null;
            }

            if(ex != null) {
                Log.d(TAG, "Data Server Listen err " +ex);
                return;
            }

            if(mDataServer == null) {
                mDataServer = new AsyncHttpServer();
                mDataServer.websocket("/", null, DatasocketCallback);
                mDataServer.setErrorCallback(mDataCallback);
                mDataServer.listen(mDataPort);
                Log.d(TAG, "Data Server retry Listen");
            }
        };
    };

    private CompletedCallback mAudioCallback = new CompletedCallback(){
        public void onCompleted(Exception ex){
            //Log.d(TAG, "MirAudioServer Error:"+ex);

            if (mAudioServer != null) {
                mAudioServer.stop();
                mAudioServer = null;
            }

            if(ex != null) {
                Log.d(TAG, "Audio Server Listen err " +ex);
                return;
            }
            if(mAudioServer == null) {
                mAudioServer = new AsyncHttpServer();
                mAudioServer.websocket("/", null, AudiosocketCallback);
                mAudioServer.setErrorCallback(mAudioCallback);
                mAudioServer.listen(mAudioPort);
                Log.d(TAG, "Audio Server retry Listen");
            }
        };
    };

    private PlayerDecoder.DecoderListener mDecListener = null;
    void initDecoderListener(){
        mDecListener = new PlayerDecoder.DecoderListener() {

            @Override
            public void setStaus(String Status, PlayerDecoder decoder) {
            }

            @Override
            public void SetUIHw(int w, int h, int angle,int TVRotation,PlayerDecoder decoder) {
                Log.d("SHZ:setUIHW ", "w "+mViewWidth+" h "+ mViewHeight+ " angle " + angle+" TVRotation "+ TVRotation);

                if(TVRotation == 0) {//normally same with start OK,1080*608
                  mTextureView.setRotation(90);
                  mRotate = true;
                  mTextureView.setScaleX((float)mViewHeight/(float)mViewWidth);//1080/1920            1920 resize to 1080 right
                  mTextureView.setScaleY((float)mViewWidth/(float)mViewHeight);//1920/886            886 rize to 886?
                }
                else{
                    mRotate = false;
                    mTextureView.setRotation(0);
                    mTextureView.setScaleX(1);//1080/1920            1920 resize to 1080 right
                    mTextureView.setScaleY(1);//1920/886            886 rize to 886?
                }
            }
        };
    }


    private void onSendBdMsg(String msg){
        //start Decoder
        PlayerDecoder priPlayer = new PlayerDecoder(
                this.getDataWebSocket(),
                this.getAudioWebSocket(),
                this.getControlWebSocket(),
                this.getEncodeCodecType(),
                mDecListener) ;

        //SurfaceTexture surface;
        priPlayer.setSurface(mSurface);

    }


    private void SetControlSocket(final WebSocket webSocket)
    {
        if((mControlSocket != null) && (singleCastSupport == true)){
            Log.d(TAG, "close opened control socket:"+mControlSocket);
            mControlSocket.close();
        }
        mControlSocket = webSocket;
    }

    private void SetDataSocket(final WebSocket webSocket)
    {
        if((mDataSocket != null) && (singleCastSupport == true)){
            Log.d(TAG, "close opened data socket:"+mDataSocket);
            mDataSocket.close();
        }

        mDataSocket = webSocket;
    }

    private void SetAudioSocket(final WebSocket webSocket)
    {
        if((mAudioSocket != null) && (singleCastSupport == true)){
            Log.d(TAG, "close opened audio socket:"+mAudioSocket);
            mAudioSocket.close();
        }

        mAudioSocket = webSocket;
    }

    private AsyncHttpServer.WebSocketRequestCallback ControlsocketCallback = new AsyncHttpServer.WebSocketRequestCallback() {

        @Override
        public void onConnected(final WebSocket webSocket, AsyncHttpServerRequest request) {
            SetControlSocket(webSocket);
            Log.d(TAG, "New control client: " + webSocket);
//            Log.d(TAG, "Playdecoder status:" + PlayerDecoder.getDecoderStatus());
            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception e) {

                    try {
                        if (e != null)
                        {
                            Log.d(TAG, "Control Closed error");
                            e.printStackTrace();
                        }
                    } finally {
                        Log.d(TAG, "Control Closed onCompleted:" + webSocket);
                        isFriendReady= false;
                    }
                }
            });


            webSocket.setStringCallback(new WebSocket.StringCallback() {
                public void onStringAvailable(String s) {

                    Log.d(TAG, "now Recv control String msg:" + s);
                    if (s.startsWith("CheckVersion")) {
                        String ClientVersion = s.split(":")[1]; //need be back
                        Log.d(TAG, "ClientVersion:" + ClientVersion); //need be back
                        webSocket.send("ServerVersion:"+MirServerVersion+":"+CodecSupportType);
                    }
                    if (s.startsWith("START")) {
                        mRemoteIp = s.split(":")[1];
                        mEcoderCodecType =Integer.parseInt(s.split(":")[2]); //need be back
                        Log.d(TAG, "mEcoderCodecType:" + mEcoderCodecType);
                        if(isFriendReady)
                        {
                            onSendBdMsg(s);
                            isFriendReady= false;
                        }
                        else
                            isFriendReady = true;
                    }else if (s.equals("bye")){
                        Log.d(TAG, "Recv control bye msg, close socket");
                        webSocket.close();
                    }
                }
            });
        }
    };

    private AsyncHttpServer.WebSocketRequestCallback DatasocketCallback = new AsyncHttpServer.WebSocketRequestCallback() {

        @Override
        public void onConnected(final WebSocket webSocket, AsyncHttpServerRequest request) {
            SetDataSocket(webSocket);
            Log.d(TAG, "New Video data client: " + webSocket);
            if(isFriendReady)
            {
                onSendBdMsg("START:"+mRemoteIp);
                isFriendReady= false;
            }
            else
                isFriendReady = true;

            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception e) {
                    try {
                        if (e != null)
                        {
                            Log.d(TAG, "Data Closed error");
                            e.printStackTrace();
                        }
                    } finally {
                        Log.d(TAG, "Data Closed onCompleted:" + webSocket);
                        isFriendReady= false;
                    }
                };
            });

            webSocket.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter dataEmitter, ByteBufferList byteBufferList) {

                    byteBufferList.recycle();
                }
            });

            webSocket.setStringCallback(new WebSocket.StringCallback() {
                public void onStringAvailable(String s) {
                    Log.d(TAG, "Recv data String msg:" + s);
                }
            });
        }
    };

    private AsyncHttpServer.WebSocketRequestCallback AudiosocketCallback = new AsyncHttpServer.WebSocketRequestCallback() {

        @Override
        public void onConnected(final WebSocket webSocket, AsyncHttpServerRequest request) {
            SetAudioSocket(webSocket);
            Log.d(TAG, "New Audio data client: " + webSocket);

            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception e) {
                    try {
                        if (e != null)
                        {
                            Log.d(TAG, "Auido Closed error");
                            e.printStackTrace();
                        }
                    } finally {
                        Log.d(TAG, "Auido Closed onCompleted:" + webSocket);
                    }
                };
            });

            webSocket.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter dataEmitter, ByteBufferList byteBufferList) {
                    byteBufferList.recycle();
                }
            });

            webSocket.setStringCallback(new WebSocket.StringCallback() {
                public void onStringAvailable(String s) {
                    Log.d(TAG, "Recv Auido String msg:" + s);
                }
            });
        }
    };

    public void onSocketDestroy() {
        if(webSocketTouch != null){
            webSocketTouch.close();
            webSocketTouch= null;
        }
        if(mControlSocket != null){
            mControlSocket.close();
            mControlSocket= null;
        }

        if(mAudioSocket != null){
            mAudioSocket.close();
            mAudioSocket= null;
        }

        if(mDataSocket != null){
            mDataSocket.close();
            mDataSocket= null;
        }

        Log.d(TAG, "onDestroy");
        if(mControlServer != null){
            mControlServer.stop();
            mControlServer = null;
        }

        if(mDataServer != null){
            mDataServer.stop();
            mDataServer = null;
        }

        if(mAudioServer != null){
            mAudioServer.stop();
            mAudioServer = null;
        }
    }

    private String intToIp(int i) {
         return (i & 0xFF) + "." +
                         ((i >> 8) & 0xFF) + "." +
                         ((i >> 16) & 0xFF) + "." +
                         (i >> 24 & 0xFF);
     }

    private AsyncHttpClient.WebSocketConnectCallback websocketTouchCallback = new AsyncHttpClient
            .WebSocketConnectCallback() {
        @Override
        public void onCompleted(final Exception ex, WebSocket webSocket, Uri uri) {
            //connect to TV
            if (ex != null) {
                ex.printStackTrace();
                return;
            }

            ClientActivity.this.webSocketTouch = webSocket;
            Log.d(TAG, "Touch Connection OK:"+address);
            //send local IP to TV

            WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            //判断wifi是否开启
            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
            }
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            Log.d(TAG, "getConnectionInfo ");
            int ipAddress = wifiInfo.getIpAddress();
            String ip = intToIp(ipAddress);
            Log.d(TAG, "send ip :"+ip);
            webSocket.send("IP:"+ip);

            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception e) {
                    ClientActivity.this.webSocketTouch = null;
                    Log.d(TAG, "Disconnected");
                }
            });
        }
    };

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture arg0, int width, int height) {
        mSurface = new Surface(arg0);
        mViewWidth = width;
        mViewHeight = height;
        AsyncHttpClient.getDefaultInstance().websocket("ws://" + address + ":6000", null, websocketTouchCallback);//for touc event
        onStartSocket();
    }
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture arg0) {
        return false;
    }
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture arg0, int width,int height) {
        mViewWidth = width;
        mViewHeight = height;
    }
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture arg0) {
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        Log.d(TAG, "ONTOUCH");
        if (webSocketTouch != null) {
        //    webSocket.send(motionEvent.getX() / deviceWidth + "," + motionEvent.getY() / deviceHeight);
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        Log.d(TAG, "onTouchEvent");
        Parcel motionEventParcel = Parcel.obtain();
//        Log.d(TAG,"original:"+motionEvent.toString());
//        if (webSocketTouch != null) {
//            motionEventParcel.setDataPosition(0);
//            motionEvent.writeToParcel(motionEventParcel, 0);
//            webSocketTouch.send(motionEventParcel.marshall());//bsp
//        }
        String jsonTxt = "{";
        jsonTxt+="\"action\":";
        jsonTxt+= motionEvent.getAction();
        jsonTxt+=",";

        double xpos= motionEvent.getX();
        double ypos= motionEvent.getY();
        jsonTxt+="\"touch\":[";
        int cnt = motionEvent.getPointerCount();
        Log.d(TAG,"original "+motionEvent.toString());
        double Xrate = ((float)deviceWidth)/1080.0;
        double Yrate = ((float)deviceHeight)/1920.0;
        MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[cnt];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[cnt];

        for(int index=0;index<cnt;index++){
            MotionEvent.PointerCoords pointerCoord = new MotionEvent.PointerCoords();
            pointerCoord.pressure = 1;

            if(mRotate){//if rotate, mean TV lanscape mode
                xpos =(motionEvent.getY(index))/Yrate;
                ypos = ( deviceWidth - motionEvent.getX(index))/Xrate;
            }else{
                xpos= (motionEvent.getX(index))/Xrate;
                ypos= (motionEvent.getY(index))/Yrate;
            }
            jsonTxt+="{";
            jsonTxt+="\"x\":"+(int)xpos+",";
            jsonTxt+="\"y\":"+(int)ypos+",";
            jsonTxt+="\"id\":"+motionEvent.getPointerId(index)+"";
            pointerCoord.x = (float)xpos;//x坐标
            pointerCoord.y = (float)ypos;//yx坐标
            pointerCoords[index] = pointerCoord;
            jsonTxt+="}";
            if(index < (cnt-1))
            jsonTxt+=",";
            MotionEvent.PointerProperties pointerPropertie = new MotionEvent.PointerProperties();
            pointerPropertie.id = motionEvent.getPointerId(index);
            pointerPropertie.toolType = motionEvent.getToolType(index);
            pointerProperties[index] = pointerPropertie;
        }
        jsonTxt+="]";
        jsonTxt+="}";
        Log.d(TAG, "JSON TXT:"+jsonTxt);
        long when = SystemClock.uptimeMillis();
        int actionPoint = motionEvent.getAction();
        MotionEvent newMotionEvent = MotionEvent.obtain(when, when, actionPoint, cnt, pointerProperties, pointerCoords, 0, 0, 1, 1, 0, 0, 0, 0);

        motionEventParcel.setDataPosition(0);
        newMotionEvent.writeToParcel(motionEventParcel, 0);
        motionEventParcel.setDataPosition(0);

        Log.d(TAG,"actionPoint :"+actionPoint);
        Log.d(TAG,newMotionEvent.toString());
        Log.d(TAG,"Parcel:"+motionEventParcel.toString());
//
//        if (webSocketTouch != null) {
//            webSocketTouch.send(motionEventParcel.marshall());//bsp
//        }
        if (webSocketTouch != null) {
            webSocketTouch.send(jsonTxt);//bsp
        }
        motionEventParcel.recycle();
        return false;
    }



    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    //Add Bsp
    @Override
    protected void onStart() {
        super.onStart();
        //Log.d(TAG, "Bsp Debug onStart");
    }
    @Override
    protected void onResume() {
        super.onResume();
        //Log.d(TAG, "Bsp Debug onResume");
    }
    @Override
    protected void onPause() {
        super.onPause();
        //Log.d(TAG, "Bsp Debug onPause");
    }
    @Override
    protected void onStop() {
        super.onStop();
        //Log.d(TAG, "Bsp Debug onStop");
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        onSocketDestroy();

    }
    //End Add Bsp
}

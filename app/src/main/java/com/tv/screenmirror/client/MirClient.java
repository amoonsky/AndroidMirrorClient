package com.tv.screenmirror.client;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;

import android.view.Surface;
import android.view.WindowManager;

import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.WebSocket;
import  com.tv.screenmirror.client.MainActivity;
import  com.tv.screenmirror.client.glec.EGLRender;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

//bsp
//
//import org.omg.IOP.TAG_ALTERNATE_IIOP_ADDRESS;

public class MirClient extends Service {

	private MediaCodec encoder = null;
	Thread encoderThread = null;
	Thread socketThread = null;
	private String TAG = "MirClient";

	Handler mHandler;
	SharedPreferences preferences;
	int deviceWidth;
	int deviceHeight;
	Point resolution = new Point();
	AlertWindow alertWindow = null;
	private VirtualDisplay virtualDisplay = null;
	private boolean bEncoderStart = false;
	private String encInfoHead = null;
	byte[] encInfoData = null;
	private WebSocket mWebControlSocket=null;
	//private WebSocket mWebSocket= null;//old
	private boolean mStopFlag = true;
	private boolean ExitCurrentDecoder = false;
	private int mWatchDog = 0;
	private int mLastRotion = Surface.ROTATION_0%2;
	public static boolean mRunStatus = false;

	private final int    MAX_VIDEO_FPS = 60;
	//private final int    MAX_VIDEO_FPS = 30;

    public int mScreenDisplayWidth = 1920;
    public int mScreenDisplayHeight = 1080;

    public int mWidth = 1920;
    public int mHeight = 1080;
//	private int defaultBitrate =(int)(2 *1024 *1024);
//	private int MinBitrateThreshold =(int)(1.2 * 1024 * 1024);
//	private int MaxBitrateThreshold =(int)(2 *1024 *1024);
//	private int mBitrate =  (int)(2 *1024 *1024);
	boolean EGLRenderSupport = false;

//	private int defaultBitrate =(int)(4 *1024 *1024);
//	private int MinBitrateThreshold =(int)(2 * 1024 * 1024);
//	private int MaxBitrateThreshold =(int)(4 *1024 *1024);
//	private int mBitrate =  (int)(4 *1024 *1024);

	private int defaultBitrate =(int)(3 *1024 *1024);
	private int MinBitrateThreshold =(int)(1.5 * 1024 * 1024);
	private int MaxBitrateThreshold =(int)(3 *1024 *1024);
	private int mBitrate =  (int)(3 *1024 *1024);

    private int    video_fps = MAX_VIDEO_FPS;
	private final int TIMEOUT_US = 10000;

	private int mAlphaValue = 0;
	private long latency_threadhold = (long)800000;//800ms
	private long comsumed_ts = 0;
	private boolean fetch_new_comsumed_pts = false;
	private long fetch_ticket = 0;

	public final static long mControlPort = 21100;
	public final static long mDataPort     = 21099;
	public final static long mDataSocketPort = 21200;
	public static int CODEC_AVC_FLAG  =1;
	public static int CODEC_HEVC_FLAG =1<< 1;

	private boolean mRotation = false;
	private boolean enableClose= false;
	public static String CurrerntServerIp=null;
	private SurfaceTexture mSurfaceTexture;
	private Surface mSurface = null;
	private EGLRender eglRender=null;
	private String mime_type = MediaFormat.MIMETYPE_VIDEO_AVC;

	private byte[] sps=null;
	private byte[] pps=null;
	private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
	private OnScreenCallBack onScreenCallBack;

    private File mfile = null;

    // 创建BufferedOutputStream对象
    private long mframeindex = 0;
    private long frameSkipBase = 1;
    private int mCodecSupport = CODEC_AVC_FLAG;
	private int mEncoderCodecType = CODEC_AVC_FLAG;
    public int EncoderCodecSupportType = CODEC_AVC_FLAG;

	private long last_ts = 0;
    private String MirClientVersion = "3.0";
	private LinkedBlockingQueue<byte[]> VideoList = null;
	boolean isAudioPlaying = false;
	public void setOnScreenCallBack(OnScreenCallBack onScreenCallBack) {
		this.onScreenCallBack = onScreenCallBack;
	}

	public interface OnScreenCallBack {
		void onScreenInfo(byte[] bytes);
		void onCutScreen(Bitmap bitmap);
	}

	private void onSendBdMsg(String msg) {
		Intent broadcast = new Intent("com.tv.screenmirror.BROADCAST");
		broadcast.putExtra("Action", msg);
		sendBroadcast(broadcast);
	}

	public void sendMsg(String msg) {
		if (mWebControlSocket != null) {
			//Log.d(TAG, "SEND msg:"+msg);
            try {
                mWebControlSocket.send(msg);
            }
            catch (IllegalStateException e) {
                e.printStackTrace();
            }
		}
	}

	public void stopClient() {

		Log.d(TAG, "stopClient "+enableClose);
	//	if(mSuccessSocket > 0)
		if(enableClose == false)
			return;

		Log.d(TAG, "start sendMsg bye");
		sendMsg("bye");
		if (eglRender != null) {
			eglRender.stop();
			eglRender = null;
		}
		ExitCurrentDecoder = true;

		if (mWebControlSocket != null && mWebControlSocket.isOpen()) {
			Log.d(TAG, "=====> Close mWebControlSocket");
			mWebControlSocket.close();
			mWebControlSocket=null;
		}

		if (mVideoDataClient != null && mVideoDataClient.isOpen()) {
			Log.d(TAG, "=====> Close mVideoDataClient");
			mVideoDataClient.close();
			mVideoDataClient=null;
		}

		Log.d(TAG, "onSendBdMsg");
		onSendBdMsg("STOP");
		mySleep(500);
		if (encoder != null) {
			encoder.signalEndOfInputStream();
		}
		stopForeground(true);
		stopSelf();
	}

	private String getLocalIp() {
		WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		// 判断wifi是否开启
		if (!wifiManager.isWifiEnabled()) {
			wifiManager.setWifiEnabled(true);
		}
		WifiInfo wifiInfo = wifiManager.getConnectionInfo();
		int ipAddress = wifiInfo.getIpAddress();
		String ip = intToIp(ipAddress);
		return ip;
	}

	private String intToIp(int i) {

		return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF)
				+ "." + (i >> 24 & 0xFF);
	}

	public void stopEncode() {
		Log.d(TAG, "stopEncode");
		onSendBdMsg("STOP");
        if (eglRender != null) {
            eglRender.stop();
            eglRender=null;
        }
		ExitCurrentDecoder = true;
		mStopFlag = true;
	}

	private void CheckEncoderSupportCodec() {

		int numCodecs = MediaCodecList.getCodecCount();
		for (int i = 0; i < numCodecs; i++) {
			MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
			// 判断是否为编码器，否则直接进入下一次循环
			if (!codecInfo.isEncoder()) {
				continue;
			}
			// 如果是解码器，判断是否支持Mime类型
			String[] types = codecInfo.getSupportedTypes();
			for (int j = 0; j < types.length; j++) {
				if (types[j].equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
					EncoderCodecSupportType|=CODEC_AVC_FLAG;
					Log.d(TAG,"AVC Supported");
					continue;
				}

				if (types[j].equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
					EncoderCodecSupportType|=CODEC_HEVC_FLAG;
					Log.d(TAG,"HEVC Supported");
					continue;
				}
			}
		}

//		EncoderCodecSupportType = CODEC_AVC_FLAG;

		Log.d(TAG,"EncoderCodecSupportType "+ EncoderCodecSupportType);
	}
	/**
	 * Main Entry Point of the server code. Create a WebSocket server and start
	 * the encoder.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG,"onStartCommand");
		if (intent != null && intent.getAction() == "STOP") {

			Log.d(TAG, "onStartCommand: mstopflag:" + mStopFlag);
			onSendBdMsg("STOP");
			if (mStopFlag) {
				enableClose = true;
				Log.d(TAG, "onStartCommand enableClose " + enableClose);
				stopClient();
			}

			stopEncode();
		} else if (intent.getAction().equals("START")) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				startForeground(1024 ,new Notification());
			}

			String ip = intent.getStringExtra("serverip");
			VideoList= new LinkedBlockingQueue<byte[]>();
			CurrerntServerIp = ip;
			encoderThread = null;
			preferences = PreferenceManager.getDefaultSharedPreferences(this);
			DisplayMetrics dm = new DisplayMetrics();
			Display mDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
					.getDefaultDisplay();
			mDisplay.getMetrics(dm);
			deviceWidth = dm.widthPixels;
			deviceHeight = dm.heightPixels;
			Log.d(TAG,"deviceWidth:"+deviceWidth+",deviceHeight:"+deviceHeight);


			mWidth  = mScreenDisplayWidth = 1920;
			//mWidth  = mScreenDisplayWidth = 1280;
			int baseHeight =  (int)(1920.0 * (float)deviceWidth/(float)deviceHeight  );
			//int baseHeight =  (int)(1280.0 * (float)deviceWidth/(float)deviceHeight  );
			int desHeight = baseHeight % 16;
			baseHeight = baseHeight - desHeight;
			mHeight = mScreenDisplayHeight = baseHeight;
			Log.d(TAG,"mScreenDisplayWidth:"+mScreenDisplayWidth+",mScreenDisplayHeight:"+mScreenDisplayHeight);

			float resolutionRatio = Float.parseFloat("0.25");
			mDisplay.getRealSize(resolution);
			resolution.x = (int) (resolution.x * resolutionRatio);
			resolution.y = (int) (resolution.y * resolutionRatio);

			final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
					WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
					WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
							| WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
							| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
							| WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

			params.gravity = Gravity.TOP | Gravity.LEFT;
			params.height = WindowManager.LayoutParams.WRAP_CONTENT;
			params.width = WindowManager.LayoutParams.WRAP_CONTENT;
			WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);


			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
			}else {
				params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
			}
			CheckEncoderSupportCodec();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				while (!Settings.canDrawOverlays(this)) {
					Log.d(TAG, "can draw");

					Intent nintent = new Intent(
							Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
					nintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(nintent);
					break;
				}
			}


			enableClose =false;

			AsyncHttpClient.getDefaultInstance().websocket(
					"ws://" + ip + ":" + mControlPort, null, websocketClientControlCallback);

			mVideoDataClient = new SocketClient(ip, (int)mDataSocketPort);

			onSendBdMsg("STARTED");

			// Log.d(TAG, "SERVER LISTEN");
			mHandler = new Handler();
		}
		return START_NOT_STICKY;
	}
	SocketClient mVideoDataClient = null;

	private AsyncHttpClient.WebSocketConnectCallback websocketClientControlCallback = new AsyncHttpClient.WebSocketConnectCallback() {
		@Override
		public void onCompleted(final Exception ex, final WebSocket webSocket, Uri uri) {

			if (ex != null) {
				Log.d(TAG, "websocketClientControlCallback enableClose " + enableClose);
				stopClient();
				enableClose=true;
				//mSuccessSocket--;
				ex.printStackTrace();
				Log.d(TAG, "new version not support;Control socket error");
				mWebControlSocket = null;
				return;
			}
			mWebControlSocket = webSocket;
			Log.d(TAG, "Control Socket Connected Success");
			/*
			Control socket command flow
			Android : START->
			TV:
            */

            mWebControlSocket.send("CheckVersion:"+MirClientVersion);
			webSocket.setClosedCallback(new CompletedCallback() {
				@Override
				public void onCompleted(Exception e) {
					Log.d(TAG, "=====================Control connect Closed");
					stopEncode();
				}
			});

            webSocket.setStringCallback(new WebSocket.StringCallback() {
				public void onStringAvailable(String s) {
                    mWatchDog = 0;
					//Log.d(TAG, "constrol recv String msg:" + s);
					if (s.equals("SENDTDATA")) {
						if (encoderThread == null) {
							Log.d(TAG, "start Encode....");
							encoderThread = new Thread(new EncoderWorker(),
									"Encoder Thread");
							encoderThread.start();
							socketThread = new Thread(new SocketWorker(),
									"Socket Thread");
							socketThread.start();

						} else {
							Log.d(TAG, "not null....");
						}
					}else if(s.startsWith("ServerVersion:")){
						Log.d(TAG, "ServerVersion:" + s);
                        String CurrentServerVersion = s.split(":")[1];
                        if(CurrentServerVersion.equals("3")){
                            Log.d(TAG, "ServerVersion fitted with "+CurrentServerVersion);
                        }

						String CodecSupport = s.split(":")[2];
                        try {
							mCodecSupport = Integer.parseInt(CodecSupport);
						}catch(Exception e){
                        	e.printStackTrace();
						}

						Log.d(TAG, "mCodecSupport is "+mCodecSupport);
						if( ((mCodecSupport & CODEC_HEVC_FLAG) ==CODEC_HEVC_FLAG ) && ((EncoderCodecSupportType & CODEC_HEVC_FLAG) ==CODEC_HEVC_FLAG )){
							mime_type= MediaFormat.MIMETYPE_VIDEO_HEVC;
							mEncoderCodecType = CODEC_HEVC_FLAG;
						}

						mWebControlSocket.send("START:" + getLocalIp()+":"+mEncoderCodecType);
                        mRunStatus = true;
                    }
                    else if (s.startsWith("DecoderStatus:")){
						// 处理屏幕旋转，通知解码端分辨率，动态设置，如果屏幕旋转，需要重新设置
						mRotation = true;

                        Log.d(TAG,"sethw:"+ String.valueOf(mWidth) + "x" +  String.valueOf(mHeight));
						Log.d(TAG,"mRotation set true");
						setEncoderWH();
						sendMsg("sethw:"+ String.valueOf(mWidth) + "x" +  String.valueOf(mHeight));

					}else{//ts, by Huazhu Sun
						String ts_header= s.substring(0, 3);
						if(ts_header.equals("dog"))
						{
							String ts_end = s.substring(3);
							comsumed_ts = Long.parseLong(ts_end);
//							Log.d(TAG, "fetch comsumed_ts is "+ comsumed_ts);
							fetch_ticket = System.currentTimeMillis();
							fetch_new_comsumed_pts =true;
						}
					}
				}
			});
		}
	};


	private byte[] HeaderByteLen = new byte[4];
	private void sendDataBySocket(byte[] header, byte[] b){
		if(mVideoDataClient != null){
			int dataLen = header.length+b.length;
			byte[] data = new byte[dataLen];
			System.arraycopy(header, 0, data, 0, header.length);
			System.arraycopy(b, 0, data, header.length, b.length);

			if(VideoList != null){
				VideoList.add(data);
			}
		}
	}

	private class SocketWorker implements Runnable {
		@Override
		public void run() {
			while (!mStopFlag) {
				if (VideoList == null) {
					mySleep(1);
					continue;
				}

				byte[] data = VideoList.poll();
				if (data == null) {
					mySleep(1);
					continue;
				}

//			Log.d(TAG, "sendDataBySocket start");
				try {
					byte index = (byte) (mframeindex & 0xff);
					mVideoDataClient.sendData(index, data);
					mframeindex++;
				} catch (IOException e) {
					e.printStackTrace();
					mVideoDataClient.close();
					mVideoDataClient = null;
					Log.d(TAG, "sendDataBySocket close mVideoDataClient");
					break;
				}
//			Log.d(TAG, "sendDataBySocket end");
			}

			VideoList.clear();
		}
	}

	private void sendData(ByteBuffer encodedData, MediaCodec.BufferInfo info){
		if (info.size != 0) {
			boolean bError = false;
			String infoString = info.offset + "," + info.size + ","
					+ info.presentationTimeUs + "," + info.flags+'.';

			byte[] b = new byte[encodedData.remaining()];
			try {
				encodedData.position(info.offset);
				encodedData.limit(info.offset + info.size);
				encodedData.get(b);
			} catch (BufferUnderflowException e) {
				bError = true;
				e.printStackTrace();
				Log.d(TAG, "got a frame error");
			}

			if (!bError) {
				sendDataBySocket(infoString.getBytes(), b);
			}
		}
	}

	private void encodeToVideoTrack(ByteBuffer encodeData) {
		if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
			Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
			//mBufferInfo.size = 0;
		}
		if (mBufferInfo.size == 0) {
//			Log.d(TAG, "info.size == 0, drop it.");
			encodeData = null;
		} else {
//			Log.d(TAG, "got buffer, info: size=" + mBufferInfo.size
//					+ ", presentationTimeUs=" + mBufferInfo.presentationTimeUs
//					+ ", offset=" + mBufferInfo.offset);
		}
		if (encodeData != null) {
			sendData(encodeData, mBufferInfo);
		}
	}

//	private int mVideoTrackIndex;
//	private void getSpsPpsByteBuffer(MediaFormat newFormat) {
//		sps = newFormat.getByteBuffer("csd-0").array();
//		pps = newFormat.getByteBuffer("csd-1").array();
//		//EventBus.getDefault().post(new EventLogBean("编码器初始化完成"));
//	}
//
//	private void resetOutputFormat() {
//		MediaFormat newFormat = encoder.getOutputFormat();
//		Log.i(TAG, "output format changed.\n new format: " + newFormat.toString());
//		getSpsPpsByteBuffer(newFormat);
//		Log.i(TAG, "started media muxer, videoIndex=" + mVideoTrackIndex);
//	}

	private void AdjustBitRate( MediaCodec.BufferInfo info){
		if((comsumed_ts != 0) && (fetch_new_comsumed_pts == true)) {
			fetch_new_comsumed_pts = false;

			if (((info.presentationTimeUs - comsumed_ts) > latency_threadhold)
					&& (mBitrate > MinBitrateThreshold)
					) {

				mBitrate = (int) MinBitrateThreshold;
				Log.d(TAG, "decrease bit rate" + mBitrate);
				Bundle param = new Bundle();
				param.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mBitrate);
				encoder.setParameters(param);
				param = null;
				if(eglRender != null) {
					eglRender.initFPs(20);
					Log.d(TAG, "set fps 20");
				}
                //frameSkipBase = 2;
			}
			else if (((info.presentationTimeUs - comsumed_ts) < (latency_threadhold / 4))
					&& (mBitrate < (int) MaxBitrateThreshold)
					) {

				mBitrate = (int) MaxBitrateThreshold;
				Log.d(TAG, "increase bit rate" + mBitrate);
				Bundle param = new Bundle();
				param.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mBitrate);
				encoder.setParameters(param);
				param = null;
                //frameSkipBase = 1;
				if(eglRender != null) {
					eglRender.initFPs(MAX_VIDEO_FPS);
					Log.d(TAG, "set fps 60");
				}
			}
		}
	}

	private void doEncodeWork() {
		ByteBuffer[] byteBuffers = null;
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			byteBuffers = encoder.getOutputBuffers();
		}

		int index = encoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
		if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
			Log.d(TAG,"MediaCodec INFO_OUTPUT_FORMAT_CHANGED");
		} else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
		} else if (index >= 0) {
			AdjustBitRate(mBufferInfo);
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
				encodeToVideoTrack(byteBuffers[index]);
			} else {
				encodeToVideoTrack(encoder.getOutputBuffer(index));
			}

			encoder.releaseOutputBuffer(index, false);
		}
	}

    public static int getScreenDpi(Context context) {
        return context.getResources().getDisplayMetrics().densityDpi;
    }

	@TargetApi(19)
	private Surface createDisplaySurface() throws IOException {
		int dpi = getScreenDpi(this);
		Log.d(TAG, "system dpi:" + dpi);
		Log.d(TAG, "mime_type :" + mime_type);
		MediaFormat mMediaFormat = MediaFormat.createVideoFormat(mime_type,
				mWidth, mHeight);
		mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE,
				(int) mBitrate);
		mMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, video_fps);
		mMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		mMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 100);
		encoder = MediaCodec.createEncoderByType(mime_type);
		encoder.configure(mMediaFormat, null, null,
				MediaCodec.CONFIGURE_FLAG_ENCODE);
		Surface surface = encoder.createInputSurface();
		if (EGLRenderSupport == false) {
			eglRender = null;
		} else{
			Log.d(TAG, "egl render Width:"+mWidth+",Height:"+mHeight);
			eglRender = new EGLRender(surface, mWidth, mHeight, video_fps);
			eglRender.setCallBack(new EGLRender.onFrameCallBack() {
				@Override
				public void onUpdate() {
					doEncodeWork();
				}

				@Override
				public void onCutScreen(Bitmap bitmap) {//screenshot
					Log.d(TAG, "onCutScreen en");
					onScreenCallBack.onCutScreen(bitmap);
				}
			});
		}

		return surface;
	}
	public Surface encoderInputSurface = null;
	@TargetApi(19)
	public void startDisplayManager() {
		DisplayManager mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

		try {
			encoderInputSurface = createDisplaySurface();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Surface inputSurface = null;
		if (EGLRenderSupport == true) {
			inputSurface = eglRender.getDecodeSurface();
		}else{
			inputSurface = encoderInputSurface;
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			virtualDisplay = mDisplayManager.createVirtualDisplay(
					"TV Screen Mirror", mWidth, mHeight,
                    50,
					inputSurface,
					DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
						| DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE);
		} else {
			if (MainActivity.mMediaProjection != null) {
				virtualDisplay = MainActivity.mMediaProjection
					.createVirtualDisplay(
						"TV Screen Mirror",
						mWidth,
						mHeight,
						50,
						DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
						inputSurface,
						null, null);// bsp
			} else {
				Log.d(TAG,"MediaProjection initialized error");
			}
		}

		encoder.start();

		if (EGLRenderSupport == true) {
			Log.d(TAG,"eglRender start");
			eglRender.start();
		}

	}

	private void mySleep(long times_ms) {
		try {
			Thread.sleep(times_ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private boolean checkScreenRotation(){
		int rotation = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
		int status = rotation%2;

		if(status != mLastRotion){
			//检测到屏幕旋转
			if (status==0){
                mWidth = mScreenDisplayHeight;
                mHeight = mScreenDisplayWidth;
			}
            else
            {
                mWidth = mScreenDisplayWidth;
                mHeight = mScreenDisplayHeight;
            }

//			Log.d(TAG, "rotationsethw:"+ String.valueOf(mWidth) + "x" +  String.valueOf(mHeight));
			sendMsg("rotationsethw:"+ String.valueOf(mWidth) + "x" +  String.valueOf(mHeight));
			mLastRotion = status;
			return true;
		}
		return false;
	}

	private class WatchDogThread implements Runnable {
		@Override
		public void run() {
			int auidoStatus = 0;
			boolean AudioActive = false;
			int num = 0;

			AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);

			if( audioManager.isMusicActive()){
				Log.d(TAG, "enableSync");
			}
			else{
				Log.d(TAG, "disableSync");
			}

			while (!mStopFlag) {
				mySleep(100);
				num++;
				if (num%10 == 0) {
//					Log.d(TAG,"Send Control Msg live");
					sendMsg("live");
					mWatchDog += 1;
				}

				if(mWatchDog > 30){
                    Log.d(TAG,"fetch watchdog timeout");
					stopEncode();
					break;
				}

				if(AudioActive !=  audioManager.isMusicActive()){
					Log.d(TAG, "audioManager Sync"+audioManager.isMusicActive());
					if( !AudioActive){
						AudioActive =true;
						auidoStatus = 1;
						sendMsg("enableSync");
						Log.d(TAG, "enableSync");
					}
					else{
						AudioActive =false;
						auidoStatus = 0;
						sendMsg("disableSync");
						Log.d(TAG, "disableSync");
					}
				}

			}
		}
	}

	private class RotationCheckThread implements Runnable {
		@Override
		public void run() {
			boolean rotateStatus =false;
			Log.d(TAG, "RotationCheckThread:mStopFlag:"+mStopFlag+",mRotation:"+mRotation);
			while (!mStopFlag) {
				if(mRotation) {
					//Log.d(TAG, "start checkScreenRotation");
					rotateStatus = checkScreenRotation();
					if (rotateStatus == true) {

						Log.d(TAG, "do ScreenRotation");
						if(eglRender != null) {
							eglRender.stop();
							eglRender=null;
						}
						ExitCurrentDecoder = true;

					}
				}
				mySleep(10);
			}
		}
	}


	private void setEncoderWH(){
		int rotation = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
		if ( rotation%2 == 0){
            mWidth  = mScreenDisplayHeight;
			mHeight = mScreenDisplayWidth;
		}
	}

	@TargetApi(19)
	private class EncoderWorker implements Runnable {
		@Override
		public void run() {
			Log.d(TAG, " run EncoderWorker123....");
			mStopFlag = false;
			mBitrate = defaultBitrate;
			Log.d(TAG, "EncoderWorker:start WatchDogThread");
			Thread WatchDogThread = new Thread(new WatchDogThread(), "WatchDogThread");
			Thread RotationThread = new Thread(new RotationCheckThread(), "RotationCheckThread");
			Log.d(TAG, "EncoderWorker:end start WatchDogThread");
			WatchDogThread.start();
			RotationThread.start();
			mframeindex = 0;
			while (!mStopFlag) {
				startDisplayManager();//it will be jammed for loop
				if (EGLRenderSupport == false) {
					ExitCurrentDecoder = false;
					while(!ExitCurrentDecoder){
						doEncodeWork();
					}
				}

				Log.d(TAG,"exit startDisplayManager");
				if (encoder != null) {
					mySleep(200);
					encoder.signalEndOfInputStream();
					mySleep(200);
					encoder.stop();
					encoder.release();
					encoder = null;
				}

				if (eglRender != null) {
					eglRender.stop();
					eglRender = null;
				}
				ExitCurrentDecoder = true;

				if (encoderInputSurface != null) {
					encoderInputSurface.release();
					encoderInputSurface = null;
				}

				if (virtualDisplay != null) {
					virtualDisplay.release();
					virtualDisplay = null;
				}
			}

			stopClient();
			Log.d(TAG, "encode exit.................");
		}
	}

	public static boolean getRunstatus() {
		Log.d("EncoderThread", "mRunStatus:" + mRunStatus);
		return mRunStatus;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(TAG, "onDestroy.................");
		stopEncode();
		enableClose = true;
		Log.d(TAG, "onDestroy enableClose " + enableClose);
		stopClient();
		enableClose=false;

		if (encoder != null) {
			Log.d(TAG, "encoder stop.................");
			mySleep(200);
			encoder.signalEndOfInputStream();
			mySleep(200);
			encoder.stop();
			encoder.release();
			encoder = null;
		}

		if (virtualDisplay != null) {
			virtualDisplay.release();
			virtualDisplay = null;
		}

		if(encoderInputSurface != null)
		{
			encoderInputSurface.release();
			encoderInputSurface =null;
		}
		mRunStatus = false;
		CurrerntServerIp = null;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}

package com.tv.screenmirror.client;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.LinearLayout;

@SuppressLint("NewApi")
public class AlertWindow extends LinearLayout implements SurfaceHolder.Callback {

	LayoutInflater mInflater;
	public static SurfaceView View; // bsp
	private int mAlphaUp = 1;

	public AlertWindow(Context context) {
		super(context);
		mInflater = (LayoutInflater) context
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	}

	public AlertWindow(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public AlertWindow(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		new Thread(new Runnable() {
			@Override
			public void run() {
			}
		}).start();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
	}

	public void setAlpha() {
		View = (SurfaceView) findViewById(R.id.alert_window);
		float alpha = View.getAlpha();
		if (alpha >= 0.98) 
			mAlphaUp = -1;
		if (alpha <= 0.02)
			mAlphaUp = 1;
		alpha += 0.05 * mAlphaUp;
		View.setAlpha(alpha);
		//Log.d("SETALPHA", "setAlpha...");
	}
}

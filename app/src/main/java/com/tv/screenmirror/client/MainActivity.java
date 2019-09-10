package com.tv.screenmirror.client;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.screenmirror.client.AddressInputDialog;

public class MainActivity extends Activity {

    SharedPreferences prefs;
    boolean hasSystemPrivileges = false;
    private static final String KEY_SYSTEM_PRIVILEGE_PREF = "has_system_privilege";
    public static final boolean DEBUG = false;

    public static MediaProjectionManager mMediaProjectionManager;
    public static MediaProjection mMediaProjection;
    private TextView mText;
    private static String TAG = "MainActivity";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        hasSystemPrivileges = prefs.getBoolean(KEY_SYSTEM_PRIVILEGE_PREF, false);
        mText = (TextView)findViewById(R.id.textView);
        mText.setText(" ");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mMediaProjectionManager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
    }


    public void startRecvScreen(View v) {
        AddressInputDialog mydialog = new AddressInputDialog();
        mydialog.setEventType(AddressInputDialog.EventRecvScreen);
        mydialog.show(getFragmentManager(), "Recv Address Dialog");
    }

    public void stopSendScreen(View v) {
        Intent stopServerIntent = new Intent(this, MirClient.class);
        stopServerIntent.setAction("STOP");
        Log.d(TAG,"stopServerIntent ");
        stopService(stopServerIntent);
    }

    public static final int REQUEST_MEDIA_PROJECTION = 1;
    public void startSendScreen(View v) {
        AddressInputDialog mydialog = new AddressInputDialog();
        mydialog.setListener(new AddressInputDialog.DialogListener(){
            @Override
            public void setAddress(String address) {
                Log.d(TAG,"set address "+ address);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startActivityForResult(
                            mMediaProjectionManager.createScreenCaptureIntent(),
                            REQUEST_MEDIA_PROJECTION);
                }
            }
        });

        mydialog.setEventType(AddressInputDialog.EventSendScreen);
        mydialog.show(getFragmentManager(), "Send Address Dialog");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG,"onActivityResult address "+ AddressInputDialog.ServerAddress);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show();
                return;
            }

            mMediaProjection = MainActivity.mMediaProjectionManager.getMediaProjection(
                    resultCode, data);
            Intent startServerIntent = new Intent(this, MirClient.class);
            startServerIntent.setAction("START");
            startServerIntent.putExtra("serverip", AddressInputDialog.ServerAddress);
            Log.d(TAG,"startServerIntent  "+ AddressInputDialog.ServerAddress);
            startService(startServerIntent);
            // startBt();
        }
    }
}

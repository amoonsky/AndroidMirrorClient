package com.tv.screenmirror.client;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

public class AddressInputDialog extends DialogFragment {

    public static final String KEY_ADDRESS_EXTRA = "address";
    public static final String KEY_LAST_ADDRESS_PREF = "last_address";
    public static int EventSendScreen = 0;
    public static int EventRecvScreen = 1;
    public int EventType = EventSendScreen;

    public void setEventType(int type){
        EventType = type;
    }
    public static String ServerAddress = null;
    private static String TAG = "AddressInputDialog";

    public interface DialogListener {
        public void setAddress(String address);
    }
    public DialogListener mListener = null;
    public void setListener(DialogListener tlistener){
        mListener = tlistener;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        // Get the layout inflater
        LayoutInflater inflater = getActivity().getLayoutInflater();

        final SharedPreferences prefs = getActivity().getSharedPreferences("MAIN_PREFS", Context.MODE_PRIVATE);
        String lastAddress = prefs.getString(KEY_LAST_ADDRESS_PREF, "");

        final LinearLayout dialogLayout = (LinearLayout) inflater.inflate(R.layout.dialog_address_input, null);
        final EditText addressInput = (EditText) dialogLayout.findViewById(R.id.address_input);
        addressInput.setText(lastAddress);

        // Inflate and set the layout for the dialog
        // Pass null as the parent view because its going in the dialog layout
        builder.setTitle("服务器地址");
        builder.setView(dialogLayout)
                // Add action buttons
                .setPositiveButton("连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {

                        String address = addressInput.getText().toString();
                        if (!address.equals("")) {

                            if(EventType == EventRecvScreen) {
                                Intent startIntent = new Intent(getActivity(), ClientActivity.class);
                                startIntent.putExtra(KEY_ADDRESS_EXTRA, address);
                                SharedPreferences.Editor editor = prefs.edit();
                                editor.putString(KEY_LAST_ADDRESS_PREF, address);
                                editor.commit();
                                startActivity(startIntent);
                            }
                            else{
                                ServerAddress = address;
                                Log.d(TAG,"address "+ ServerAddress);
                                mListener.setAddress(ServerAddress);
//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//                                    startActivityForResult(
//                                            MainActivity.mMediaProjectionManager.createScreenCaptureIntent(),
//                                            REQUEST_MEDIA_PROJECTION);
//                                }
                            }
                        }
                    }
                });
        return builder.create();
    }

}

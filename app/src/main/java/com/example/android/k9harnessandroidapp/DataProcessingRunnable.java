package com.example.android.k9harnessandroidapp;

import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.UUID;


/**
 * Created by rickflaget on 11/29/17.
 */

public class DataProcessingRunnable implements Runnable {
    private String TAG = "DataProcessRunnable";
    private Handler handler;
    private SQLiteHelper myDB;
    private Context context;
    public static BluetoothAdapter mBluetoothAdapter;
    private static final UUID MY_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static boolean isRunning = false;

    public DataProcessingRunnable(Context ctx){
        context = ctx;
    }

    public void initRunnable(SQLiteHelper a, Handler b) {
        myDB = a;
        handler = b;
    }
    /* Data Proccessing thread runnable run() function.
     * Takes in data from our datamock up, and parses it
     * according to old ECE groups methods, then sends it to our
     * interal SQL database, the notification method, and repeats
     */




    @Override
    public void run() {
        isRunning = true;


        int hr;
        int rr;
        int at;
        int ct;
        double abdominalTemp;
        double ambientTemp;
        double chestTemp;

        Notifications notify = new Notifications(context);
        notify.setNotificationsSettings();


        DataMockup testData = new DataMockup();
        SharedPreferences prefs = context.getSharedPreferences("runnable_thread", context.MODE_PRIVATE);
        boolean keepRunning = prefs.getBoolean("connected", true);
        int counter = 0;
        boolean cont = true;
        while (keepRunning) {
            while(keepRunning && counter < 10) {
                //wait 10 seconds
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                keepRunning = prefs.getBoolean("connected", true);
                ++counter;
            }
            if (!keepRunning) {
                break;
            }
            counter = 0;
            //New data input
            String[] parsedMessage = testData.getDataPoint().split(":");
            parsedMessage[0] = parsedMessage[0].trim();
            parsedMessage[1] = parsedMessage[1].trim();
            parsedMessage[2] = parsedMessage[2].trim();
            parsedMessage[3] = parsedMessage[3].trim();
            parsedMessage[4] = parsedMessage[4].replaceAll("#","");
            parsedMessage[4] = parsedMessage[4].trim();
                    /*
                    boolean hrNumeric = isNumeric(parsedMessage[0]);
                    boolean rrNumeric = isNumeric(parsedMessage[1]);
                    boolean abtNumeric = isNumeric(parsedMessage[2]);
                    boolean ambNumeric = isNumeric(parsedMessage[3]);
                    boolean chestNumeric = isNumeric(parsedMessage[4]);
                    */

            //copied from previous versions
            hr = Integer.parseInt(parsedMessage[0]);
            rr = Integer.parseInt(parsedMessage[1]);
            abdominalTemp = (double) Integer.parseInt(parsedMessage[2]);
            ambientTemp = (double) Integer.parseInt(parsedMessage[3]);
            chestTemp = (double) Integer.parseInt(parsedMessage[4]);

            /*abdominalTemp = convertTemp(abdominalTemp);
            ambientTemp = convertTemp(ambientTemp);
            chestTemp = convertTemp(chestTemp);
            */

            //get average ambient temp so far
            //int sizeAmbient = listAmbient.size();

            Cursor ambientTemps = myDB.getAllAmbientTemps();
            int numTemps = ambientTemps.getCount() + 1; //for current read in val

            double total = ambientTemp;
            while(ambientTemps.moveToNext()){
                total += ambientTemps.getInt(0);
            }
            //TODO: TEST avgAMBIENT VALUES

            double avgAmbient = total/(double)numTemps;
            at = (int) abdominalTemp;
            ct = 100;//calculateCoreTemp(abdominalTemp, ambientTemp, chestTemp, avgAmbient);

            //DO NOT TOUCH ECE
            myDB.addDataTick(hr,rr,ct,(int)ambientTemp,at);
            notify.createAllNotifications(hr,rr,ct,at);
            handler.sendEmptyMessage(0);
           // Log.d(TAG, "STILL RUNNING!");
        }
        isRunning = false;
    }

    public class MyBluetoothService {
        private static final String TAG = "MY_APP_DEBUG_TAG";

        public void manageMyConnectedSocket(BluetoothSocket mmSocket) {
            ConnectedThread myConnectedThread = new ConnectedThread(mmSocket);
            myConnectedThread.run();
            myConnectedThread.cancel();
        }

        private class ConnectedThread extends Thread {
            private final BluetoothSocket mmSocket;
            private final InputStream mmInStream;
            private byte[] mmBuffer; // mmBuffer store for the stream
            private byte[] mmBufferCopy; // holds copy of mmBuffer to protect it from being overwritten while used by the handler

            public ConnectedThread(BluetoothSocket socket) {
                mmSocket = socket;
                InputStream tmpIn = null;

                // Get the input streams; using temp objects because
                // member streams are final.
                try {
                    tmpIn = socket.getInputStream();
                } catch (IOException e) {
                    Log.e(TAG, "Error occurred when creating input stream", e);
                }
                mmInStream = tmpIn;
                Message toastMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Input stream created.");
                toastMsg.sendToTarget();
            }

            public void run() {
                mmBuffer = new byte[2048];
                mmBufferCopy = new byte[2048];
                int numBytes = 0; // bytes returned from read()
                // Keep listening to the InputStream until an exception occurs.
                while (true) {
                    try {
                        // Read from the InputStream.
                        mmBuffer[numBytes] = (byte) mmInStream.read(); // read one byte at a time out of input stream
                        if ((mmBuffer[numBytes] == '#')) { // '#' is considered termination character
                            System.arraycopy(mmBuffer,0,mmBufferCopy,0,2048); // copy buffer to another array to protect data
                            mmBuffer = new byte[2048]; // reset buffer
                            // Send the obtained bytes to the UI activity.
                            Message readMsg = handler.obtainMessage(
                                MessageConstants.MESSAGE_READ, numBytes, -1,
                                mmBufferCopy);
                            readMsg.sendToTarget(); // send data to handler
                            numBytes = 0;
                        } else if (numBytes > 30) {
                            mmBuffer = new byte[2048]; // reset buffer if too much data indicating terminating character not received at proper time
                        } else { // keep reading bytes
                            numBytes++;
                        }
                    } catch (IOException e) {
                        Log.d(TAG, "Input stream was disconnected", e);
                        break;
                    }
                }
            }

            // Call this method from the main activity to shut down the connection.
            public void cancel() {
                try {
                    mmSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Could not close the connect socket", e);
                }
            }


        }
    }

    public interface MessageConstants {
        int MESSAGE_READ = 0;
        int MESSAGE_TOAST = 1;
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            // Use a temporary object that is later assigned to mmSocket
            // because mmSocket is final.
            BluetoothSocket tmp = null;
            mmDevice = device;
            try {
                // Get a BluetoothSocket to connect with the given BluetoothDevice.
                // MY_UUID is the app's UUID string, also used in the server code.
                tmp = mmDevice.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
                Message toastMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Socket creation failed.");
                toastMsg.sendToTarget();
            }
            mmSocket = tmp;
            Message toastMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Socket created!");
            toastMsg.sendToTarget();
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            //mBluetoothAdapter.cancelDiscovery();
            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and return.
                Log.e(TAG, "Connection failed", connectException);
                Message toastMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Unable to connect.");
                toastMsg.sendToTarget();
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                return;
            }

            // The connection attempt succeeded. Perform work associated with
            // the connection in a separate thread.
            Message toastMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Connection successful!");
            toastMsg.sendToTarget();
            MyBluetoothService bluetoothService = new MyBluetoothService();
            bluetoothService.manageMyConnectedSocket(mmSocket);
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }
}


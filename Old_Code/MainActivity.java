package com.example.dell.actualtest;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;  

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MainActivity";
    public static BluetoothAdapter mBluetoothAdapter;
    private static final UUID MY_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_ENABLE_BT = 1;
    public ArrayList listHR = new ArrayList();
    public ArrayList listRR = new ArrayList();
    public ArrayList listCT = new ArrayList();
    public ArrayList listAT = new ArrayList();
    public ArrayList listAmbient = new ArrayList();
    /* ActiveGraph holds an integer that determines which graph is displayed on the screen
     * 0 : Heart rate is displayed
     * 1 : Respiratory rate is displayed
     * 2 : Core temp is displayed
     * 3 : Abdominal temp is displayed
     */
    public int ActiveGraph = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GraphView graph = (GraphView) findViewById(R.id.graph);
        graph.setTitle("Heart Rate (beats/minute X seconds)");
        // activate horizontal zooming and scrolling
        graph.getViewport().setScalable(true);

        new Thread(new Runnable() {

            @Override
            public void run() {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (mBluetoothAdapter == null) {
                    // Device does not support Bluetooth
                    Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Bluetooth is not supported.");
                    toastMsg.sendToTarget();
                    return;
                }

                if (!mBluetoothAdapter.isEnabled()) {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }

                Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();

                BluetoothDevice harness = null;
                if (pairedDevices.size() > 0) {
                    // There are paired devices. Get the name and address of each paired device.
                    for (BluetoothDevice device : pairedDevices) {
                        String deviceHardwareAddress = device.getAddress(); // MAC Address
                        String target = "00:06:66:8C:D3:57";
                        if (deviceHardwareAddress.equals(target)) {
                            harness = device;
                        }
                    }
                }

                if (harness == null) { // device not paired with key fob
                    Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Phone is not paired with key fob device.");
                    toastMsg.sendToTarget();
                    return;
                }


                ConnectThread harnessClient = new ConnectThread(harness);
                harnessClient.run();
                Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Communication over.");
                toastMsg.sendToTarget();
                harnessClient.cancel();
                toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Connection terminated.");
                toastMsg.sendToTarget();
            }
        }).start();
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
                Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Socket creation failed.");
                toastMsg.sendToTarget();
            }
            mmSocket = tmp;
            Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Socket created!");
            toastMsg.sendToTarget();
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            mBluetoothAdapter.cancelDiscovery();
            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and return.
                Log.e(TAG, "Connection failed", connectException);
                Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Unable to connect.");
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
            Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Connection successful!");
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

    // Define several constants used when transmitting messages between the
    // service and the UI.
    public interface MessageConstants {
        int MESSAGE_READ = 0;
        int MESSAGE_TOAST = 1;
    }
    /* This handler parses the data received from the harness.
     * It also handles sending toast notifications.
     */
    public final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MessageConstants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    String[] parsedMessage = readMessage.split(":");
                    if (parsedMessage.length == 5) { // if the received data has correct amountof information
                        int hr;
                        int rr;
                        int at;
                        int ct;
                        double abdominalTemp;
                        double ambientTemp;
                        double chestTemp;
                        // get object references to text fields that display the data
                        TextView heartRate = (TextView) findViewById(R.id.HeartRateValue);
                        TextView respRate = (TextView) findViewById(R.id.RespiratoryRateValue);
                        TextView coreTemp = (TextView) findViewById(R.id.CoreTempValue);
                        TextView abTemp = (TextView) findViewById(R.id.AbTempValue);
                        // remove leading or trailing whitespace
                        parsedMessage[0] = parsedMessage[0].trim();
                        parsedMessage[1] = parsedMessage[1].trim();
                        parsedMessage[2] = parsedMessage[2].trim();
                        parsedMessage[3] = parsedMessage[3].trim();
                        parsedMessage[4] = parsedMessage[4].trim();
                        // check if each separate value is numeric
                        boolean hrNumeric = isNumeric(parsedMessage[0]);
                        boolean rrNumeric = isNumeric(parsedMessage[1]);
                        boolean abtNumeric = isNumeric(parsedMessage[2]);
                        boolean ambNumeric = isNumeric(parsedMessage[3]);
                        boolean chestNumeric = isNumeric(parsedMessage[4]);
                        if (hrNumeric && rrNumeric && abtNumeric && ambNumeric && chestNumeric) { // if every valuable is numeric
                            hr = Integer.parseInt(parsedMessage[0]);
                            if (hr == 0) {
                                heartRate.setText("NA"); // if hr = -1, harness is not ready to provide heart rate values
                                heartRate.setTextColor(Color.BLACK);
                                listHR.add(0); // add 0 so timing is consistent among all data graphs
                            }
                            else {
                                heartRate.setText(parsedMessage[0]); // set heart rate text field
                                listHR.add(hr); // add value to global list
                                // adjust colors of text field for heart rate
                                if (hr <= 120) {
                                    heartRate.setTextColor(Color.GREEN);
                                } else if (hr > 120 && hr <= 160) {
                                    heartRate.setTextColor(0xFFFF3300); // set color to ORANGE
                                } else {
                                    heartRate.setTextColor(Color.RED);
                                }
                            }
                            rr = Integer.parseInt(parsedMessage[1]);
                            if (rr == 0) {
                                respRate.setText("NA"); // if rr = -1, harness is not ready to provide respiratory rate values
                                respRate.setTextColor(Color.BLACK);
                                listRR.add(0); // add 0 so timing is consistent among all data graphs
                            }
                            else {
                                respRate.setText(parsedMessage[1]); // set respiratory rate text field
                                listRR.add(rr); // add value to global list for respiratory rate
                                // adjust colors of text field for respiratory rate
                                if (rr <= 120) {
                                    respRate.setTextColor(Color.GREEN);
                                } else if (rr > 120 && rr <= 160) {
                                    respRate.setTextColor(0xFFFF3300); // set color to ORANGE
                                } else {
                                    respRate.setTextColor(Color.RED);
                                }
                            }
                            // get temperature values from message as integers
                            abdominalTemp = (double) Integer.parseInt(parsedMessage[2]);
                            ambientTemp = (double) Integer.parseInt(parsedMessage[3]);
                            chestTemp = (double) Integer.parseInt(parsedMessage[4]);
                            // convert temperature values from ADC values to degrees Fahrenheit
                            abdominalTemp = convertTemp(abdominalTemp);
                            ambientTemp = convertTemp(ambientTemp);
                            chestTemp = convertTemp(chestTemp);
                            /* get average ambient temp so far */
                            listAmbient.add(ambientTemp);
                            int sizeAmbient = listAmbient.size();
                            int i = 0;
                            double avgAmbient = 0.0;
                            while (i < sizeAmbient) {
                                avgAmbient += (double) listAmbient.get(i);
                                i++;
                            }
                            avgAmbient = avgAmbient / sizeAmbient;
                            at = (int) abdominalTemp;
                            // calculate core temperature
                            ct = calculateCoreTemp(abdominalTemp, ambientTemp, chestTemp, avgAmbient);
                            coreTemp.setText(Integer.toString(ct));
                            abTemp.setText(Integer.toString(at));
                            listCT.add(ct); // add value to global list for core temperature
                            listAT.add(at); // add value to global list for abdominal temperature
                            // adjust colors for core temperature text field
                            if (ct < 101) {
                                coreTemp.setTextColor(Color.GREEN);
                            } else if (ct >= 101 && ct < 104) {
                                coreTemp.setTextColor(0xFFFF3300);
                            } else {
                                coreTemp.setTextColor(Color.RED);
                            }
                            // adjust colors for abdominal temperature text field
                            if (at < 101) {
                                abTemp.setTextColor(Color.GREEN);
                            } else if (at >= 101 && at < 104) {
                                abTemp.setTextColor(0xFFFF3300);
                            } else {
                                abTemp.setTextColor(Color.RED);
                            }
                            // get currently displayed graph and update it
                            GraphView graph = (GraphView) findViewById(R.id.graph);
                            graph.removeAllSeries();
                            int value;
                            int index = 0;
                            int sizeHR = listHR.size();
                            int sizeRR = listRR.size();
                            int sizeCT = listCT.size();
                            int sizeAT = listAT.size();
                            switch (ActiveGraph) {
                                case 0:
                                    DataPoint[] dpHR = new DataPoint[sizeHR];
                                    while (index < sizeHR) {
                                        value = (int) listHR.get(index);
                                        dpHR[index] = new DataPoint(index * 5, value);
                                        index++;
                                    }
                                    LineGraphSeries<DataPoint> seriesHR = new LineGraphSeries<>(dpHR);
                                    graph.addSeries(seriesHR);
                                    break;
                                case 1:
                                    DataPoint[] dpRR = new DataPoint[sizeRR];
                                    while (index < sizeRR) {
                                        value = (int) listRR.get(index);
                                        dpRR[index] = new DataPoint(index * 5, value);
                                        index++;
                                    }
                                    LineGraphSeries<DataPoint> seriesRR = new LineGraphSeries<>(dpRR);
                                    graph.addSeries(seriesRR);
                                    break;
                                case 2:
                                    DataPoint[] dpCT = new DataPoint[sizeCT];
                                    while (index < sizeCT) {
                                        value = (int) listCT.get(index);
                                        dpCT[index] = new DataPoint(index * 5, value);
                                        index++;
                                    }
                                    LineGraphSeries<DataPoint> seriesCT = new LineGraphSeries<>(dpCT);
                                    graph.addSeries(seriesCT);
                                    break;
                                case 3:
                                    DataPoint[] dpAT = new DataPoint[sizeAT];
                                    while (index < sizeAT) {
                                        value = (int) listAT.get(index);
                                        dpAT[index] = new DataPoint(index * 5, value);
                                        index++;
                                    }
                                    LineGraphSeries<DataPoint> seriesAT = new LineGraphSeries<>(dpAT);
                                    graph.addSeries(seriesAT);
                                    break;
                            }
                        }
                    } else {
                        Context context = getApplicationContext();
                        int duration = Toast.LENGTH_SHORT;
                        String text = "Corrupted data received.";
                        Toast.makeText(context, text, duration).show();
                    }
                    break;
                case MessageConstants.MESSAGE_TOAST: // send to toast notification to screen
                    Context context = getApplicationContext();
                    int duration = Toast.LENGTH_SHORT;
                    String text = (String) msg.obj;
                    Toast.makeText(context, text, duration).show();
                    break;
            }
        }
    };

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
                Message toastMsg = mHandler.obtainMessage(MessageConstants.MESSAGE_TOAST, "Input stream created.");
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
                            Message readMsg = mHandler.obtainMessage(
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

    /* Convert ADC value to degrees Fahrenheit */
    public double convertTemp(double rawValue) {
        double temp = 1023.0 / rawValue - 1.0;
        temp = 10000.0 / temp;
        double steinhart = temp / 10000.0;
        steinhart = Math.log(steinhart);
        steinhart = steinhart / 3950.0;
        steinhart = steinhart + 1.0 / (25.0 + 273.15);
        steinhart = 1.0 / steinhart;
        steinhart = steinhart * (9.0 / 5.0) - 459.67;
        return steinhart;
    }

    /* Calculate core tempurature from the abdominal, ambient, and chest temperatures */
    public int calculateCoreTemp(double abdominal, double ambient, double chest, double avgAmbient) {
        double C = 1;
        double Ka = (0.01203) * avgAmbient + -0.77285;
        double Kb = (0.05661) * avgAmbient + -3.60028;
        double Kc = (-0.07096) * avgAmbient + 5.64545;
        CheckBox override = (CheckBox) findViewById(R.id.overrideConstants);
        if (override.isChecked()) {
            // get input values from EditText objects if override box is checked
            EditText cInput = (EditText) findViewById(R.id.constantC);
            EditText kaInput = (EditText) findViewById(R.id.constantKa);
            EditText kbInput = (EditText) findViewById(R.id.constantKb);
            EditText kcInput = (EditText) findViewById(R.id.constantKc);
            if (!cInput.getText().toString().isEmpty()) {
                C = Double.valueOf(cInput.getText().toString());
            }
            if (!kaInput.getText().toString().isEmpty()) {
                Ka = Double.valueOf(kaInput.getText().toString());
            }
            if (!kbInput.getText().toString().isEmpty()) {
                Kb = Double.valueOf(kbInput.getText().toString());
            }
            if (!kcInput.getText().toString().isEmpty()) {
                Kc = Double.valueOf(kcInput.getText().toString());
            }
        }
        else {
            EditText cInput = (EditText) findViewById(R.id.constantC);
            EditText kaInput = (EditText) findViewById(R.id.constantKa);
            EditText kbInput = (EditText) findViewById(R.id.constantKb);
            EditText kcInput = (EditText) findViewById(R.id.constantKc);
            cInput.setText(String.format("%.5f",C));
            kaInput.setText(String.format("%.5f",Ka));
            kbInput.setText(String.format("%.5f",Kb));
            kcInput.setText(String.format("%.5f",Kc));
        }
        double coreTemp = (Ka * ambient) + (Kb * chest) + (Kc * abdominal) + C;
        return (int) coreTemp;
    }

    /* function is called whenever the labels HR, RR, CT, AT are tapped.
     * Displays graph related to the label tapped.
     */
    public void click(View v) {
        GraphView graph = (GraphView) findViewById(R.id.graph); // get graph object
        graph.removeAllSeries(); // clear graph
        int value;
        int index = 0;
        int sizeHR = listHR.size();
        int sizeRR = listRR.size();
        int sizeCT = listCT.size();
        int sizeAT = listAT.size();
        switch(v.getId()) {
            case R.id.HeartRate: // Heart Rate
                ActiveGraph = 0;
                graph.setTitle("Heart Rate (beats/min X seconds)");
                DataPoint[] dpHR = new DataPoint[sizeHR];
                while (index < sizeHR) {
                    value = (int) listHR.get(index);
                    dpHR[index] = new DataPoint(index * 5, value); // add value for every 5 seconds
                    index++;
                }
                LineGraphSeries<DataPoint> seriesHR = new LineGraphSeries<>(dpHR);
                graph.addSeries(seriesHR); // add data to graph
                break;
            case R.id.RespiratoryRate: // Respiratory Rate
                ActiveGraph = 1;
                graph.setTitle("Respiratory Rate (breaths/minute X seconds)");
                DataPoint[] dpRR = new DataPoint[sizeRR];
                while (index < sizeRR) {
                    value = (int) listRR.get(index);
                    dpRR[index] = new DataPoint(index * 5, value); // add value for every 5 seconds
                    index++;
                }
                LineGraphSeries<DataPoint> seriesRR = new LineGraphSeries<>(dpRR);
                graph.addSeries(seriesRR); // add data to graph
                break;
            case R.id.CoreTemp: // Core Temperature
                ActiveGraph = 2;
                graph.setTitle("Core Temp (degrees F X seconds");
                DataPoint[] dpCT = new DataPoint[sizeCT];
                while (index < sizeCT) {
                    value = (int) listCT.get(index);
                    dpCT[index] = new DataPoint(index * 5, value); // add value for every 5 seconds
                    index++;
                }
                LineGraphSeries<DataPoint> seriesCT = new LineGraphSeries<>(dpCT);
                graph.addSeries(seriesCT); // add data to graph
                break;
            case R.id.AbdominalTemp: // Abdominal Temperature
                ActiveGraph = 3;
                graph.setTitle("Abdominal Temp (degrees F X seconds)");
                DataPoint[] dpAT = new DataPoint[sizeAT];
                while (index < sizeAT) {
                    value = (int) listAT.get(index);
                    dpAT[index] = new DataPoint(index * 5, value); // add value for every 5 seconds
                    index++;
                }
                LineGraphSeries<DataPoint> seriesAT = new LineGraphSeries<>(dpAT);
                graph.addSeries(seriesAT); // add data to graph
                break;
        }
    }

    /* checks if a string consists only of numeric characters */
    public static boolean isNumeric(String str)
    {
        try
        {
            double d = Double.parseDouble(str);
        }
        catch(NumberFormatException nfe)
        {
            return false;
        }
        return true;
    }
}

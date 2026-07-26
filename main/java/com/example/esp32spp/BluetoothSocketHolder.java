package com.example.esp32spp;

import android.bluetooth.BluetoothSocket;

/**
 * Simple holder for a BluetoothSocket so multiple Activities can access it.
 * In your app you may have a more robust connection manager.
 */
public class BluetoothSocketHolder {
    private static BluetoothSocket socket;

    public static synchronized void setSocket(BluetoothSocket s) {
        socket = s;
    }

    public static synchronized BluetoothSocket getSocket() {
        return socket;
    }

    public static synchronized void clear() {
        socket = null;
    }
}

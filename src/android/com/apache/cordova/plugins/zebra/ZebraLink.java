package com.apache.cordova.plugins.zebra;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.Manifest;
import android.telecom.Call;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.BluetoothConnectionInsecure;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.device.MagCardReader;
import com.zebra.sdk.device.MagCardReaderFactory;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.util.internal.CisdfFileSender;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;

public class ZebraLink extends CordovaPlugin {

	public class ZebraLinkException extends Exception
	{
		/**
		 *
		 */
		private static final long serialVersionUID = 1L;

		public ZebraLinkException(String msg) { super(msg); }
	}

	static BluetoothConnection printerConnection;
	static String printerAddress;

	static final String lock = "ZebraLinkLock";

    static final String REQUESTPERM = "requestPermission";
	static final String ENABLEBLUETOOTH = "enableBluetooth";
	static final String DISCOVER = "discover";
	static final String CANCELDISCOVER = "cancelDiscover";
	static final String CONNECT = "connect";
	static final String DISCONNECT = "disconnect";
	static final String SWIPE = "swipe";
	static final String PRINT = "print";
	static final String CHECK = "check";

	static final int BluetoothNotSupportedCode = 1001;
	static final int BluetoothDisabledCode = 1002;
	static final int LocationPermissionDeniedCode = 1003;
	static final int PrinterNotReady = 2001;
	static final int SwipeTimeout = 2002;
	static final int ConnectionErrorCode = 3001;
	static final int InvalidArgumentCode = 4001; // App's mistake
	static final int NoPrinterCode = 4002;
	static final int OtherErrorCode = 5001;      // Plugin's mistake

    static final int PERMISSIONS_REQUEST_LOCATION_ACCESS = 200;
	static final int ENABLE_BLUETOOTH_REQUEST = 201;

	static final String BROKENPIPE = "Broken Pipe";

    private CallbackContext permissionCallback;
	private CallbackContext enableBluetoothCallback;
	private BroadcastReceiver discoveryReceiver;

	@Override
	public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException
	{
		System.err.println("ZebraLink." + action + "("+callbackContext+")");

		PluginResult async = new PluginResult(PluginResult.Status.NO_RESULT,"");
		async.setKeepCallback(true);

		if(action.equals(DISCONNECT)) {
			// Disconnect from current connected device
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    disconnect(callbackContext);
                }
            });

		} else if (action.equals(REQUESTPERM)) {
			// Request runtime location permission
			requestPermission(callbackContext);

		} else if (action.equals(ENABLEBLUETOOTH)) {
			// Show dialog asking user to enable Bluetooth
			enableBluetooth(callbackContext);

        } else if(action.equals(DISCOVER)) {
			// Discover nearby printers
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					discover(args, callbackContext);
				}
			});

		} else if (action.equals(CANCELDISCOVER)) {
			// Cancel discovery
			cancelDiscover(callbackContext);

		} else if (action.equals(CONNECT)) {
			// Connect to specific printer (mac address needed)
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					connect(args, callbackContext);
				}
			});

		} else if(action.equals(SWIPE)) {
			// Swiping credit card?
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					swipe(args, callbackContext);
				}
			});

		} else if (action.equals(PRINT)) {
			// Print
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					print(args, callbackContext);
				}
			});

		} else if (action.equals(CHECK)) {
			cordova.getThreadPool().execute(new Runnable() {
				public void run() {
					check(args, callbackContext);
				}
			});

		} else {
			// Command not found
			return false;
		}

		return true;
	}

// TODO: rewrite
	public void check(JSONArray arguments, CallbackContext callbackContext)
	{
		synchronized(lock)
		{
			try
			{
				this.printerIsConnectedAndReady();
			}
			catch(Exception ex)
			{
				callbackContext.error(ex.getLocalizedMessage());
			}
		}
		callbackContext.success("Success");
	}


    /*
     * Request runtime location permission. (Required to use discovery feature)
     */
    public void requestPermission(CallbackContext callbackContext) {
        if (hasLocationPermission()) {
            // Permission is already granted
            System.err.println("Permission is already granted");
            callbackContext.success(1);
            return;
        }

        try {
            this.permissionCallback = callbackContext;
            String[] permissions = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
            java.lang.reflect.Method method = cordova.getClass().getMethod("requestPermissions",
                    org.apache.cordova.CordovaPlugin.class,
                    int.class,
                    java.lang.String[].class);
            method.invoke(cordova, this, PERMISSIONS_REQUEST_LOCATION_ACCESS, permissions);
        } catch (Exception e) {
            System.err.println("No such method");
			callbackContext.error(createError(OtherErrorCode, e.getLocalizedMessage()));
        }

        // See onRequestPermissionResult() for handler code
    }

	/*
	 * Show dialog asking user to enable Bluetooth.
	 * Do nothing if Bluetooth is already enabled.
	 * Pass 1 in success callback if bluetooth is enabled, 0 if disabled
	 */
	public void enableBluetooth(CallbackContext callbackContext) {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

		if (adapter == null) {
			callbackContext.error(createError(BluetoothNotSupportedCode, "This device does not support Bluetooth"));
			return;
		}

		if (adapter.isEnabled()) {
			callbackContext.success(1);
			return;
		}

		this.enableBluetoothCallback = callbackContext;
		Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		cordova.startActivityForResult(this, enableBtIntent, ENABLE_BLUETOOTH_REQUEST);

		// See onActivityResult() for handler code

	}

	/*
	* Options: {
	* 	printerOnly: true (if false, will return all device found)
	* }
	 */
	public void discover(JSONArray arguments, CallbackContext callbackContext)
	{

		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if (adapter == null) {
			// The device does not support Bluetooth
			System.err.println("This device does not support Bluetooth");
            callbackContext.error(createError(BluetoothNotSupportedCode, "This device does not support Bluetooth"));
            return;
		}

        if (!adapter.isEnabled()) {
			// Bluetooth was disabled
            System.err.println("Bluetooth is disabled");
            callbackContext.error(createError(BluetoothDisabledCode, "Bluetooth is disabled"));
            return;
		}

        // Check and request ACCESS_COARSE_LOCATION permission
        if (!hasLocationPermission()) {
            // ACCESS_COARSE_LOCATION permission denied
			System.err.println("ACCESS_COARSE_LOCATION permission denied");
			callbackContext.error(createError(LocationPermissionDeniedCode, "ACCESS_COARSE_LOCATION permission denied"));
			return;
        }

		// Prepare option argument
		boolean printerOnly = true;
		try {
			JSONObject argDict = arguments.getJSONObject(0);
			if (argDict.has("printerOnly")) {
				printerOnly = argDict.getBoolean("printerOnly");
			}
		} catch (JSONException e) {
			e.printStackTrace();
			callbackContext.error(createError(InvalidArgumentCode, e.getLocalizedMessage()));
			return;
		}

		if (adapter.isDiscovering()) {
			adapter.cancelDiscovery();
		}

		// Define intent filter
		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

		// Register receiver
		// See method getBluetoothReceiver for handler logics
		BroadcastReceiver mReceiver = getBluetoothReceiver(callbackContext, printerOnly);
		cordova.getActivity().getApplicationContext().registerReceiver(mReceiver, filter);
		System.err.println("Register receiver " + mReceiver);
		this.discoveryReceiver = mReceiver;

		// Ready and go!
		adapter.startDiscovery();

	}

	public void cancelDiscover(CallbackContext callbackContext) {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

		if (adapter == null) {
			// The device does not support Bluetooth
			System.err.println("This device does not support Bluetooth");
			callbackContext.error(createError(BluetoothNotSupportedCode, "This device does not support Bluetooth"));
			return;
		}

		if (adapter.isDiscovering()) {
			adapter.cancelDiscovery();
		}

        System.err.println("Cancel Discovery");
		callbackContext.success();
	}

	private BroadcastReceiver getBluetoothReceiver(final CallbackContext _callbackContext, final boolean printerOnly) {
		// Define receiver
		class BluetoothReceiver extends BroadcastReceiver {
			JSONArray devices;
			boolean isStarted;
			CallbackContext callbackContext;

			public BluetoothReceiver() {
				devices = new JSONArray();
				this.isStarted = false;
				this.callbackContext = _callbackContext;
			}

			public void onReceive(Context context, Intent intent) {

				String action = intent.getAction();

				if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
					System.err.println("Start discovery " + this);
					this.isStarted = true;

				} else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					// When discovery finds a device
					// Get the BluetoothDevice object from the Intent
					BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					System.err.println("Found " + device.getName() + ", address: " + device.getAddress());

					// Printer has major class: 1536, and class 1664
					// https://www.bluetooth.com/specifications/assigned-numbers/baseband
					BluetoothClass deviceClass = device.getBluetoothClass();
					if (!printerOnly) {
						// Add all devices
						JSONObject deviceJSON = toDeviceJSON(device);
						devices.put(deviceJSON);
						PluginResult result = new PluginResult(PluginResult.Status.OK, deviceJSON);
						result.setKeepCallback(true);
						callbackContext.sendPluginResult(result);

					} else if (deviceClass.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING &&
							deviceClass.getDeviceClass() == 1664) {
						// Add only printer
						JSONObject deviceJSON = toDeviceJSON(device);
						devices.put(deviceJSON);
						PluginResult result = new PluginResult(PluginResult.Status.OK, deviceJSON);
						result.setKeepCallback(true);
						callbackContext.sendPluginResult(result);
					}

				} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
					System.err.println("Discover finished " + this);

					if (this.isStarted) {
						System.out.println("Unregister " + this);
						cordova.getActivity().getApplicationContext().unregisterReceiver(this);
						callbackContext.success(devices);
					}
				}
			}

			private JSONObject toDeviceJSON(BluetoothDevice device) {
				JSONObject jDevice = new JSONObject();
				try {
					jDevice.put("name", device.getName());
					jDevice.put("address", device.getAddress());
					jDevice.put("isPaired", device.getBondState() == BluetoothDevice.BOND_BONDED);
					jDevice.put("majorClass", device.getBluetoothClass().getMajorDeviceClass());
					jDevice.put("minorClass", device.getBluetoothClass().getDeviceClass());

				} catch (JSONException e) {
					e.printStackTrace();
				}

				return jDevice;
			}
		}

		return new BluetoothReceiver();
	}

	public void print(JSONArray arguments, CallbackContext callbackContext)
	{
		String template;
		JSONObject values;

		System.err.println("Printing...");

		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			// Device does not support Bluetooth
			callbackContext.error(createError(BluetoothNotSupportedCode, "Bluetooth Not Supported On Device"));
			return;
		}

		if (!mBluetoothAdapter.isEnabled()) {
			// Bluetooth is not enabled :)
			callbackContext.error(createError(BluetoothDisabledCode, "Bluetooth Not Enabled"));
			return;
		}

		if (printerConnection == null) {
			callbackContext.error(createError(NoPrinterCode, "Please connect to printer before printing"));
			return;
		}


		try {

			JSONObject argDict = arguments.getJSONObject(0);
			template = argDict.getString("template");
			values = argDict.getJSONObject("formValues");
			String error = "";

			// Prepare template
			for (Iterator<String> it = values.keys(); it.hasNext(); ) {
				String k = it.next();
				String value = values.getString(k);
				String token = "@" + k + "@";
				template = template.replaceAll(token, value);
			}

			// Printer only support \r\n
			String[] templateArr = template.split("\\r\\n|\\r|\\n");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < templateArr.length; i++) {
				System.err.println("line: " + templateArr[i]);
				sb.append(templateArr[i].trim() + "\r\n");
			}
			String formattedTemplate = sb.toString();


			if (isPrinterReady()) {
				// Print normally, nicely, peacefully
				synchronized (lock) {
					printerConnection.write(formattedTemplate.getBytes());
				}

			} else {
				// Printer is not ready, see what happen
				error = getPrinterErrorMessage();

				if (BROKENPIPE.equals(error) || (printerConnection != null && !printerConnection.isConnected())) {
					// If Broken Pipe,  Try to open new connection, then write
					synchronized (lock) {
						printerConnection.close();
						printerConnection.open();
						printerConnection.write(formattedTemplate.getBytes());
					}
					// Assume that write successfully. Reset error.
					error = "";
				}
			}

			if (error.length() > 0) {
				callbackContext.error(createError(PrinterNotReady, error));
			} else {
				callbackContext.success("Success");
			}

		} catch (JSONException e) {
			System.err.println("Something wrong" + e);
			callbackContext.error(createError(InvalidArgumentCode, e.getLocalizedMessage()));

		} catch (ZebraPrinterLanguageUnknownException e) {
			e.printStackTrace();
			callbackContext.error(createError(OtherErrorCode, e.getLocalizedMessage()));

		} catch (ConnectionException e) {
			System.err.println(e.getLocalizedMessage());
			callbackContext.error(createError(ConnectionErrorCode, e.getLocalizedMessage()));
		}

	}

	public void test(JSONArray arguments, CallbackContext callbackContext)
	{
		try
		{
			JSONObject argDict = arguments.getJSONObject(0);
			String macAdd = argDict.getString("address");
			BluetoothConnection myConn = new BluetoothConnectionInsecure(macAdd);
			myConn.open();
			ZebraPrinter myPrinter = ZebraPrinterFactory.getInstance(myConn);
			myPrinter.printConfigurationLabel();
			myConn.close();
			callbackContext.success("Success");
		}
		catch(Exception ex)
		{
			callbackContext.error(ex.getLocalizedMessage());
		}
	}

	public void connect(JSONArray arguments, CallbackContext callbackContext) {

		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			// Device does not support Bluetooth
			callbackContext.error(createError(BluetoothNotSupportedCode, "Bluetooth Not Supported On Device"));
			return;
		}

		if (!mBluetoothAdapter.isEnabled()) {
			// Bluetooth is not enabled :)
			callbackContext.error(createError(BluetoothDisabledCode, "Bluetooth Not Enabled"));
			return;
		}

		PrinterStatus status;

		// Get and verify address
		String address;
		try {
			JSONObject argDict = arguments.getJSONObject(0);
			address = argDict.getString("address");

		} catch (JSONException e) {
			e.printStackTrace();
			callbackContext.error(createError(InvalidArgumentCode, e.getLocalizedMessage()));
			return;
		}

		if (address == null || address.trim().length() == 0) {
			callbackContext.error(createError(InvalidArgumentCode, "Address must not be null or empty"));
			return;
		}

		try {
			// Disconnect old connection with different printer
			if (printerConnection != null && !address.equals(printerConnection.getMACAddress())) {

				System.err.println("Disconnecting old connection");
				synchronized (lock) {
					printerConnection.close();
				}
				printerConnection = null;
			}

			// Get connection or create a new one
			BluetoothConnection connection = printerConnection;
			if (connection == null) {
				connection = new BluetoothConnectionInsecure(address);
			}

			synchronized(lock) {
				// Open connection
				connection.open();
				printerConnection = connection;

				// Get printer status
				ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
				status = printer.getCurrentStatus();
			}

			callbackContext.success(printerStatusAsDictionary(status));

		} catch(ConnectionException ex) {

			try {
				// Make sure connection is close
				if (printerConnection != null) {
					synchronized (lock) {
						printerConnection.close();
					}
				}
			} catch (Exception e) {
				e.printStackTrace(); // Do nothing

			} finally {
				printerConnection = null;
				String error = ex.getLocalizedMessage();
				System.err.println(error);
				callbackContext.error(createError(ConnectionErrorCode, error));
			}

		} catch (ZebraPrinterLanguageUnknownException e) {
			callbackContext.error(createError(OtherErrorCode, e.getLocalizedMessage()));
		}
	}

	public void disconnect(CallbackContext callbackContext) {
		try {
			synchronized(lock) {
				printerConnection.close();
			}
			System.err.println("Disconnected");
			callbackContext.success();

		} catch (ConnectionException e) {
			e.printStackTrace();
			callbackContext.error(createError(ConnectionErrorCode, e.getLocalizedMessage()));

		}

	}

	public void swipe(JSONArray arguments,CallbackContext callbackContext) {

		BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			// Device does not support Bluetooth
			callbackContext.error(createError(BluetoothNotSupportedCode, "Bluetooth Not Supported On Device"));
			return;
		}

		if (!mBluetoothAdapter.isEnabled()) {
			// Bluetooth is not enabled :)
			callbackContext.error(createError(BluetoothDisabledCode, "Bluetooth Not Enabled"));
			return;
		}

		if (printerConnection == null) {
			callbackContext.error(createError(NoPrinterCode, "Please connect to printer before swiping"));
			return;
		}


		// Prepare arguments. Default timeout is 10 seconds
		int timeout = 10000;
		try {
			JSONObject argDict = arguments.getJSONObject(0);
			if (argDict.has("timeout")) {
				timeout = argDict.getInt("timeout");
			}

		} catch (JSONException e) {
			e.printStackTrace();
			callbackContext.error(createError(InvalidArgumentCode, e.getLocalizedMessage()));
			return;
		}


		// Check connection and get printer
		MagCardReader cardReader;
		try {
			synchronized (lock) {
				ZebraPrinter printer = ZebraPrinterFactory.getInstance(printerConnection);
				cardReader = MagCardReaderFactory.create(printer);
			}
		} catch (ConnectionException e) {
			// Try connecting again
			try {
				synchronized (lock) {
					System.err.println("reconnect");
					printerConnection.close();
					printerConnection.open();
					ZebraPrinter printer = ZebraPrinterFactory.getInstance(printerConnection);
					cardReader = MagCardReaderFactory.create(printer);
				}
			} catch (ConnectionException conE) {
				callbackContext.error(createError(ConnectionErrorCode, e.getLocalizedMessage()));
				return;
			} catch (Exception ex) {
				callbackContext.error(createError(OtherErrorCode, e.getLocalizedMessage()));
				return;
			}

		} catch (Exception e) {
			callbackContext.error(createError(OtherErrorCode, e.getLocalizedMessage()));
			return;
		}


		// Ready to read card. Send signal to app
		PluginResult result = new PluginResult(PluginResult.Status.OK, "Ready");
		result.setKeepCallback(true);
		callbackContext.sendPluginResult(result);
		System.err.println("ready");


		// Start reading
		String[] tracks;
		try {
			synchronized (lock) {
				tracks = cardReader.read(timeout);
			}
		} catch (ConnectionException e) {
			callbackContext.error(createError(ConnectionErrorCode, e.getLocalizedMessage()));
			return;
		}
	System.err.println("Finish reading");

		// Validate tracks
		if (tracks == null || tracks.length == 0) {
			callbackContext.error(createError(SwipeTimeout, "Swipe timeout"));
			return;
		}

		int totalLength = 0;
		for (int i = 0; i < tracks.length; i++) {
			totalLength += tracks[i].length();
		}

		if (totalLength == 0) {
			callbackContext.error(createError(SwipeTimeout, "Swipe timeout"));
			return;
		}

		// Nothing goes wrong, return as success
		callbackContext.success(new JSONArray(Arrays.asList(tracks)));

	}


	// ============================= Callbacks =============================

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {

        if (requestCode == PERMISSIONS_REQUEST_LOCATION_ACCESS) {
			CallbackContext callbackContext = this.permissionCallback;

            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                System.err.println("Permission granted");
                callbackContext.success(1);

            } else {
                System.err.println("Permission denied");
                callbackContext.success(0);
            }

			this.permissionCallback = null;
        }
    }

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {

		if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
			CallbackContext callbackContext = this.enableBluetoothCallback;

			if (resultCode == Activity.RESULT_OK) {
				callbackContext.success(1);
			} else {
				callbackContext.success(0);
			}

			this.enableBluetoothCallback = null;
		}
	}

	public void onDestroy() {
		if(printerConnection != null) {
			try {
				printerConnection.close();
			} catch(Exception ex){
				ex.printStackTrace();
			} // who cares?
		}
		printerConnection = null;
	}



	// ============================= Private Helpers =============================

	private boolean hasLocationPermission() {
		boolean hasPermission = true;

		try {
			String permission = Manifest.permission.ACCESS_COARSE_LOCATION;
			java.lang.reflect.Method method = cordova.getClass().getMethod("hasPermission", permission.getClass());
			Boolean bool = (Boolean) method.invoke(cordova, permission);
			hasPermission = bool.booleanValue();

		} catch (Exception e) {
			e.printStackTrace();
		}

		return hasPermission;
	}

	/*
	 * Simple check if printer is connected and ready to use.
	 * Return true/false
	 */
	private boolean isPrinterReady() throws ConnectionException, ZebraPrinterLanguageUnknownException {
		if (printerConnection == null) return false;
		if (!printerConnection.isConnected()) return false;

		PrinterStatus status;
		try {
			synchronized (ZebraLink.lock) {
				ZebraPrinter printer = ZebraPrinterFactory.getInstance(printerConnection);
				status = printer.getCurrentStatus();
			}
		} catch (ConnectionException conE) {
			// Broken Pipe exception shoudl be handled nicely (not just throwing error)
			System.err.println("Printer is not ready: " + conE.getLocalizedMessage());
			return false;
		}

		return status.isReadyToPrint;
	}

	// Return printer error message
	// Return null if printer is ready
	// Should be called outside synchronized block
	private String getPrinterErrorMessage() throws ZebraPrinterLanguageUnknownException {
		if (printerConnection == null || !printerConnection.isConnected()) return "Printer is not connected";

		PrinterStatus status;
		try {
			synchronized (ZebraLink.lock) {
				ZebraPrinter printer = ZebraPrinterFactory.getInstance(printerConnection);
				status = printer.getCurrentStatus();
			}
		} catch (ConnectionException conE) {
			System.err.println(BROKENPIPE);
			return BROKENPIPE;
		}

		return errorFromStatus(status);
	}


	// Get one error from ZebraPrinter. Return null if the printer is ready
	// Should be used before print
	private String errorFromStatus(PrinterStatus status) {
		if (status.isReadyToPrint) return null;
		if (status.isHeadOpen) return "Printer head open";
		if (status.isHeadTooHot) return "Printer head too hot";
		if (status.isPaperOut) return "Printer paper out";
		if (status.isReceiveBufferFull) return "Printer buffer full";
		if (status.isRibbonOut) return "Printer ribbon out";
		if (status.isPaused) return "Printer is paused";

		return null;
	}

	public JSONObject printerStatusAsDictionary(PrinterStatus status)
	{
		JSONObject dict = new JSONObject();
		try
		{
			dict.put("ready", status.isReadyToPrint);
			dict.put("open", status.isHeadOpen);
			dict.put("cold", status.isHeadCold);
			dict.put("too_hot", status.isHeadTooHot);
			dict.put("paper_out", status.isPaperOut);
			dict.put("ribbon_out", status.isRibbonOut);
			dict.put("buffer_full", status.isReceiveBufferFull);
			dict.put("paused", status.isPaused);
			dict.put("partial_format_in_progress", status.isPartialFormatInProgress);
			dict.put("labels_remaining_in_batch", status.labelsRemainingInBatch);
			dict.put("label_length_in_dots", status.labelLengthInDots);
			dict.put("number_of_formats_in_receive_buffer", status.numberOfFormatsInReceiveBuffer);
		}
		catch(JSONException ex)
		{
			System.err.println(""+ex);
			ex.printStackTrace(System.err);
		}
		return dict;
	}

	public JSONObject getStatusForPrinterConnection(BluetoothConnection connection)
	{
		// must already be in a synchronized block
		try
		{
			ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);

			if(printer != null)
			{
				PrinterStatus status = printer.getCurrentStatus();
				JSONObject statusDict = this.printerStatusAsDictionary(status);
				return statusDict;
			}
		}
		catch(Exception ex)
		{
			JSONObject error = new JSONObject();
			try {
				error.put("error", ex.getMessage());
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
			try {
				error.put("ready", false);
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
			return error;
		}
		return null;
	}


	public boolean printerIsConnectedAndReady() throws ZebraLinkException
	{
		// this should only be called within an @synchronized(lock) block
		// in a background thread
		// if the connection is closed - open it
		if((printerConnection == null || !printerConnection.isConnected()) && printerAddress != null)  {

			printerConnection = new BluetoothConnectionInsecure(printerAddress);
			try {
				printerConnection.open();

			} catch(Exception ex) {
				// open failed - clean up
				try {
					printerConnection.close();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					//e.printStackTrace();
				} finally {
					printerConnection = null;
				}

			}
		}

		// no connection and we couldn't open one
		if(printerConnection == null)
		{
			throw new ZebraLinkException("Printer Not Responding");
		}

		JSONObject dict = this.getStatusForPrinterConnection(printerConnection);

		if(dict != null)
		{
			if(dict.optBoolean("ready"))
			{
				return true;
			}
			throw new ZebraLinkException(this.printerStatusMessageForStatus(dict));
		}

		// close the connection - it is broken
		try {
			printerConnection.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		printerConnection = null;

		throw new ZebraLinkException("Printer Not Responding");
	}

	public String printerStatusMessageForStatus(JSONObject dict) {
		if(dict == null)
		{
			return "Printer Not Responding";
		}
		String msg = "";
		String json = dict.toString();

		System.err.println(json);

		if(dict.optBoolean("ready"))
		{
			msg = "Printer Ready";
		}
		else if(dict.optString("error") != null && dict.optString("error").trim().length() > 0)
		{
			msg = dict.optString("error");
		}
		else if(dict.optBoolean("open"))
		{
			msg = "Printer Door Open";
		}
		else if(dict.optBoolean( "paper_out"))
		{
			msg = "Printer Out Of Paper";
		}
		else if(dict.optBoolean( "ribbon_out"))
		{
			msg = "Printer Ribbon Out";
		}
		else if(dict.optBoolean( "buffer_full"))
		{
			msg = "Printer Buffer Full";
		}
		else if(dict.optBoolean( "too_hot"))
		{
			msg = "Printer Too Hot";
		}
		else if(dict.optBoolean( "cold"))
		{
			msg = "Printer Warming Up";
		}
		else if(dict.optBoolean("paused"))
		{
			msg = "Printer Is Paused";
		}
		else
		{
			msg = "Check Printer";
		}
		return msg;
	}

	private JSONObject createError(int code, String message) {
		JSONObject error = new JSONObject();
		try {
			error.put("code", code);
			error.put("message", message);
			return error;

		} catch (JSONException e) {
			e.printStackTrace();
			return error;
		}
	}

}

package com.apache.cordova.plugins.zebra;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.BluetoothConnectionInsecure;
import com.zebra.sdk.device.MagCardReader;
import com.zebra.sdk.device.MagCardReaderFactory;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterBluetooth;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class ZebraLink extends CordovaPlugin {

	public class ZebraLinkException extends Exception
	{
		/**
		 *
		 */
		private static final long serialVersionUID = 1L;

		public ZebraLinkException(String msg) { super(msg); }
	}

	public class NSError
	{
		public String message = null;
		public boolean isOK() { return message == null; }
	}

	static String lastPrinterStatus;
	static JSONArray lastDiscoveredPrinters;
	static BluetoothConnection printerConnection;
	static String printerAddress;

	static final String lock = "ZebraLinkLock";

	static final String DISCOVER = "discover";
	static final String CONNECT = "connect";
	static final String DISCONNECT = "disconnect";
	static final String SWIPE = "swipe";
	static final String PRINT = "print";
	static final String CHECK = "check";

	@Override
	public boolean execute(String action, JSONArray inargs, CallbackContext cid) throws JSONException
	{
		System.err.println("ZebraLink." + action + "("+cid+")");

		PluginResult async = new PluginResult(PluginResult.Status.NO_RESULT,"");
		async.setKeepCallback(true);

		if(action.equals(DISCONNECT)) // run synchronously
		{
			try
			{
				this.disconnect(inargs,cid);
			}
			catch(Throwable t)
			{
				t.printStackTrace();
			}
			cid.sendPluginResult(async);
			return true;
		}

		if(action.equals(DISCOVER))
		{
			class Discover implements Runnable
			{
				ZebraLink z;
				JSONArray arguments;
				CallbackContext callbackId;

				public Discover(ZebraLink z, JSONArray args, CallbackContext cbid) { this.z = z; this.arguments = args; this.callbackId = cbid; }
				public void run() { /*Looper.prepare();*/ z.discover(this.arguments,this.callbackId); /*Looper.myLooper().quit();*/ }

			}

			cid.sendPluginResult(async);
			try
			{
				new Discover(this,inargs,cid).run();
			}
			catch(Throwable t)
			{
				t.printStackTrace();
			}
			return true;
		}

		if (action.equals(CONNECT))
		{
			class Connect implements Runnable
			{
				ZebraLink z;
				JSONArray arguments;
				CallbackContext callbackId;

				public Connect(ZebraLink z, JSONArray args, CallbackContext cbid) { this.z = z; this.arguments = args; this.callbackId = cbid; }
				public void run() { /* Looper.prepare(); */ z.connect(this.arguments,this.callbackId); /* Looper.myLooper().quit(); */ }

			}
			new Connect(this,inargs,cid).run();
			cid.sendPluginResult(async);
			return true;
		}
		if(action.equals(SWIPE))
		{
			class Swipe implements Runnable
			{
				ZebraLink z;
				JSONArray arguments;
				CallbackContext callbackId;

				public Swipe(ZebraLink z, JSONArray args, CallbackContext cbid) { this.z = z; this.arguments = args; this.callbackId = cbid; }
				public void run() { z.swipe(this.arguments,this.callbackId); }
			}
			cid.sendPluginResult(async);
			new Swipe(this,inargs,cid).run();
			return true;
		}
		if (action.equals(PRINT))
		{
			class Print implements Runnable
			{
				ZebraLink z;
				JSONArray arguments;
				CallbackContext callbackId;

				public Print(ZebraLink z, JSONArray args, CallbackContext cbid) { this.z = z; this.arguments = args; this.callbackId = cbid; }
				public void run() {z.print(this.arguments,this.callbackId); }
			}
			cid.sendPluginResult(async);
			new Print(this,inargs,cid).run();
			return true;
		}
		if (action.equals(CHECK))
		{
			class Check implements Runnable
			{
				ZebraLink z;
				JSONArray arguments;
				CallbackContext callbackId;

				public Check(ZebraLink z, JSONArray args, CallbackContext cbid) { this.z = z; this.arguments = args; this.callbackId = cbid; }
				public void run() {z.check(this.arguments,this.callbackId); }
			}

			cid.sendPluginResult(async);
			new Check(this,inargs,cid).run();
			return true;
		}

		return false;
	}


	public void check(JSONArray arguments, CallbackContext cid)
	{
		synchronized(ZebraLink.lock)
		{
			try
			{
				this.printerIsConnectedAndReady();
			}
			catch(Exception ex)
			{
				cid.error(ex.getLocalizedMessage());
			}
		}
		cid.success("Success");
	}

	// utility function
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


	public String printerStatusMessageForStatus(JSONObject dict)
	{
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

	public boolean printerIsConnectedAndReady() throws ZebraLinkException
	{
		// this should only be called within an @synchronized(lock) block
		// in a background thread
		// if the connection is closed - open it
		if((ZebraLink.printerConnection == null || !ZebraLink.printerConnection.isConnected()) && ZebraLink.printerAddress != null)
		{
			ZebraLink.printerConnection = new BluetoothConnectionInsecure(ZebraLink.printerAddress);
			try
			{
				ZebraLink.printerConnection.open();
			}
			catch(Exception ex)
			{
				// open failed - clean up
				try {
					ZebraLink.printerConnection.close();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					//e.printStackTrace();
				}
				ZebraLink.printerConnection = null;
			}
		}

		// no connection and we couldn't open one
		if(ZebraLink.printerConnection == null)
		{
			throw new ZebraLinkException("Printer Not Responding");
		}

		JSONObject dict = this.getStatusForPrinterConnection(ZebraLink.printerConnection);

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
			ZebraLink.printerConnection.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		ZebraLink.printerConnection = null;

		throw new ZebraLinkException("Printer Not Responding");
	}

	public void discover(JSONArray arguments, CallbackContext cid)
	{
		class BTDiscoveryHandler implements DiscoveryHandler
		{
			JSONArray printers = new JSONArray();
			CallbackContext cid;

			public BTDiscoveryHandler(CallbackContext cid) { this.cid = cid; }

			public void discoveryError(String message)
			{
				this.cid.error(message);
			}

			public void discoveryFinished()
			{
				this.cid.success(printers);
			}

			@Override
			public void foundPrinter(DiscoveredPrinter printer)
			{
				DiscoveredPrinterBluetooth pr = (DiscoveredPrinterBluetooth) printer;

				try
				{
					Map<String,String> map = pr.getDiscoveryDataMap();

					for (String settingsKey : map.keySet()) {
						System.out.println("Key: " + settingsKey + " Value: " + printer.getDiscoveryDataMap().get(settingsKey));
					}

					String name = pr.friendlyName;
					String mac = pr.address;
					JSONObject p = new JSONObject();
					p.put("name",name);
					p.put("address", mac);
					for (String settingsKey : map.keySet()) {
						System.out.println("Key: " + settingsKey + " Value: " + map.get(settingsKey));
						p.put(settingsKey,map.get(settingsKey));
					}
					printers.put(p);
				} catch (Exception e) {}
			}
		};

		try
		{
			BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
			Set<BluetoothDevice> devices = adapter.getBondedDevices();
			//adapter.cancelDiscovery();

			JSONArray array = new JSONArray();

			for(Iterator<BluetoothDevice> it = devices.iterator(); it.hasNext();)
			{
				BluetoothDevice device = it.next();
				BluetoothClass cls = device.getBluetoothClass();
				String name = device.getName();
				String major = String.format("0x%X",cls.getMajorDeviceClass());
				String minor = String.format("0x%X",cls.getDeviceClass());
				String mac = device.getAddress();
				JSONObject p = new JSONObject();
				p.put("name",name);
				p.put("address", mac);
				p.put("major_device_class", major);
				p.put("device_class", minor);


				switch(device.getBondState())
				{
					case BluetoothDevice.BOND_BONDED:
					{
						p.put("bond_state", "BOND_BONDED");
						break;
					}
					case BluetoothDevice.BOND_BONDING:
					{
						p.put("bond_state", "BOND_BONDING");
						break;
					}
					case BluetoothDevice.BOND_NONE:
					{
						p.put("bond_state ", "BOND_NONE");
						break;
					}
					default:
					{
						break;
					}
				}

				array.put(p);
				System.err.println(p.toString(2));
			}
			//resultString = new PluginResult(PluginResult.Status.OK, array).toSuccessCallbackString(cid);
			BluetoothDiscoverer.findPrinters(cordova.getActivity(), new BTDiscoveryHandler(cid));
		}
		catch(Exception ex)
		{
			cid.error(ex.getLocalizedMessage());
		}
	}
        /**
         * Converts a text string with extended ASCII characters
         * into a Java-friendly byte array.
         * @param input
         * @return
         */
        private byte[] convertExtendedAscii(String input)
        {
                int length = input.length();
                byte[] retVal = new byte[length];

                for(int i=0; i<length; i++)
                {
                          char c = input.charAt(i);

                          if (c < 127)
                          {
                                  retVal[i] = (byte)c;
                          }
                          else
                          {
                                  retVal[i] = (byte)(c - 256);
                          }
                }

                return retVal;
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

	public void print(JSONArray arguments, CallbackContext cid)
	{
		String template = null;
		JSONObject values = null;

		try
		{
			BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null)
			{
				// Device does not support Bluetooth
				throw new ZebraLinkException("Bluetooth Not Supported On Device");
			}
			else
			{
				if (!mBluetoothAdapter.isEnabled())
				{
					// Bluetooth is not enabled :)
					throw new ZebraLinkException("Bluetooth Not Enabled");
				}
			}

			//	printerIsConnectedAndReady();

			JSONObject argDict = arguments.getJSONObject(0);
			template = argDict.getString("template");
			values = argDict.getJSONObject("formValues");

			for(Iterator<String> it = values.keys(); it.hasNext();)
			{
				String k = it.next();
				String value = values.getString(k);
				String token = "@"+k+"@";
				template = template.replaceAll(token,value);
			}
			template = template.replaceAll("\n", "\r\n").replaceAll("\r\r","\r");

			synchronized(ZebraLink.lock)
			{
				ZebraLink.printerConnection.write(convertExtendedAscii(template));
			}
			cid.success("Success");
		}
		catch(Exception ex)
		{
			cid.error(ex.getLocalizedMessage());
		}

	}

	public void test(JSONArray arguments, CallbackContext cid)
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
			cid.success("Success");
		}
		catch(Exception ex)
		{
			cid.error(ex.getLocalizedMessage());
		}
	}

	public void connect(JSONArray arguments, CallbackContext cid)
	{
		String address = null;

		try
		{
			JSONObject argDict = arguments.getJSONObject(0);
			address = argDict.getString("address");
			if(address != null && address.trim().length() > 0) { ZebraLink.printerAddress = address; }
			BluetoothConnection connection = new BluetoothConnectionInsecure(ZebraLink.printerAddress);

			synchronized(ZebraLink.lock)
			{
				connection.open();

				JSONObject dict = this.getStatusForPrinterConnection(connection);
				if(dict.optBoolean("ready"))
				{
					ZebraLink.printerConnection = connection;
				}
				cid.success(this.printerStatusMessageForStatus(dict));
				return;
			}
		}
		catch(Exception ex)
		{
			cid.error(ex.getLocalizedMessage());
		}
	}
	public void disconnect(JSONArray arguments, CallbackContext cid)
	{
		synchronized(ZebraLink.lock)
		{
			try {
				ZebraLink.printerConnection.close();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
			ZebraLink.printerConnection = null;
		}
	}

	public void swipe(JSONArray arguments,CallbackContext cid)
	{
		String tracks[] = null;

		try
		{
			JSONObject argDict = arguments.getJSONObject(0);
			int timeout = argDict.getInt("timeout");
			if(timeout == 0) { timeout = 20000; }
			synchronized(ZebraLink.lock)
			{
				int tries = 0;
				int max_tries = 1;
				ZebraPrinter printer = ZebraPrinterFactory.getInstance(ZebraLink.printerConnection);

				while((tracks == null || tracks.length == 0) && tries++ < max_tries)
				{
					MagCardReader reader = MagCardReaderFactory.create(printer);
					tracks = reader.read(timeout);
				}
			}
			if(tracks == null || tracks.length == 0)
			{
				throw new ZebraLinkException("Unable To Read Card");
			}
			JSONArray jsonTracks = new JSONArray();
			for(int i = 0; i < tracks.length; ++i)
			{
				jsonTracks.put(tracks[i]);
			}
			cid.success(jsonTracks);
		}
		catch(Exception ex)
		{
			cid.error(ex.getLocalizedMessage());
		}
	}

	public void onDestroy()
	{
		if(ZebraLink.printerConnection != null)
		{
			try
			{
				ZebraLink.printerConnection.close();
			} catch(Exception ex){} // who cares?
		}
		ZebraLink.printerConnection = null;
	}

}

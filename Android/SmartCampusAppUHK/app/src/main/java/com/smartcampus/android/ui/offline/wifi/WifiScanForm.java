/*
Copyright (c) 2014, Aalborg University
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.smartcampus.android.ui.offline.wifi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import android.annotation.SuppressLint;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import com.smartcampus.R;
import com.smartcampus.android.location.LocationService;
import com.smartcampus.android.ui.Globals;
import com.smartcampus.android.ui.maps.offline.WebMap2DOffline;
import com.smartcampus.android.wifi.WifiMeasurementBuilder;
import com.smartcampus.indoormodel.AbsoluteLocation;
import com.smartcampus.indoormodel.Building;
import com.smartcampus.indoormodel.graph.IGraph;
import com.smartcampus.webclient.BatchUpdater;
import com.smartcampus.webclient.IWebClient;
import com.smartcampus.webclient.OData4jWebClient;
import com.smartcampus.webclient.snifferbackend.*;
import com.smartcampus.wifi.MacInfo;
import com.smartcampus.wifi.WifiMeasurement;

/**
 * This class is used for taking a measurement and adding it to the radio map on the server. 
 * @author rhansen
 *
 */
public class WifiScanForm extends ListActivity {
	
	private static String clientMacAddress;
	
	//Get the client's mac address
	private String getClientMacAddress()
	{
		String mac = null;
		WifiManager wifiMan = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
					
		//Wi-Fi needs to be turned on in order to get mac address
		//If we turn it on, we turn it back off afterwards
		boolean prevWifiStatus = wifiMan.isWifiEnabled();
		boolean curWifiStatus = prevWifiStatus;
		if (prevWifiStatus == false)
		{
			curWifiStatus = wifiMan.setWifiEnabled(true);
		}
		mac = wifiMan.getConnectionInfo().getMacAddress();
		if (curWifiStatus != prevWifiStatus)
			wifiMan.setWifiEnabled(prevWifiStatus);
				
		return mac;		
	}	
	
	
	@SuppressLint("NewApi") //TODO: Update API
	private class UploadMeasurementTask extends AsyncTask<WifiMeasurement, Void, Integer>
    {
		private String downloadMsg = "Ok";
		private WifiScanForm mOwner;
		
		UploadMeasurementTask(WifiScanForm owner)
		{
			this.mOwner = owner;
		}		
		
		//Upload a new measurement
		//Also, in the case of an unbound location, upload that too (i.e., the vertex)
		//Upload the vertex first as a measurement requires a vertex		
		@Override
		protected Integer doInBackground(WifiMeasurement... params)
		{
			WifiMeasurement newMeas = params[0];
			//DEBUG: Missing WifiMeasurement
			if (newMeas.getNumMACs() == 0)
			{
				downloadMsg = "Empty Measurement";
				return exceptionID;
			}
			
			//NOTE: Should probably move this to after attempt to upload (and now it is - cf. below)
			WebMap2DOffline.SelectedOfflineVertex.addFingerprint(newMeas);
			int vertexId;
			
			IWebClient webClient = new OData4jWebClient();
			Building currentBuilding = LocationService.CurrentBuilding;
			IGraph graph = currentBuilding.getGraphModel();
			if (!mOwner.mIsLocationBound) //We have an unbound location
			{
				try
				{
					//Upload vertex to server
					vertexId = webClient.addVertex(WebMap2DOffline.SelectedOfflineVertex, LocationService.CurrentBuilding);	
				}
				catch (Exception ex1)
				{
					Throwable t = ex1.getCause();
					if (t != null)
						downloadMsg = t.getMessage();
					else
						downloadMsg = ex1.getMessage();
					return exceptionID;
				}
				//Add vertex to the local graph
				graph.addVertex(WebMap2DOffline.SelectedOfflineVertex);
				
				//Update selected vertex
				WebMap2DOffline.SelectedOfflineVertex.setId(vertexId);
			}
			else
			{
				vertexId = WebMap2DOffline.SelectedOfflineVertex.getId();
			}
			try
			{
				//Upload measurement
				//uploading measurement using OData4j:
				int measId = webClient.addMeasurement(newMeas, WebMap2DOffline.SelectedOfflineVertex);
				//Batch post of histograms (MUCH more efficient than Odata4j which doesn't support batch updates
				//Note: Uploading of histograms via Odata4j (addMeasurement(...) has been commented out as a result
				downloadMsg = BatchUpdater.updateHistograms(newMeas, measId);
				//DEBUG: Missing WifiMeasurement
				if (!downloadMsg.equalsIgnoreCase("ok"))
				{
					return exceptionID;
				}
			}
			catch (Exception ex2)
			{
				Throwable t = ex2.getCause();
				if (t != null)
					downloadMsg = t.getMessage();
				else
					downloadMsg = ex2.getMessage();
				
				return exceptionID;
			}			
			
			//Upload aps that have not yet been registered for the building
			Set<String> measMacs = newMeas.getMACs();
			ArrayList<String> newMacs = new ArrayList<String>();
			List<String> buildingMacs = currentBuilding.getPermissableAPs();
			for (String curMac : measMacs)
			{
				if (!buildingMacs.contains(curMac))
				{
					buildingMacs.add(curMac);
					newMacs.add(curMac);
				}
			}
			try
			{
				//Uploading via OData4j:
				//webClient.addBuilding_Macs(newMacs, currentBuilding);
				//Uploading via the MUCH more efficient BatchUpdate:
				if (newMacs.size() > 0)
				{
					BatchUpdater.updateBuilding_MacInfos(
							newMacs, currentBuilding.getBuildingID());
				}
			}
			catch (Exception ex3)
			{
				Throwable t = ex3.getCause();
				if (t != null)
					downloadMsg = t.getMessage();
				else
					downloadMsg = ex3.getMessage();
				
				return exceptionID;
			}
			
			return vertexId;
		}

		@Override
		protected void onPostExecute(Integer arg)
		{
			//Remove the upload progress dialog
			ProgressDialog pd = mOwner.mUploadProgressDialog;
			if (pd != null && pd.isShowing())
			{
				pd.dismiss();
			}
			
			//Check what happened and act accordingly
			int vertexId = arg;
			//1) We encountered an error
			if (vertexId == exceptionID)
			{
				Globals.createErrorDialog(mOwner, "Error", downloadMsg).show();
			}
			//2) Upload was a huge success
			else
			{	
				Toast.makeText(mOwner, "Measurement saved. Thank you!", Toast.LENGTH_SHORT).show();
				
				if (!mOwner.mIsLocationBound)
				{
					Intent intent = new Intent(BROADCAST_MEASUREMENT_UPLOADED);
					intent.putExtra(INTENT_EXTRA_IS_NEW_VERTEX, true);
					intent.putExtra(INTENT_EXTRA_VERTEX_ID, vertexId);
					mOwner.sendBroadcast(intent);
				}
				mOwner.mIsLocationBound = true;
				finish();
			}
		}
    }
		
	private static final int exceptionID = -1;	
	
	//The button to start/stop measurements:
	private Button mStartStopButton; 		
	//The button to upload measurements
	private Button mUploadButton;
	//Displays measurement progress
	private ProgressDialog mMeasProgressDialog; 
	//Dispalys upload progress
	private ProgressDialog mUploadProgressDialog;
	//Prefix used in the meas progress dialog
	private static final String SCAN_STATUS_PREFIX = "Number of scans: ";
	//service used to create measurement
	private WifiMeasurementBuilder wifiMeasurementService; 
	
	//checks whether the service is bound
	private boolean mIsWifiMeasurementServiceBound;    
	
	//The last measurement and the selected macs to keep follow:
	//The two arrays go hand in hand in representing the selected macs
	//mResultMacs keeps all registered macs 
	//while mResultChecked keeps track of which ones to keep
	//private WifiMeasurement mLastMeasurement;
	private String[] mResultMacs;
	private boolean[] mResultChecked;
	
	//Adapter for the APs of the recent measurement
	//Check boxes indicate which APs are sent to the server
	//private WifiScanAdapter mResultAdapter;
		
	//If we don't have a bound location (i.e., a vertex) we create a new one and add it to the graph
	private boolean mIsLocationBound; //if false -> add vertex to server	
	public static final String BROADCAST_MEASUREMENT_UPLOADED = "com.smartcampus.android.ui.offline.wifi.MEASUREMENT_UPLOADED";
	public static final String INTENT_EXTRA_IS_NEW_VERTEX = "IS_NEW_VERTEX";
	
	public static final String INTENT_EXTRA_VERTEX_ID = "VERTEX_ID";
	
	//This broadcast receiver updates a progress dialog with the number of scans currently taken
	private final BroadcastReceiver mNewScanReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mMeasProgressDialog.setMessage(SCAN_STATUS_PREFIX + intent.getIntExtra("ScanNumber", 0));
		}
	};
			
	//Holds a service connection to the WifiSniffer which is responsible for doing the actual measurement	
	private ServiceConnection mConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			wifiMeasurementService = ((WifiMeasurementBuilder.LocalBinder)service).getService();	
			mIsWifiMeasurementServiceBound = true;
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			//wifiMeasurementService = null;			
			mIsWifiMeasurementServiceBound = false;
		}		
	};	
	
	
	private List<String> getCheckedMacs()
	{
		List<String> result = new ArrayList<String>();
		
		if (mResultMacs != null || mResultChecked != null)
		{
			for (int i = 0; i < mResultChecked.length; i++)
			{
				if (mResultChecked[i])
					result.add(mResultMacs[i]);
			}
		}
		return result;
	}	
	
	//Returns all the macs that have been checked off (deselected)
	//These should NOT be included in the uploaded WifiMeasurement
	private List<String> getUncheckedMacs()
	{
		List<String> result = new ArrayList<String>();
		
		if (mResultMacs != null || mResultChecked != null)
		{
			for (int i = 0; i < mResultChecked.length; i++)
			{
				if (!mResultChecked[i])
					result.add(mResultMacs[i]);
			}
		}
		return result;
	}
	
	//The listview displays the APs that were heard in the most recent measurement
	private void initializeListView()
	{
		String[] emptyInfoLines = new String[0];
		setListAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_multiple_choice, emptyInfoLines));

        final ListView listView = getListView();
        listView.setItemsCanFocus(false);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setOnItemClickListener(new OnItemClickListener() {
		    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		      	
		    	mResultChecked[position] = !mResultChecked[position];
		    	/* DEBUG
		    	 * String res = getSelectedMacConcatened(getSelectedMacs());
		    	 * Toast.makeText(WifiScanForm.this, res, Toast.LENGTH_SHORT).show();
		    	 */
		    }
		  });       

	}
	
	//The progress dialog displays the number of scans taken in the current measurement
	private void initializeProgressDialog()
	{
		mMeasProgressDialog = new ProgressDialog(this);
		mMeasProgressDialog.setIndeterminate(true);
		mMeasProgressDialog.setTitle(SCAN_STATUS_PREFIX);	
		mMeasProgressDialog.setButton("Stop",  new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				WifiScanForm.this.stopInfrastructureMeasurement();
				WifiScanForm.this.stopDeviceMeasurement();				
			}			
		});
	}
	
	//The same button is used to start and stop a measurement
	//The action and text of the button depends on whether a measurement is in progress.
	private void initializeStartStopButton()
	{
		mStartStopButton.setOnClickListener(new OnClickListener()
		{
			@Override
			public void onClick(View v) {
				WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		    	if (!wifi.isWifiEnabled())
		    	{
		    		Globals.createWifiDialog(WifiScanForm.this).show();
		    	}    
		    	else
		    	{
		    		WifiScanForm.this.startInfrastructureMeasurement();
		    		WifiScanForm.this.startDeviceMeasurement();		
		    	}
			}			
		});
	}
	
	//The upload button is used to upload a new measurement (and maybe a new vertex) to the server
	//The button is only enabled when a new measurement has been taken.
	private void initializeUploadButton()
	{
		//We only enable the upload button, once we have a Wifi measurement
		this.mUploadButton.setEnabled(false);
		this.mUploadButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				saveInfrastructureMeasurement();
				saveDeviceMeasurement();					
			}						
		});
	}		
	
	//Used to bypass onCreate() on orientation changes.
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
	    super.onConfigurationChanged(newConfig);
	    /*
	    if (wifiSnifferService != null && wifiSnifferService.isMeasuring())
	    {
	    	mMeasProgressDialog.show();
	    }
	    */
	}	
	
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.offline_wifi_scan);	
		mStartStopButton = (Button)this.findViewById(R.id.start_stop_button);
		this.mUploadButton = (Button)this.findViewById(R.id.upload_button);
				
		initializeListView();	
		
		initializeStartStopButton();
		
		initializeUploadButton();	
		
		initializeProgressDialog();
		
		//get mac address for infrastructure based positioning
		if (clientMacAddress == null)
			clientMacAddress = getClientMacAddress();
		
		mIsLocationBound = this.getIntent().getExtras().getBoolean(WebMap2DOffline.IS_LOCATION_BOUND);
		//Bind WifiSniffer
		Intent bindIntent = new Intent(this, WifiMeasurementBuilder.class);
		bindService(bindIntent, mConnection, Context.BIND_AUTO_CREATE);
		
		registerReceiver(mNewScanReceiver, new IntentFilter(WifiMeasurementBuilder.NEW_SCAN));		
	}

	@Override
	protected void onListItemClick(ListView lv, View v, int position, long id)
	{		
		//app.setCurrentReview(this.reviews.get(position));
		//ReviewList.CurrentReview = this.reviews.get(position);		
	}
	
	public void onStop() {
		super.onStop();
		if (mIsWifiMeasurementServiceBound)
		{
			unbindService(mConnection);
			mIsWifiMeasurementServiceBound = false;
		}		
	}	
	
	/**
	 * Tells the sniffer backend (if present) that the client 
	 * with this mac address would like to start a sniffer measurement.
	 */
	private void startInfrastructureMeasurement()
	{
		//Get mac address
		if (clientMacAddress == null)
		{
			clientMacAddress = getClientMacAddress();
		}
		//Tell the sniffer backend
		if (clientMacAddress != null)
		{
			//notify sniffer backend that client starts measuring
			new Thread(new Runnable() 
			{			
				public void run() {					
					int buildingId = LocationService.CurrentBuilding.getBuildingID();
					int vertexId = WebMap2DOffline.SelectedOfflineVertex.getId();
					
					IInfrastructurePositioningService svc = new JsonInfrastructurePositioningClient();
					if (mIsLocationBound)
					{						
						svc.startMeasuringAtBoundLocation(clientMacAddress, buildingId, vertexId);
					}
					else
					{
						AbsoluteLocation loc = WebMap2DOffline.SelectedOfflineVertex.getLocation().getAbsoluteLocation();
						svc.startMeasuringAtUnboundLocation(clientMacAddress, buildingId, loc.getLatitude(), loc.getLongitude(), (int)loc.getAltitude()); 
					}					
			    }
			}).start();
		}
	}
	
	/**
	 * Tells the sniffer backend (if present) that the client
	 * with this mac address would like to stop a sniffer measurement.
	 */
	private void stopInfrastructureMeasurement()
	{
		//Get mac address
		if (clientMacAddress == null)
		{
			if (wifiMeasurementService != null)
				clientMacAddress = getClientMacAddress();
		}
		//Tell the sniffer backend
		if (clientMacAddress != null)
		{
			//notify sniffer backend that client starts measuring
			new Thread(new Runnable() 
			{			
				public void run() {
					IInfrastructurePositioningService svc = new JsonInfrastructurePositioningClient();
					svc.stopMeasuring(clientMacAddress);										
			    }
			}).start();
		}
	}
	
	/**
	 * Tells the sniffer backend (if present) that the client 
	 * with this mac address would like to save a conducted sniffer measurement.
	 * In order for at measurement to be saved, the sniffer infrastructure has to be in place and 
	 * this call must follow the calls StartSnifferMeasurement() and StopSnifferMeasurement()
	 */
	private void saveInfrastructureMeasurement()
	{
		//Get mac address
		if (clientMacAddress == null)
		{
			if (wifiMeasurementService != null)
				clientMacAddress = getClientMacAddress();
			//if (this.wifiSnifferService.en)
		}
		//Tell the sniffer backend
		if (clientMacAddress != null)
		{
			//notify sniffer backend that client starts measuring
			new Thread(new Runnable() 
			{			
				public void run() {
					IInfrastructurePositioningService svc = new JsonInfrastructurePositioningClient();
					svc.saveMeasurement(clientMacAddress);										
			    }
			}).start();
		}
	}
	
	private void saveDeviceMeasurement() {
		//WifiMeasurement meas = getDummyMeasurement();				
		WifiMeasurement meas = wifiMeasurementService.getLastMeasurement();
		
		//Check 1: Do we have a measurement?
		//(The upload button should only be enabled if a new measurement has been taken)
		if (meas == null) 					
		{
			Toast.makeText(WifiScanForm.this, "No available measurement", Toast.LENGTH_SHORT).show();
			return;
		}				
		//Check 2: Do we have a location?
		//(A location is required for a fingerprint)
		if (WebMap2DOffline.SelectedOfflineVertex == null)
		{
			Toast.makeText(WifiScanForm.this, "No location has been chosen", Toast.LENGTH_SHORT).show();
			return;
		}				
		//Check 3: Are there any selected access points?				
		if (!(getCheckedMacs().size() > 0))
		{
			Toast.makeText(WifiScanForm.this, "No access points are chosen", Toast.LENGTH_SHORT).show();
			return;
		}
		meas.removeMacs(getUncheckedMacs());
		
		//Upload in background
		//showMacs(meas);
		//Show Indeterminate and cancelable progress dialog
		WifiScanForm.this.mUploadProgressDialog =
			ProgressDialog.show(WifiScanForm.this, "", "Uploading measurement...", true, true);
		
		new UploadMeasurementTask(WifiScanForm.this).execute(meas);
																	
		updateUI_NoMeasurement();
	}	
	
	private void startDeviceMeasurement()
	{		
		if (wifiMeasurementService != null)
		{
			updateUI_NoMeasurement();
			
			//Show progress dialog
			mMeasProgressDialog.setMessage(SCAN_STATUS_PREFIX);
			mMeasProgressDialog.show();
			
			//start measuring
			if (wifiMeasurementService.isMeasuring()) 
				wifiMeasurementService.stopMeasuring();
			wifiMeasurementService.startMeasuring();
		}
	}
	
	private void stopDeviceMeasurement()
	{
		if (wifiMeasurementService != null)
		{
			wifiMeasurementService.stopMeasuring();
			
			WifiMeasurement scanResult = wifiMeasurementService.getLastMeasurement();
						
			updateUI_NewMeasurement(scanResult);
			//updateUI_NewMeasurement(getDummyMeasurement());
		}
	}	
	
	//1) Display scan result: SSID (if available) and mac of registered aps
	//2) Populate the list of selected macs, and
	//3) enable upload of result
	private void updateUI_NewMeasurement(WifiMeasurement scanResult) {
		String[] infoLines; //The lines to display in the list
		if (scanResult != null && scanResult.getNumMACs() > 0) 
		{
			int numMacs = scanResult.getNumMACs();
			infoLines = new String[numMacs];
			mResultMacs = new String[scanResult.getNumMACs()];
			mResultChecked = new boolean[scanResult.getNumMACs()];
			
			MacInfo tmp;
			String ssid;
			String line;
			int i = 0;
			for (String mac : scanResult.getMACs())
			{
				tmp = scanResult.getMacInfo(mac);
				ssid = tmp != null ? tmp.getSSID() : "Unknown";
				line = ssid + " (" + mac + ")";
				infoLines[i] = line;
				mResultMacs[i] = mac;
				mResultChecked[i] = true;
				i++;
			}
			
			mUploadButton.setEnabled(true);
		}
		else //no result
		{
			infoLines = new String[0];
			
			mUploadButton.setEnabled(false);
		}
		
		ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, infoLines);
				
		setListAdapter(ad);		
		
		final ListView lv = getListView();
		for (int i = 0; i < lv.getCount(); i++)
			lv.setItemChecked(i, true);
		
		ad.notifyDataSetChanged();

		//mResultAdapter.updateResults(mResult);
		//mResultAdapter.notifyDataSetChanged();
	}
	
	//Update UI to reflect the fact that there is no measurement to upload
	private void updateUI_NoMeasurement() {
		this.mUploadButton.setEnabled(false);
		String[] emptyInfoLines = new String[0];
		
		ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, emptyInfoLines);
		setListAdapter(ad);		
		ad.notifyDataSetChanged();
		
		//mResultAdapter.updateResults(mResult);
		//mResultAdapter.notifyDataSetChanged();
	}
	
	@SuppressWarnings(value = { "unused" })
	private static WifiMeasurement getDummyMeasurement() 
	{
		WifiMeasurement wm = new WifiMeasurement();
		
		String[] aps = new String[20];
		String curMac;
		for (int i = 0; i < aps.length; i++)
		{
			curMac = "AP-" + i;
			aps[i] = curMac;
			for (int j = 1; j < 20; j++)
			{
				wm.addValue(aps[i], -50 + j);			
			}
		}		
		return wm;	
	}
	
	/*	 
	private void removeUnwantedMacs(WifiMeasurement meas)
	{
		List<String> unwantedMacs = getUncheckedMacs();
		for (String mac : unwantedMacs)
		{
			meas.removeMac(mac);
		}
	}	
	
	private void showMacs(WifiMeasurement meas)
	{
		List<String> macs = new ArrayList<String>();
		for (String m : meas.getMACs())
			macs.add(m);
		Toast.makeText(this, getSelectedMacConcatened(macs), Toast.LENGTH_LONG).show();
	}
	
	private WifiMeasurement getDummyMeasurement() 
	{
		WifiMeasurement wm = new WifiMeasurement();
		
		String[] aps = new String[5];
		String curMac;
		for (int i = 0; i < aps.length; i++)
		{
			curMac = "AP-" + i;
			aps[i] = curMac;
			wm.addValue(aps[i], i);
		}
		
		return wm;
		
		WifiMeasurement wm13 = new WifiMeasurement();
		int ss13 = -62;
		for (String mac : MockGraph.getPermissableAPs())
			wm13.addValue(mac, ss13--);
		v13.addFingerprint(wm13);
	}
	
	
	private String getSelectedMacConcatened(List<String> macs)
	{
		StringBuilder sb = new StringBuilder();
    	for (String sMac : macs)
    	{
    		sb.append(sMac).append(", ");
    	}
    	return sb.toString();
	}
	*/
}


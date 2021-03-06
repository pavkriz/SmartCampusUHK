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

package com.smartcampus.android.ui.maps.online;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import com.smartcampus.R;
import com.smartcampus.android.location.LocationService;
import com.smartcampus.android.navigation.NavigationEngine;
import com.smartcampus.android.ui.AboutActivity;
import com.smartcampus.android.ui.Globals;
import com.smartcampus.android.ui.SetTrackingActivity;
import com.smartcampus.android.ui.data.VertexAdapter;
import com.smartcampus.android.ui.maps.WebMap2D;
import com.smartcampus.android.ui.maps.offline.WebMap2DOffline;
import com.smartcampus.android.ui.offline.graph.ChooseBuilding;
import com.smartcampus.android.ui.online.search.SearchDialog;
import com.smartcampus.android.ui.online.web.SymbolicLocationWebView;
import com.smartcampus.baselogic.DistanceMeasurements;
import com.smartcampus.indoormodel.AbsoluteLocation;
import com.smartcampus.indoormodel.AggregateLocation;
import com.smartcampus.indoormodel.Building;
import com.smartcampus.indoormodel.SymbolicLocation;
import com.smartcampus.indoormodel.SymbolicLocation.InfoType;
import com.smartcampus.indoormodel.graph.Edge;
import com.smartcampus.indoormodel.graph.IGraph;
import com.smartcampus.indoormodel.graph.Vertex;
import com.smartcampus.javascript.JSInterface;

public class WebMap2DOnline extends WebMap2D {
	
	private class IdentifyingBuildingReceiver extends BroadcastReceiver
	{
		@Override
		public void onReceive(Context arg0, Intent arg1) {
			if (WebMap2DOnline.displayRadiomapDownloadNotifications())
			{
				Toast.makeText(WebMap2DOnline.this, "Determining building...", Toast.LENGTH_SHORT).show();	
				//It SHOULD be registered since we are receiving this message, but i THINK
				//that it has resulted in an exception during orientationChange. 
			}
			try
			{
				unregisterReceiver(this);
			}
			catch (Exception ex) {}
		}		
	}
	
	
	//callback for when a symbolic location has been added or modified.
	//this receiver updates the graph overlay to reflect the changes. 
	//registered in manifest (maybe not necessary?)
	private class NewSearchReceiver extends BroadcastReceiver {		
		@Override
		public void onReceive(Context context, Intent intent) {
			String query = intent.getStringExtra("query");
			if (query != null)
			{
				//NOTE: AAU specifik soegning:
				createSearchResultDialog(query).show();
				//NOTE: FOLIA specifik soegning:
				//JSInterface.search(WebMap2DOnline.this.webView, query);
				WebMap2DOnline.this.setTrackingPosition(false);
			}
		}
	}
	
	
	private static final String provider_prefix = "Choose Provider";	
	private static String providerMenuText = provider_prefix;	
	private static Drawable providerDrawable;
	
	//Different positioning states we can be in
	/*
	private static final int PROVIDER_USE_GPS = 0;
	private static final int PROVIDER_USE_WIFI = 1;
	private static final int PROVIDER_USE_NONE = 2;
	private static final int PROVIDER_USE_AUTOMATIC = 3;
	*/
	public enum ProviderStatus { NONE, GPS, WIFI, AUTO }
	
	private static Bitmap isTrackingPositionIcon;
	private static Bitmap isNotTrackingPositionIcon;	
	
	private boolean mIsFirstFix = true; //tells us whether we have just started the app    
    
    private NewSearchReceiver mNewSearchReceiver; //Receives search result
	
	//Indicates the current provider
	//private int mProviderStatus = PROVIDER_USE_NONE; //We start with GPS (note: new state != old state)
	private ProviderStatus mProviderStatus = ProviderStatus.NONE;
	private synchronized void setProviderStatus(ProviderStatus status)
	{
		//No validation performed
		mProviderStatus = status;
	}
	public synchronized ProviderStatus getProviderStatus()
	{
		return mProviderStatus;
	}
	
	private Location lastKnownWifiLocation; //last registered wi-fi location (previously static)
	
	//The floor of the last known Wifi location estimate
	private static int mCurrentEstimatedFloor;
	protected static LocationService mLocationService;	
	protected static boolean mIsLocationServiceBound;
	
	private ProgressDialog mProgressDialog; //= createDownloadProgressDialog();

    private String mGpsProvider;
        
    private LocationManager mLocationManager;    
        
    //Specifies the required number of meters between two consecutive estimated locations before 
    //the most recent estimated location is shown
	private int updateThresholdInMeters;
    
	//Indicates whether we are currently tracking the position, i.e., center around location estimate
    private boolean isTrackingPosition;   	

    //Indicates whether a route is currently shown
    private boolean mIsRouteShown;

    //Used to draw routes
    private RouteOverlay routeOverlay;
    
	private static boolean displayRadiomapDownloadNotifications()
	{
		return true; //Always show
		//return !SplashScreenActivity.isShowing();
	}
		
	private static Location getBuildingLocationOrNull(Building b)
    {
    	if (b == null)
    		return null;
    	Location l = new Location("Wi-Fi");
    	l.setLatitude(b.getLatitude());
    	l.setLongitude(b.getLongitude());
    	return l;
    }
	private static String getBuildingNameOrDefault(Building b)
    {
    	return b != null && b.getName() != null
				? b.getName()
				: "<NN>";
    }
	
	/** Determines whether one Location reading is better than the current Location fix
      * @param newLocation  The new Location that you want to evaluate
      * @param currentBestLocation  The current Location fix, to which you want to compare the new one
      */
    protected static boolean isBetterLocation(Location newLocation, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }
        //RH added: The original code did not perform this check
        if (newLocation == null) {
        	return false;
        }

        // Check whether the new location fix is newer or older
        final int TWO_MINUTES = 1000 * 60 * 2;        
        long timeDelta = newLocation.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
        // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (newLocation.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(newLocation.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }
	
	/** Checks whether two providers are the same */
    private static boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
          return provider2 == null;
        }
        return provider1.equals(provider2);
    }   
    
	private final LocationListener gpsLocationListener = new LocationListener() {
    	public void onLocationChanged(Location location) {
    		boolean update = WebMap2DOnline.this.isTrackingPosition(); 
    		if (update)
    			updateNewLocation(location);
    		if (LocationService.isTrackingAllowed())
    		{
    			if (mLocationService != null)
    			{
    				mLocationService.AddToTrackedPositions(
    						LocationService.CreateTrackedPosition(
    								location, LocationService.TRACKING_PROVIDER_GPS));
    			}
            }
    	}   	

		public void onProviderDisabled(String provider){
    		//updateWithNewLocation(null);
    	}
    	
    	public void onProviderEnabled(String provider){ }
    	
    	public void onStatusChanged(String provider, int status, Bundle extras){ }
    };
	
	//Holds a service connection to the WifiLocationService which uses Wifi to infer a location estimate	
	protected ServiceConnection mLocationServiceConnection = new ServiceConnection()
	{		
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			mLocationService = ((LocationService.LocalBinder)service).getService();	
			mIsLocationServiceBound = true;
			
			// Define a listener that responds to location updates
			LocationListener locationListener = new LocationListener() {
			    public void onLocationChanged(Location location) {
			    	mCurrentEstimatedFloor = (int)location.getAltitude(); //placed here because we check floor before updating location
			    	
			    	//TODO: REMOVE - JUST USED TEMPORARILY FOR TESTING PURPOSES
			    	//Bundle b = location.getExtras();
			    	//int[] vids = b.getIntArray(LocationService.BUNDLE_ESTIMATE_VERTICES_IDS);
			    	//double[] scores = b.getDoubleArray(LocationService.BUNDLE_ESTIMATE_SCORES);
			    	
			    	//First check if we are even interested in receiving position updates?
			    	if (!WebMap2DOnline.this.isTrackingPosition())	
			    		return;
			    	
			    	//Then check whether we need to update floor
			    	//We change floor if this is the very first wifi location or the location is estimated
			    	//at a new floor
			    	boolean doChangeFloor = false;
		    		if (lastKnownWifiLocation == null) //No prior pos: We change floor			    		
		    		{
		    			doChangeFloor = true;
		    		}
		    		else //Check for new floor
		    		{
		    			int prevFloor = (int)lastKnownWifiLocation.getAltitude();
			    		if (prevFloor != mCurrentEstimatedFloor)
			    			doChangeFloor = true;		    		
		    		}
		    		
		    		if (doChangeFloor)
		    		{
		    			mCurrentSelectedFloor = mCurrentEstimatedFloor;
		    			Toast.makeText(WebMap2DOnline.this, "Changing to new floor...", Toast.LENGTH_SHORT).show();
		    			refreshUI();
		    		}
		    		
		    		lastKnownWifiLocation = location;
				    //the threshold based check for whether to update the location is conducted in updateNewLocation()
		    		//as it applies to gps and wi-fi alike
		    		updateNewLocation(location);
		    				    	
			    }

			    public void onProviderDisabled(String provider) {}			

			    public void onProviderEnabled(String provider) {}

			    public void onStatusChanged(String provider, int status, Bundle extras)
			    {
			    	if (provider.equalsIgnoreCase(LocationService.PROVIDER_NAME))
			    	{
			    		switch (status)
			    		{
			    		case LocationService.STATUS_CONNECTION_PROBLEM: 
			    			disableWifiProvider();
							if (getProviderStatus() == ProviderStatus.WIFI)
			    			{								
								if (extras != null && extras.getString("Msg") != null)
								{
									Globals.createErrorDialog(WebMap2DOnline.this, "Error", extras.getString("Msg")).show();				    			
								}
								else
								{
									Globals.createConnectionErrorDialog(WebMap2DOnline.this).show();
								}
								break;
			    			}
							
			    		case LocationService.STATUS_BUILDING_NOT_FOUND:
			    			disableWifiProvider();
		    				if (getProviderStatus() == ProviderStatus.WIFI)
			    			{			    				
				    			AlertDialog.Builder builder = new AlertDialog.Builder(WebMap2DOnline.this);
				    			builder.setTitle("Building not found");
				    			builder.setMessage("A building could not be determined.\n Please select one manually from the list of available buildings.")
			    			       .setCancelable(false)
			    			       .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
			    			           public void onClick(DialogInterface dialog, int id) {
			    			               //Map2DOnline.this.finish();
			    			        	   startActivity(new Intent(WebMap2DOnline.this, ChooseBuilding.class));
			    			           }
			    			       }
			    			    );
				    			builder.create().show();
			    			}
			    			break;
			    			
						case LocationService.STATUS_RADIOMAP_DOWNLOADED:
							boolean isShowing = mProgressDialog.isShowing();
							if (isShowing)
								mProgressDialog.dismiss();
							try
							{
								unregisterReceiver(downloadingRadiomapReceiver);
							}
							catch (IllegalArgumentException ex) {}							
							//The missing 'break' is intentional.
							
						case LocationService.STATUS_RADIOMAP_READY:
							initializeBuilding();
							mCurrentSelectedFloor = mCurrentBuilding.getInitialFloorNumber();
							mCurrentEstimatedFloor = mCurrentSelectedFloor; //until we get the first estimate
							lastKnownLocation = WebMap2DOnline.getBuildingLocationOrNull(mCurrentBuilding);							
							
							if (WebMap2DOnline.displayRadiomapDownloadNotifications())
							{
								String bName = WebMap2DOnline.getBuildingNameOrDefault(mCurrentBuilding);
								Toast.makeText(WebMap2DOnline.this, "Radio map ready for " + bName, Toast.LENGTH_SHORT).show();
							}
								
							//centerAtBuilding(); //Called when ready map is ready instead (from javascript)
							loadTiles();
														
							if (getProviderStatus() == ProviderStatus.WIFI)
							{
								doWifiPositioning();								
							}
							break;						
						}
			    	}
			    }				    			
			};
			mLocationService.addLocationListener(locationListener);	
			WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
	    	//We check to see if wifi is enabled because
			//enableIndoorPositioning turns on wifi without asking
			if (wifi.isWifiEnabled())
				mLocationService.enableIndoorPositioning();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0)
		{			
			mLocationService = null;
		}	
	};
	
	BroadcastReceiver downloadingRadiomapReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context arg0, Intent arg1) {
			if (WebMap2DOnline.displayRadiomapDownloadNotifications())
			{
				if (!mProgressDialog.isShowing())
					mProgressDialog.show();
			}
		}		
	};   
    
    @Override
    protected boolean addCurrentFloorToFloorChangerDialog()
    {
    	return true;
    }
    
    private void bindLocationService()
    {
    	Intent bindIntent = new Intent(this, LocationService.class);
		bindService(bindIntent, mLocationServiceConnection, Context.BIND_AUTO_CREATE);
		mIsLocationServiceBound = true;
    }
      
    @Override
    protected void changeToFloor(int newFloor)
	{
    	super.changeToFloor(newFloor);
		setTrackingPosition(false);
	}
    
    private void clearRoute() {
		setRouteShown(false);
		routeOverlay = null;
		JSInterface.showEdges(webView, null, getCurrentFloor());
    	//do check
		//TODO: JAVASCRIPT - ClearRotue
		/*
    	if (mGraphOverlay.get(mCurrentSelectedFloor) != null)
    	{
    		mGraphOverlay.get(mCurrentSelectedFloor).restoreDestinationItem();
    		mMapView.getOverlays().remove(mRouteOverLay);
    	
    		mRouteOverLay.stopDrawingRoute(); //= new RouteOverlay();
    		updateOverlays(mCurrentSelectedFloor); //getVisibleVertices(mGraph.getVertices(mCurrentSelectedFloor));
    		mMapView.invalidate();
    		mIsRouteShown = false;
    	} 
    	*/   	
    }     
    
    
   /**
     * Create a dialog that allow changing the positioning update interval dynamically.
     * @return AlertDialog that prompts the user for a new positioning update interval.
     */
    @SuppressLint("NewApi") //TODO: Update API
	private AlertDialog createChangeUpdateIntervalDialog()
    {
	     final EditText input = new EditText(this);
		 int intvl = LocationService.getWifiPositioningUpdateInterval();
		 input.setText(Integer.toString(intvl));
		 input.setInputType(InputType.TYPE_CLASS_NUMBER);
	   
		 AlertDialog.Builder res = new AlertDialog.Builder(this)
		 	.setTitle("Change Positioning Update Interval")
		 	.setMessage("Set new interval in milliseconds:")
		 	.setView(input)
		 	.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
		       public void onClick(DialogInterface dialog, int whichButton) {
		           int newIntvl = Integer.parseInt(input.getText().toString());
		           if (newIntvl < 500)
		        	   Toast.makeText(WebMap2DOnline.this, "The smallest allowed value is 500 ms.", Toast.LENGTH_SHORT).show();
		           else
		           {
		        	   LocationService.setWifiPositioningUpdateInterval(newIntvl);
		        	   Toast.makeText(WebMap2DOnline.this, "Update Interval changed to " + newIntvl + " ms.", Toast.LENGTH_SHORT).show();
		           }
		       }
		   })
		   .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
		       public void onClick(DialogInterface dialog, int whichButton) {
		           //do nothing
		       }
		   });
		 return res.create();
    } 
    
    /**
     * Create a dialog that allow changing the positioning update threshold dynamically.
     * @return AlertDialog that prompts the user for a new positioning update threshold.
     */
    @SuppressLint("NewApi") //TODO: Update to new API
	private AlertDialog createChangeUpdateThresholdDialog()
    {
	     final EditText input = new EditText(this);
		 input.setText(Integer.toString(getUpdateThresholdInMeters()));
		 input.setInputType(InputType.TYPE_CLASS_NUMBER);
	   
		 AlertDialog.Builder res = new AlertDialog.Builder(this)
		 	.setTitle("Change Positioning Update Threshold")
		 	.setMessage("Set new threshold in meters: \n (0 meters means 'Show every position update')")
		 	.setView(input)
		 	.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
		       public void onClick(DialogInterface dialog, int whichButton) {
		           int newThreshold = Integer.parseInt(input.getText().toString());
		           
		           WebMap2DOnline.this.setUpdateThresholdInMeters(newThreshold < 0 ? 0 : newThreshold);
		           Toast.makeText(WebMap2DOnline.this,
		        		   "Update Threshold changed to " + WebMap2DOnline.this.getUpdateThresholdInMeters() + " meters.", Toast.LENGTH_SHORT).show();
		           
		       }
		   })
		   .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
		       public void onClick(DialogInterface dialog, int whichButton) {
		           //do nothing
		       }
		   });
		 return res.create();
    }
    
    private AlertDialog createChooseProviderDialog() {
    	//NOTE: It is imperative that the choice order corresponds to the values
    	//consider using enum instead
    	//final CharSequence[] items = {"Gps", "Wi-Fi", "None", "Auto"}; //"Auto" 
    	final CharSequence[] items = new CharSequence[4];
    	items[ProviderStatus.NONE.ordinal()] = "None";
    	items[ProviderStatus.GPS.ordinal()] = "Gps";
    	items[ProviderStatus.WIFI.ordinal()] = "Wi-Fi"; 
    	items[ProviderStatus.AUTO.ordinal()] = "Auto";  

    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	    			
    	builder.setTitle("Choose a provider");
		
    	builder.setSingleChoiceItems(items, getProviderStatus().ordinal(), new DialogInterface.OnClickListener() {
    	    public void onClick(DialogInterface dialog, int item) {
    	    	ProviderStatus status = ProviderStatus.values()[item];
    	    	WebMap2DOnline.this.setProvider(status); //(item);
    	    	dialog.dismiss();
    	    }   	    
    	});
    	 
    	return builder.create();		
    }
    
    private ProgressDialog createDownloadProgressDialog()
	{
		ProgressDialog res = new ProgressDialog(this);
		res.setIndeterminate(true);
		res.setCancelable(true);
		res.setMessage("Downloading radio map...");
		return res;
	}
    
    public AlertDialog createFindNearestDialog()
    {
    	//BEWARE!! (length -1, because not interested in 'None' things (cf. below)
    	//Number of items must match number of (non-null) entries or Android will throw a nullRef
    	//exception on null values.
    	int numTypes = SymbolicLocation.InfoType.values().length - 1;
    	String[] items = new String[numTypes];
    	int i = 0; //NOTE: The first icon is 'None' - which we are not interested in. Therefore, we start at the second element
    	for (SymbolicLocation.InfoType type : SymbolicLocation.InfoType.values())
    	{
    		//Not interested in find 'None' things
    		if (type == InfoType.NONE)
    			continue;
    		items[i++] = SymbolicLocation.InfoType.prettyPrint(type);
    	}
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Find Nearest");
		builder.setItems(items, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dialog, int item) {
		        SymbolicLocation.InfoType sl = SymbolicLocation.InfoType.getValue(item);
		        Toast.makeText(WebMap2DOnline.this, SymbolicLocation.InfoType.prettyPrint(sl), Toast.LENGTH_SHORT).show();
		        WebMap2DOnline.this.setTrackingPosition(false);
		    }
		});
		return builder.create();		
    }
  
   
	//Create the 'What's nearby' dialog
    public AlertDialog createSearchResultDialog(String query) {
    	final List<Vertex> res = getVerticesFromSearch(query);    		
        AlertDialog.Builder resultBuilder = new AlertDialog.Builder(this);
        resultBuilder.setTitle("Search result");
        
    	resultBuilder.setAdapter(new VertexAdapter(this, res), new android.content.DialogInterface.OnClickListener() {
			
			//@Override
			public void onClick(DialogInterface dialog, int which) {
    			Vertex selectedVertex = res.get(which);
    			
				AbsoluteLocation absLoc = selectedVertex.getLocation().getAbsoluteLocation();
				JSInterface.centerAt(webView, absLoc.getLatitude(), absLoc.getLongitude());				
				WebMap2DOnline.this.setTrackingPosition(false);
			}			
		});

    	return resultBuilder.create();
    }
  	  
    private void disableGpsProvider()
    {
    	if (mLocationManager != null && gpsLocationListener != null)
    		mLocationManager.removeUpdates(gpsLocationListener);
    }
    
    private void disableProvider(ProviderStatus oldStatus) {
		switch (oldStatus)
		{
		case GPS: 
			disableGpsProvider();
			break;
		case WIFI:
			disableWifiProvider();
			break;
		case AUTO:
			disableGpsProvider();
			disableWifiProvider();
			break;
		default:
			break;
		}		
	}
    private void disableWifiProvider()
    {
    	if (mLocationService != null && mLocationService.isDoingWifiPositioning())
    	{
    		mLocationService.stopWifiPositioning();    
    	}
    }
    
    //Consider refactoring, so we have one place for initializing wifi positioning
	private void doWifiPositioning() {
		if (mGraph != null && getProviderStatus() == ProviderStatus.WIFI)
		{
			mLocationService.startWifiPositioning();								
		}
	}
    
    private boolean enableGpsProvider()
	{
    	if (mLocationManager == null)
			mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
    	if (mLocationManager == null)
    	{
    		Globals.createErrorDialog(this, "No Location Service found", "No location service was found, so Gps positioning is not available");
    		return false;
    	}
    	else 
		{
    		boolean gpsEnabled = mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    	    boolean networkEnabled = mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    	    
    	    //Yes, our 'gps' simply refers to the in-built location service whether gps or network-based
    	    if (!(gpsEnabled || networkEnabled))
    	    {
    	    	Globals.createGpsDialog(this).show();
    	    	//showGpsDialog();
    	    	return false;
    	    }
		}
		
		boolean providerEnabled = initializeGps();
		if (providerEnabled)
		{
			int updateIntvl = LocationService.getWifiPositioningUpdateInterval();
			updateIntvl = updateIntvl >= 0 ? updateIntvl : 0;
			int updateThreshold = this.getUpdateThresholdInMeters();
			updateThreshold = updateThreshold >= 0 ? updateThreshold : 0;
			mLocationManager.requestLocationUpdates(mGpsProvider, updateIntvl, updateThreshold, gpsLocationListener);
		}
		return providerEnabled;
	}
    
    private boolean enableWifiProvider()
	{
    	WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    	if (!wifi.isWifiEnabled())
    	{
    		Globals.createWifiDialog(this).show();
    		return false;
    	}    	
    	else if (mLocationService == null)
		{
			//calls enableIndoorPositioning upon serviceConnected()
			bindLocationService();
			return false;
		}
		else
		{
			//Receive a broadcast about identifying correct building
			IntentFilter idFilter = new IntentFilter(LocationService.PROGRESS_STATUS_DETERMINING_BUILDING);
			BroadcastReceiver idReceiver = new IdentifyingBuildingReceiver();
			registerReceiver(idReceiver, idFilter); //unregisters itself in onReceive()
			
			//Receive a broadcast about radio download - then show progressdialog
			IntentFilter downloadFilter = new IntentFilter(LocationService.PROGRESS_STATUS_DOWNLOADING_RADIOMAP);
			registerReceiver(downloadingRadiomapReceiver, downloadFilter); //unregister upon download complete
			
			//Let's go Joe
			mLocationService.enableIndoorPositioning();
			return true;
		}
	}
    
    //In online mode we estimate the current floor
    @Override
    public int getCurrentFloor()
    {
    	return mCurrentEstimatedFloor;
    }
    
    /**
     * @return The estimated location
     */
    public AggregateLocation getEstimatedLocation() {
		if (lastKnownLocation != null)
		{
			AbsoluteLocation absLoc = 
				new AbsoluteLocation(
						lastKnownLocation.getLatitude(),
						lastKnownLocation.getLongitude(),
						lastKnownLocation.getAltitude());
			return new AggregateLocation(absLoc);
		}
		else
			return null;		
	}      
    
    /**
	 * @return The update threshold in meters
	 */
	private synchronized int getUpdateThresholdInMeters() {
		return updateThresholdInMeters;
	}
        
    /**
     * Find all vertices whose title or description contains the query string.
     * @param query Specifies the query string
     * @return All vertices where the title or description contains the query string.
     */
    public List<Vertex> getVerticesFromSearch(String query)
	{	
		//Do we have a building?
		Building b = LocationService.CurrentBuilding;
		if (b == null)
			return null;
		
		//Does the building has a graph?
		IGraph g = b.getGraphModel();
		if (g == null)
			return null;
		
		//Does the graph have any vertices?
		Collection<Vertex> vertices = g.getVertices();
		if (vertices == null)
			return null;
	
		//We've got vertices - now find the appropriate ones
		//i.e., the symbolicLocation's title or description matches the query
		List<Vertex> result = new ArrayList<Vertex>();
		SymbolicLocation symLoc;
		for (Vertex v : vertices)
		{
			symLoc = v.getLocation().getSymbolicLocation();
			if (symLoc != null)
			{
				if (symLoc.getTitle().contains(query) || symLoc.getDescription().contains(query))
					result.add(v);
			}
		}
		return result;		
	}
    
    @Override
    protected List<Edge> getVisibleEdges(int floorNum)
    {
    	List<Edge> result = null;
    	if (isRouteShown() && routeOverlay != null)
    		result = routeOverlay.getEdges(floorNum);
    	return result;
    }
	
    @Override
    protected List<Vertex> getVisibleVertices(List<Vertex> vertices)
    {
    	if (vertices == null)
    		return null;
    	
    	List<Vertex> visibleVertices = new ArrayList<Vertex>();
    	for (Vertex v : vertices)
    	{
    		if (isVisibleVertex(v))
    		{
    			visibleVertices.add(v);
    		}
    	}
    	return visibleVertices;
    }
    
    /**
	 * This is called when a radio map becomes available. 
	 * We update the current building and graph
	 */
	private void initializeBuilding()
    {       
        mCurrentBuilding = LocationService.CurrentBuilding;
        if (mCurrentBuilding != null)
        	mGraph = mCurrentBuilding.getGraphModel();    
    }
    
    private boolean initializeGps() {
    	String context = Context.LOCATION_SERVICE;
		mLocationManager = (LocationManager)getSystemService(context);
		
		Criteria criteria = new Criteria();
		criteria.setAccuracy(Criteria.ACCURACY_FINE);
		criteria.setAltitudeRequired(false);
		criteria.setBearingRequired(false);
		criteria.setCostAllowed(true);
		criteria.setPowerRequirement(Criteria.POWER_LOW);
		mGpsProvider = mLocationManager.getBestProvider(criteria, true); //Implicit provider
		if (mGpsProvider == null)
			return false;
		
		Location newLocation = null;
		try
		{
			//If the provider is currently disabled, null is returned.
			newLocation = mLocationManager.getLastKnownLocation(mGpsProvider);
		}
		catch (IllegalArgumentException iae) //if provider is null or doesn't exist
		{
			return false;
		}
		if (newLocation == null)
		{
			return false;
		}
		if (isBetterLocation(newLocation, lastKnownLocation))
		{
			lastKnownLocation = newLocation;
		}
		return true;
	}    
    
    private boolean isLocationUrlWellFormed(String url) {
		return url != null && (url.startsWith("http://") || url.startsWith("www."));
	}
        
    @Override
    protected boolean isOnline() { return true; }
    public boolean isRouteShown()
    {
    	return mIsRouteShown;
    }
    private synchronized boolean isTrackingPosition()
    {
    	return isTrackingPosition;
    }
    
    //Only show locations with 'interesting' info
    //This is either symbolic info or navigational properties
    protected boolean isVisibleVertex(Vertex v)
	{
    	if (v == null)
    		return false;
    	else
    	{
    		boolean hasSymbolicInfo = v.getLocation().getSymbolicLocation() != null;
    		boolean isFloorChanger = v.isElevatorEndpoint() || v.isStairEndpoint();
    		return hasSymbolicInfo || isFloorChanger;
    	}
	}
    
    public boolean isWifiPositioningEnabled()
    {
    	return mGraph != null;
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    	super.onConfigurationChanged(newConfig);    	
    }
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (isTrackingPositionIcon == null)
        	isTrackingPositionIcon = BitmapFactory.decodeResource(getResources(), R.drawable.foliapin); //tracking_on
        if (isNotTrackingPositionIcon == null)
        	isNotTrackingPositionIcon = BitmapFactory.decodeResource(getResources(), R.drawable.tracking_off);    		
        
        mTrackPositionBtn = (ImageButton)findViewById(R.id.trackPositionButton);
        mTrackPositionBtn.setOnClickListener(new OnClickListener() {			
			@Override
			public void onClick(View v) {
				if (WebMap2DOnline.this.getProviderStatus() == ProviderStatus.NONE)
				{
					Globals.createErrorDialog(
							WebMap2DOnline.this,
							"No Provider Selected",
							"You need to select a provider in order to follow the current position").show();
					return;
				}
				
				if (mGraph != null)
		        {
					setTrackingPosition(!isTrackingPosition()); //toggle button
					if (mCurrentSelectedFloor != getCurrentFloor())
					{
						mCurrentSelectedFloor = getCurrentFloor();
						updateOverlays(mCurrentSelectedFloor);
						setTitle(concatBuildingAndFloorName(mCurrentSelectedFloor));
					}
					//NYT
					if (lastKnownLocation != null)
					{
						JSInterface.centerAt(webView, lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
					}
		        }					
			}
		});
        setTrackingPosition(true);
        
        webView.setOnTouchListener(new OnTouchListener() {			
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				int action = event.getAction();
				if (action == MotionEvent.ACTION_MOVE)
					WebMap2DOnline.this.setTrackingPosition(false);
								
				return false;
			}
		});
        
        mProgressDialog = createDownloadProgressDialog();
        bindLocationService(); 
        if (mIsFirstFix)
        {
        	setProvider(ProviderStatus.NONE);  //PROVIDER_USE_GPS
        	mIsFirstFix = false;
        }
        
        //enable type-to-search
        this.setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);
        IntentFilter newSearchFilter = new IntentFilter(SearchDialog.NEW_SEARCH);
		mNewSearchReceiver = new NewSearchReceiver();
		registerReceiver(mNewSearchReceiver, newSearchFilter);		
    }
        
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.mapd2d_online_menu, menu);
		
		return super.onCreateOptionsMenu(menu);
	}
	
	@Override
    protected void onDestroy()
    {
    	super.onDestroy();
    	disableGpsProvider();
    	disableWifiProvider();
    	unbindLocationService();
    	
    	try
    	{
    		unregisterReceiver(mNewSearchReceiver);
    	}
    	catch (Exception ex) {}
    };
    
	@Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	switch (item.getItemId()) {
    	case R.id.provider_choice_online:
    		AlertDialog chooseProvider = createChooseProviderDialog();
    		chooseProvider.show();
    		return true;
        case R.id.floor_changer_online:
        	AlertDialog alert = createFloorChangerDialog();
        	alert.show();
            return true;        
        case R.id.what_is_here_online:
        	AlertDialog nearbyPlacesDialog = createWhatsNearbyDialog();
        	nearbyPlacesDialog.show();
            return true;
            /*
        case R.id.find_nearest:
        	createFindNearestDialog().show();
        	return true;
        	*/
        case R.id.search_online:
        	onSearchRequested();
        	return true;
        /*
        case R.id.change_view_online:
        	AlertDialog changeViewDialog = createViewDialog();
        	changeViewDialog.show();
        	return true;  
        */  
        /*	
        case R.id.change_update_interval_online:
        	 createChangeUpdateIntervalDialog().show();
        	 return true;                	
        case R.id.change_update_threshold_online:
        	createChangeUpdateThresholdDialog().show();
       	 	return true;
        */
        case R.id.clear_route:
        	clearRoute();
        	return true;
        case R.id.set_stracking_screen: 
        	startActivity(new Intent(WebMap2DOnline.this, SetTrackingActivity.class));
        	return true;        
        case R.id.offline_mode:
        	//removes routes and disable positioning when we go into offline mode
        	clearRoute();
        	setProvider(ProviderStatus.NONE);
        	startActivity(new Intent(WebMap2DOnline.this, WebMap2DOffline.class));            
            return true;
        case R.id.about_screen: 
        	startActivity(new Intent(WebMap2DOnline.this, AboutActivity.class));
        	return true;
        //NOTE: This backdoor only applies to trusted associates - certainly not the public version
        /*
        case R.id.menu_online_backdoor: 
        	ChooseBuilding.IS_BACKDOOR_ENABLED = true;
        	startActivity(new Intent(WebMap2DOnline.this, ChooseBuilding.class));
            return true;
        */            	
    	}
        return super.onMenuItemSelected(featureId, item);
    }
	
    /*
     * When onPause is called, e.g. when the activity
     * loses focus we want the userlocation overlay to stop
     * updating. Among others this preserves battery since the
     * GPS is turned off.
     * 
     */
    @Override
    public void onPause() {
        super.onPause();       
   }
    	
    @Override
	public boolean onPrepareOptionsMenu(Menu menu)
	{
		MenuItem provider = menu.findItem(R.id.provider_choice_online);
		provider.setTitle(providerMenuText);
		provider.setIcon(providerDrawable);
		
		boolean isRadiomapReady = mGraph != null;
		MenuItem miFloorChanger = menu.findItem(R.id.floor_changer_online);
		MenuItem whatsHere 		= menu.findItem(R.id.what_is_here_online);
		//Commented out for Google IO:
		//MenuItem findNearest 	= menu.findItem(R.id.find_nearest);
		MenuItem search 		= menu.findItem(R.id.search_online);
		MenuItem route 			= menu.findItem(R.id.clear_route);
		MenuItem offlineMode 	= menu.findItem(R.id.offline_mode);
		
		whatsHere.setEnabled(isRadiomapReady);
	    miFloorChanger.setEnabled(isRadiomapReady);
	    //Commented out for Google IO:
	    //findNearest.setEnabled(isRadiomapReady);
	    search.setEnabled(isRadiomapReady);
	    offlineMode.setEnabled(isRadiomapReady);
	    route.setEnabled(isRadiomapReady && isRouteShown()); //false
	    
		return super.onPrepareOptionsMenu(menu);
	}
    
    @Override
	public void onRestart() 
	{
		super.onRestart();
		if (WebMap2DOffline.hasGraphChanged())
		{
			refreshUI();
		}
	}
    
    @Override
    public void onResume() {
        super.onResume();
        mTrackPositionBtn.setVisibility(View.VISIBLE);
    }   
    
    @Override
    public void onTap(int floorNum, int vertexId) {
    	final int GET_DIRECTION = 0;
		final int OPEN_URL = 1;
		final String GET_DIRECTION_TEXT = "Get Directions";
		final String OPEN_URL_TEXT = "Open URL";
		
		final Vertex v = mGraph.getVertexById(vertexId);	
		final SymbolicLocation symLoc = v.getLocation().getSymbolicLocation();
		
		final String[] items;
		
		if (symLoc == null || (!isLocationUrlWellFormed(symLoc.getUrl())))
		{
			items = new String[1]; //URL is not applicable
			items[GET_DIRECTION] = GET_DIRECTION_TEXT;
		}
		else { //url is well-formed
			items = new String[2];
			items[GET_DIRECTION] = GET_DIRECTION_TEXT;
			items[OPEN_URL] = OPEN_URL_TEXT;
		}
		
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Choose an action");
		builder.setItems(items, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dialog, int item) {
		        switch (item) {
				case GET_DIRECTION:
					WebMap2DOnline.this.visualiseDirections(v);
					break;
				case OPEN_URL:
					Intent webIntent = new Intent(WebMap2DOnline.this, SymbolicLocationWebView.class);					
					webIntent.putExtra(Globals.URL_EXTRA, symLoc.getUrl());					
					WebMap2DOnline.this.startActivity(webIntent);
					break;
				default:
					break;
				}
		    }
		});		
		
		AlertDialog alert = builder.create();
		alert.show();
    }    
	
    public void setProvider(ProviderStatus newProviderStatus)//(int newProviderStatus)
	{
		if (newProviderStatus != getProviderStatus() || mIsFirstFix)
    	{
			String providerText = "";
			final String providerTextGps = "Gps";
			final String providerTextWifi = "Wi-Fi";
			final String providerTextNone = "None";
			final String providerTextAuto = "Auto";
						
			ProviderStatus oldStatus = getProviderStatus();
    		
    		boolean providerEnabled = false;
    		
    		//(try to) enable new appropriate provider
    		switch (newProviderStatus)
    		{
    		case GPS: 
    			try
    			{
    				providerEnabled = enableGpsProvider();
	    			if (providerEnabled)
	    			{
	    				providerText = providerTextGps;
    					providerDrawable = getResources().getDrawable(R.drawable.ic_menu_provider_gps);
	    			}
    			}
    			catch (Exception ex)
    			{
    				Globals.createExceptionDialog(this, ex).show();
    			}
    			break;
    		case WIFI:
    			providerEnabled = enableWifiProvider();
    			if (providerEnabled)
    			{
    				providerText = providerTextWifi;
    				providerDrawable = getResources().getDrawable(R.drawable.ic_menu_provider_wifi);
    			}    			
    			break;
    		case NONE: 
    			providerText = providerTextNone;
    			providerDrawable = getResources().getDrawable(R.drawable.ic_menu_provider_none);
    			break;  
    		case AUTO:
    			//maybe do it in steps?
    			boolean gpsEnabled = enableGpsProvider();
    			boolean wifiEnabled = enableWifiProvider();
    			providerEnabled = gpsEnabled && wifiEnabled;
    			if (providerEnabled)
    			{
    				providerText = providerTextAuto; 
    				providerDrawable = getResources().getDrawable(R.drawable.ic_menu_provider_auto);
    			}
    			break;    			
    		}
    		
    		if (providerEnabled || newProviderStatus == ProviderStatus.NONE)
			{
    			setProviderStatus(newProviderStatus);
				//In auto-mode we use everything, so don't disable the old one
    			if (newProviderStatus != ProviderStatus.AUTO)
    				disableProvider(oldStatus);
				
				String toastMsg;
				if (newProviderStatus == ProviderStatus.NONE)
				{
					setTrackingPosition(false);
					toastMsg = "Disabling providers";
				}
				else
				{
					setTrackingPosition(true);
					toastMsg = "Switching to " + providerText;
				}
				
        		if (!mIsFirstFix)
        			Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        		providerMenuText = provider_prefix + " (" + providerText + ")";
			}   		
    	}
	}
    
    public void setRouteShown(boolean isRouteShown)
	{
		this.mIsRouteShown = isRouteShown;
	}
	
	/**
	 * This method is called (from javascript) whenever the user taps on the map - not a marker. 
	 * This is only used in the offline phase and is therefore ignored here.  
	 * @param isOnline Indicates whether we are in the online phase
	 * @param floor the current floor
	 * @param lat the latitude of the tapped location
	 * @param lon the longitude of the tapped location
	 */
	@Override
	public void setSelectedLocation(boolean isOnline, int floor, double lat, double lon) {
		return;
	}
	
	private synchronized void setTrackingPosition(boolean isTracking)
    {
    	this.isTrackingPosition = isTracking;
    	
    	//JSInterface.setIsTracking(webView, isTracking);
    	
    	if (isTrackingPosition)
    		mTrackPositionBtn.setImageBitmap(isTrackingPositionIcon);
    	else
    		mTrackPositionBtn.setImageBitmap(isNotTrackingPositionIcon);
    	
    	//other option: android:background="@null" in web_map.xml
    	//mTrackPositionBtn.setAlpha(127); //not necessary to make button semi-transparent
     }
    
    /**
	 * @param updateThresholdInMeters the updateThresholdInMeters to set
	 */
	private synchronized void setUpdateThresholdInMeters(int updateThresholdInMeters) {
		this.updateThresholdInMeters = updateThresholdInMeters;
	}
    
    /**
	 * Indicates whether we desire to show the edges of the graph.
	 * @return We do not want to show the edges in the online phase
	 */
	protected boolean showEdges()
	{
		return isRouteShown();
		//return false; 
	}
    
    @Override
	protected void subclassHook_WhatsNearbyDialog()
	{
		this.setTrackingPosition(false);
	}

	private void unbindLocationService()
    {
    	if (mIsLocationServiceBound) {
            unbindService(mLocationServiceConnection);
            mIsLocationServiceBound = false;
        }
    }

	//Denotes the last location that was shown to the user
	private Location lastUpdatedLocation;
	
	private void updateNewLocation(Location location) {    	
    	double distToPrev = -1; //placed here so I can see its value throughoutu the mehthod (for debugging)
		
    	if (location != null) {
    		//STEP 1:
    		//If we are using more than more provider we first determine if the new estimate is better than the current
    		//In the case of a single provider, we proceedthis step is not the deal-breaker
    		boolean proceed = true;
    		if (getProviderStatus() != ProviderStatus.AUTO)
    		{
    			lastKnownLocation = location;
    		}
    		else 
    		{
    			if (isBetterLocation(location, lastKnownLocation))
    			{
    				lastKnownLocation = location;
    			}
    			else
    			{
    				proceed = false;
    			}
    		}
    		if (!proceed)
    			return;
    		
    		//STEP 2: We check if we want to SHOW the location, based on whether it is more
    		//than the specified threshold away from the previous location estimate
    		boolean showNewLocation = true;
    		if (lastUpdatedLocation != null)
    		{
	    		//ekstra tjek - paa threshold    		
	    		int updateThreshold = this.getUpdateThresholdInMeters();
	    		double oldLat = lastUpdatedLocation.getLatitude();
	    		double oldLng = lastUpdatedLocation.getLongitude();
	    		double newLat = location.getLatitude();
	    		double newLng = location.getLongitude();
	    		distToPrev = DistanceMeasurements.CalculateMoveddistanceInMeters(oldLat, oldLng, newLat, newLng);
	    		
	    		if (distToPrev < updateThreshold)
	    		{
	    			showNewLocation = false;
	    		}	    		
    		}
    		if (showNewLocation)
    		{
				Calendar c = Calendar.getInstance();
				SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
				String strTime = sdf.format(c.getTime());

				JSInterface.updateNewLocation(webView, location);
				setTitle(concatBuildingAndFloorName(mCurrentSelectedFloor) + ", location updated at " + strTime);
				lastUpdatedLocation = new Location(location); //NB: Important to COPY as done here. 
    		}    		
		}
	}
	
	public void visualiseDirections(Vertex destinationVertex) {
    	try
    	{
    		AggregateLocation sourceLoc = getEstimatedLocation();
	    	if (sourceLoc != null && destinationVertex != null && mGraph != null)
	    	{
		    	AbsoluteLocation userLoc = sourceLoc.getAbsoluteLocation();
		    	LinkedList<AbsoluteLocation> route = new NavigationEngine().getRoute(mGraph, userLoc, destinationVertex);
				routeOverlay = new RouteOverlay(route);
				setRouteShown(true);
				JSInterface.showEdges(webView, routeOverlay.getEdges(mCurrentSelectedFloor), getCurrentFloor());
	    	}
    	}
    	catch (Exception ex)
    	{
    		Toast.makeText(this, "A route could not be found", Toast.LENGTH_SHORT).show();
    	}    	
    }	
}

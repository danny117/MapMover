package com.gosylvester.maps;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.GpsStatus.Listener;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.CancelableCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;

public class MapMover

implements CancelableCallback, GooglePlayServicesClient.ConnectionCallbacks,
		OnConnectionFailedListener,
		com.google.android.gms.location.LocationListener, Listener {

	private MapMoverCallbacks mapMoverCallbacks;

	private static final double PI = Math.PI;
	private static final double PI2 = 2 * PI;
	private static final double PI3 = 3 * PI;
	private double expectedDistanceMultiplier;
	
	private static CameraPosition currentCameraPosition;
	private static com.google.android.gms.maps.model.CameraPosition.Builder cameraPositionBuilder;
	private volatile CameraUpdate nextCameraUpdate;

	private volatile boolean isAnimating;

	private int interval = 5000;

	private GoogleMap mMap;

	private LocationManager locationManager;
	private LocationClient locationClient;
	private LocationRequest locationRequest;

	private DecimalFormat decimalFormat;

	private Handler speedHandler;
	private double actualSpeed = 0;
	AtomicBoolean hasNewSpeed;

	/*
	 * this is the callback interface
	 */
	public interface MapMoverCallbacks {
		/*
		 * onSatLockChange is called when there is a change in the satellite fix
		 * display the string to the user or use the integer at the end of the
		 * string for the number of satellites used in the positionfix
		 */
		void onSatLockChange(String satLockStatus);

		/*
		 * this is called when the connection to location services fails
		 */
		void onMapMoverConnectionFailed(ConnectionResult connectionResult);

		/*
		 * this is called when there is a new speed to display
		 */
		void onSpeedChange(String displaySpeed);
	}

	// methods for the client to use

	/*
	 * initialize the map mover
	 */

	public MapMover(Activity activity, GoogleMap googleMap) {
		mapMoverCallbacks = (MapMoverCallbacks.class.isAssignableFrom(activity
				.getClass())) ? (MapMoverCallbacks) activity : null;
		locationManager = (LocationManager) activity
				.getSystemService(Context.LOCATION_SERVICE);

		mMap = googleMap;
		locationClient = new LocationClient(activity, this, this);
		cameraPositionBuilder = CameraPosition.builder();
		decimalFormat = new DecimalFormat("##0.0");
		speedHandler = new Handler();
		hasNewSpeed = new AtomicBoolean(false);
	}

	private boolean isMPH = true;
	private boolean isSpeed = true;

	private boolean isHeadingUp = true;

	/**
	 * @return the isHeadingUp
	 */
	public boolean isHeadingUp() {
		return isHeadingUp;
	}

	/**
	 * @param isHeadingUp
	 *            true the map moves heading up false the map moves with north
	 *            at the top
	 */
	public void setHeadingUp(boolean isHeadingUp) {
		this.isHeadingUp = isHeadingUp;
	}

	private boolean action_track = false;

	/**
	 * @param action_track
	 *            the action_tracker to set Set to true and it starts receiving
	 *            gps and moving map set to false and it stops receiving gps.
	 */
	public void setAction_track(boolean action_track) {
		this.action_track = action_track;
		// if on start location updates by requesting a connection
		if (action_track) {
			setSatLockStatus("");
			isAnimating = false;
			locationClient.connect();
			locationManager.addGpsStatusListener(this);
		}
		// stop location updates
		else {
			locationManager.removeGpsStatusListener(this);
			if (locationClient.isConnected()) {
				locationClient.removeLocationUpdates(this);
			}
			setSatLockStatus("");
			speedHandler.removeCallbacks(updateSpeedThread);
		}
	}

	/**
	 * @return the action_track
	 */
	public boolean isActionTrack() {
		return action_track;
	}

	private String satLockStatus = "";

	// meters per second
	private double newSpeed;

	private int processedLocations;

	private long locationInterval;

	/**
	 * @return the gps satellite status
	 */
	public String getSatLockStatus() {
		return satLockStatus;
	}

	/*
	 * Called by Location Services when the request to connect the client
	 * finishes successfully. At this point, you can request the current
	 * location or start periodic updates
	 */
	@Override
	public void onConnected(Bundle dataBundle) {
		// Create a new global location parameters object
		locationRequest = LocationRequest.create();
		// Set the update interval 70% of the interval
		// this is to make sure we have an updated location
		// when the animation completes
		locationInterval = (long) (interval * .70);
		locationRequest.setInterval(locationInterval);
		locationRequest.setFastestInterval(locationInterval);
		locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
		locationClient.requestLocationUpdates(locationRequest, this);
	}

	/*
	 * once connected and a request for location updates is received this method
	 * is called with periodic updates it starts a task that creates a camera
	 * update that moves the map.
	 */
	@SuppressLint("NewApi")
	@Override
	public void onLocationChanged(Location location) {
		Log.d("test", Boolean.toString(isAnimating) + " onlocation");
		currentCameraPosition = mMap.getCameraPosition();

		NewCameraUpdateTask newCameraUpdateTask = new NewCameraUpdateTask();

		// The nextcameraupdate task must run immediately
		// it can't wait for less important tasks to finish
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			newCameraUpdateTask.executeOnExecutor(
					AsyncTask.THREAD_POOL_EXECUTOR, location);
		} else {
			newCameraUpdateTask.execute(location);
		}

		// stop the speed handler
		speedHandler.removeCallbacks(updateSpeedThread);

		if (isSpeed) {
			newSpeed = location.hasSpeed() ? location.getSpeed() : 0f;
			hasNewSpeed.getAndSet(true);
			speedHandler.post(updateSpeedThread);
		} else {
			setDisplaySpeed("");
		}

		// this is glue for when isAnimating gets out of sync
		processedLocations += 1;
		if (processedLocations > 2) {
			isAnimating = false;
			processedLocations = 0;
		}
	}

	@Override
	public void onCancel() {
		Log.d("test", Boolean.toString(isAnimating) + " oncancel");
		isAnimating = false;
		processedLocations = 0;
	}

	/*
	 * called when the map animation completes successfully (non-Javadoc)
	 * 
	 * @see com.google.android.gms.maps.GoogleMap.CancelableCallback#onFinish()
	 */
	@Override
	public void onFinish() {
		Log.d("test", Boolean.toString(isAnimating) + " onfinish");
		isAnimating = false;
		// call to start saved animation.
		startAnimation();
		processedLocations = 0;
	}

	/* called when the gpsstatus changes */
	@Override
	public void onGpsStatusChanged(int event) {
		switch (event) {
		case android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS:
			satlock();
			break;
		case android.location.GpsStatus.GPS_EVENT_FIRST_FIX:
			satlock();
			break;
		case android.location.GpsStatus.GPS_EVENT_STARTED:
			satlock();
			break;
		case android.location.GpsStatus.GPS_EVENT_STOPPED:
			satlock();
			break;
		}
	}

	/*
	 * Called by Location Services if the attempt to Location Services fails.
	 */
	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		if (this.mapMoverCallbacks != null) {
			mapMoverCallbacks.onMapMoverConnectionFailed(connectionResult);
		}
	}

	/*
	 * this method is called when a statelite status is recived it calculates
	 * the number of satelelites used in the location
	 */
	private void satlock() {
		GpsStatus gpsStat = locationManager.getGpsStatus(null);
		if (gpsStat != null) {
			int j = 0;
			for (GpsSatellite satellite : gpsStat.getSatellites()) {
				if (satellite.usedInFix()) {
					j++;
				}
			}
			setSatLockStatus("Sat Lock " + j);
		}

	}

	/*
	 * this method fires the onSatLockChange callback when the satellite status
	 * changes
	 */
	private void setSatLockStatus(String status) {
		if (!satLockStatus.equals(status)) {
			satLockStatus = status;
			if (mapMoverCallbacks != null) {
				mapMoverCallbacks.onSatLockChange(satLockStatus);
			}
		}
	}

	/*
	 * this is the asynctask that runs to create the new cameraupdate which is
	 * used to move the map
	 */
	private class NewCameraUpdateTask extends
			AsyncTask<Location, Void, CameraUpdate> {

		@Override
		protected CameraUpdate doInBackground(Location... params) {
			Location workingLocation = null;
			CameraUpdate newCameraUpdate = null;

			float bearing = 0f;
			float speed = 0f;

			for (Location mlocation : params) {
				speed = mlocation.getSpeed();

				// camera position is saved before the start of each animation.

				if (!mlocation.hasBearing() || speed == 0) {
					workingLocation = mlocation;
					// previous bearing
				} else {
					// current bearing
					bearing = mlocation.getBearing();
					// calculate the age of the location
					// atempt for animation to end a little bit past when
					// the
					// next
					// location arrives.
					// (location.getSpeed()m/s)(1/1000 interval seconds)(
					// 1/1000
					// km/m)
					// (1/6371 radians/km) = radians/6371000000.0

					double expectedDistance = expectedDistanceMultiplier
							* speed;

					// latitude in Radians
					double currentLatitude = Math.toRadians(mlocation
							.getLatitude());
					// longitude in Radians
					double currentlongitude = Math.toRadians(mlocation
							.getLongitude());

					double calcBearing = Math.toRadians(bearing);

					// the camera position is needed so I can put in the
					// previous camera bearing when the location has no
					// bearing. This should prevent the map from
					// zooming to north when the device stops moving.

					// calculate the expected latitude and longitude based
					// on
					// staring
					// location
					// , bearing, and distance
					double sincurrentLatitude = Math.sin(currentLatitude);
					double coscurrentLatitude = Math.cos(currentLatitude);
					double cosexpectedDistance = Math.cos(expectedDistance);
					double sinexpectedDistance = Math.sin(expectedDistance);

					double expectedLatitude = Math.asin(sincurrentLatitude
							* cosexpectedDistance + coscurrentLatitude
							* sinexpectedDistance * Math.cos(calcBearing));
					double a = Math.atan2(
							Math.sin(calcBearing) * sinexpectedDistance
									* coscurrentLatitude,
							cosexpectedDistance - sincurrentLatitude
									* Math.sin(expectedLatitude));
					double expectedLongitude = currentlongitude + a;
					expectedLongitude = (expectedLongitude + PI3) % PI2 - PI;

					// convert to degrees for the expected destination
					double expectedLongitudeDestination = Math
							.toDegrees(expectedLongitude);
					double expectedLatitudeDestination = Math
							.toDegrees(expectedLatitude);

					mlocation.setLatitude(expectedLatitudeDestination);
					mlocation.setLongitude(expectedLongitudeDestination);
					workingLocation = mlocation;

				}
				break;
			}

			if (workingLocation != null) {
				if (workingLocation.hasBearing()) {
					bearing = workingLocation.getBearing();
				} else {
					bearing = currentCameraPosition.bearing;
				}
				LatLng ll = new LatLng(workingLocation.getLatitude(),
						workingLocation.getLongitude());
				cameraPositionBuilder.zoom(currentCameraPosition.zoom)
				// previous camera tilt
						.tilt(currentCameraPosition.tilt)
						// new expected destination
						.target(ll)
						// north up or heading view
						.bearing((isHeadingUp) ? bearing : 0f);
				newCameraUpdate = CameraUpdateFactory
						.newCameraPosition(cameraPositionBuilder.build());
			}

			return newCameraUpdate;
		}

		@Override
		protected void onPostExecute(CameraUpdate result) {
			Log.d("test", Boolean.toString(isAnimating) + " onPostExecute");
			if (result != null) {
				nextCameraUpdate = result;
				// start the next animation
				startAnimation();
				Log.d("test", Boolean.toString(isAnimating)
						+ " onPostExecuteComplete");
			}
		}
	}

	/*
	 * called to start the nextcameraupdate
	 */
	private void startAnimation() {
		Log.d("test", Boolean.toString(isAnimating) + " startAnimation");
		if (isAnimating || nextCameraUpdate == null) {
			return;
		}
		isAnimating = true;
		CameraUpdate animateCameraUpdate = nextCameraUpdate;
		nextCameraUpdate = null;
		mMap.animateCamera(animateCameraUpdate, interval, this);
		Log.d("test", Boolean.toString(isAnimating) + " startanimateCamera");
	}

	public boolean getGpsEnabled() {
		return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
	}

	@Override
	public void onDisconnected() {

	}

	/**
	 * @return the interval
	 */
	public int getInterval() {
		return interval;
	}

	/**
	 * @return the displaySpeed
	 */
	public String getDisplaySpeed() {
		return _displaySpeed;
	}

	/**
	 * @param displaySpeed
	 *            the displaySpeed to set
	 */
	private void setDisplaySpeed(String displaySpeed) {
		if (!_displaySpeed.equals(displaySpeed)) {
			_displaySpeed = displaySpeed;
			if (this.mapMoverCallbacks != null) {
				mapMoverCallbacks.onSpeedChange(displaySpeed);
			}
		}
	}

	/**
	 * @return the isMPH
	 */
	public boolean isMPH() {
		return isMPH;
	}

	/**
	 * @param isMPH
	 *            the isMPH to set
	 */
	public void setMPH(boolean isMPH) {
		this.isMPH = isMPH;
	}

	/**
	 * @return the isSpeed
	 */
	public boolean isSpeed() {
		return isSpeed;
	}

	/**
	 * @param isSpeed
	 *            the isSpeed to set
	 */
	public void setShowSpeed(boolean isSpeed) {
		this.isSpeed = isSpeed;
	}

	private String _displaySpeed = "";

	private Runnable updateSpeedThread = new Runnable() {

		private double n1 = .1;
		private double currentSpeed = 0;
		private double diff;
		long workDelay = 0;

		public void run() {

			// cacluate new speed
			if (hasNewSpeed.compareAndSet(true, false)) {
				// convert newSpeed to mph

				actualSpeed = newSpeed * (isMPH ? 2.23694 : 3.6);

				diff = Math.round(Math.abs(currentSpeed - actualSpeed) / n1)
						* n1;
				// divide by zero time less than zero too long of interval
				// currentSpeed = speed
				if (diff == 0) {
					currentSpeed = actualSpeed;
				}
				// exit if the workDelay is bad
				// workDelay is too short just update the speed
				workDelay = (long) (Math.round(locationInterval / diff * n1));
				if (workDelay < 1) {
					currentSpeed = actualSpeed;
				}
			}

			if (currentSpeed < actualSpeed) {
				currentSpeed += n1;
				if (currentSpeed > actualSpeed) {
					currentSpeed = actualSpeed;
				}
			}
			if (currentSpeed > actualSpeed) {
				currentSpeed -= n1;
				if (currentSpeed < actualSpeed) {
					currentSpeed = actualSpeed;
				}
			}

			// if currentSpeed is display we are done
			if (currentSpeed == actualSpeed) {
				speedHandler.removeCallbacks(this);
			} else {
				speedHandler.postDelayed(this, workDelay);
			}
			setDisplaySpeed(decimalFormat.format(currentSpeed));
		}
	};

	

	/**
	 * @param gpsReadsPerMinute
	 * 
	 * @throws IllegalArgumentException
	 */
	public void setGpsReadsPerMinute(int gpsReadsPerMinute)
			throws IllegalArgumentException {
		if (gpsReadsPerMinute < 1 || gpsReadsPerMinute > 200) {
			throw new IllegalArgumentException(
					"gpsReadsPerMinute not between 1 and 200");
		}
		int newinterval = 60000 / gpsReadsPerMinute;

		if (interval != newinterval) {
			interval = newinterval;
			expectedDistanceMultiplier = (double) interval / 6371000000.0;
			if (locationClient.isConnected()) {
				locationClient.disconnect();
				locationClient.connect();
			}
		}
	}
}

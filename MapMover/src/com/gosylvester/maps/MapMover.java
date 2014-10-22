package com.gosylvester.maps;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import android.annotation.SuppressLint;
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

	private MapMoverCallbacks callback;
	private static final double PI = Math.PI;
	private static final double PI2 = 2 * PI;
	private static final double PI3 = 3 * PI;
	private long speedInterval;
	private GoogleMap mMap;
	private double expectedDistanceMultiplier;
	private int interval = 5000;

	private volatile CameraUpdate nextCameraUpdate;
	private volatile boolean isAnimating;

	private LocationManager locationManager;
	private LocationClient locationClient;

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

	public void stopAction_track() {
		action_track = false;
		if (mMap != null) {
			mMap.stopAnimation();
		}
		setSatLockStatus("");
		callback = null;

		if (locationManager != null) {
			locationManager.removeGpsStatusListener(this);
		}
		if (locationClient != null) {
			if (locationClient.isConnected()) {
				locationClient.removeLocationUpdates(this);
				locationClient.disconnect();
				locationClient = null;
			}
		}

		if (speedHandler != null) {
			speedHandler.removeCallbacks(updateSpeedThread);
		}

		speedHandler = null;
		locationManager = null;

		decimalFormat = null;
		mMap = null;
		hasNewSpeed = null;

	}

	/**
	 * @param action_track
	 *            the action_tracker to set Set to true and it starts receiving
	 *            gps and moving map set to false and it stops receiving gps.
	 */
	public void startAction_track(Context context, GoogleMap gMap,
			MapMoverCallbacks mapMoverCallbacks) {
		callback = mapMoverCallbacks;
		hasNewSpeed = new AtomicBoolean(false);
		action_track = true;
		// if on start location updates by requesting a connection
		mMap = gMap;
		setSatLockStatus("");

		if (locationClient == null) {
			locationClient = new LocationClient(context, this, this);
			locationClient.connect();
		}
		if (locationManager == null) {
			locationManager = (LocationManager) context
					.getSystemService(Context.LOCATION_SERVICE);
		}
		locationManager.removeGpsStatusListener(this);
		locationManager.addGpsStatusListener(this);
		if (decimalFormat == null) {
			decimalFormat = new DecimalFormat("##0.0");
		}
		isAnimating = false;
		speedHandler = new Handler();
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

	// time it takes to update the display this is 10 per second.
	private final int speedCount = 100;
	private float displacement = 1f;
	private boolean isAccuracyAlgorythm = true;

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
		if (!action_track) {
			if (locationClient != null) {
				if (locationClient.isConnected()) {
					locationClient.removeLocationUpdates(this);
					locationClient.disconnect();
					locationClient = null;
				}
			}
			return;
		}

		// Create a new global location parameters object
		LocationRequest locationRequest = LocationRequest.create();
		// Set the update interval 70% of the interval
		// this is to make sure we have an updated location
		// when the animation completes
		long locationInterval = (long) (interval * .70);
		locationRequest.setInterval(locationInterval);
		locationRequest.setFastestInterval(locationInterval);
		locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
		locationRequest.setSmallestDisplacement(displacement);
		locationClient.requestLocationUpdates(locationRequest, this);
		locationRequest = null;
		// number of miliseconds to update the speed 10 per second 100
		speedInterval = (long) interval / speedCount;
	}

	/*
	 * once connected and a request for location updates is received this method
	 * is called with periodic updates it starts a task that creates a camera
	 * update that moves the map.
	 */
	@SuppressLint("NewApi")
	@Override
	public void onLocationChanged(Location location) {
		if (!action_track) {
			return;
		}
		if (isAccuracyAlgorythm) {
			if (!location.hasAccuracy()) {
				return;
			}
			if (location.getAccuracy() > 42) {
				return;
			}
		}

		NewCameraUpdateTask newCameraUpdateTask = new NewCameraUpdateTask();

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
		if (callback != null) {
			callback.onMapMoverConnectionFailed(connectionResult);
		}
	}

	/*
	 * this method is called when a statelite status is recived it calculates
	 * the number of satelelites used in the location
	 */
	private void satlock() {
		if (locationManager != null) {
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
	}

	/*
	 * this method fires the onSatLockChange callback when the satellite status
	 * changes
	 */
	private void setSatLockStatus(String status) {
		if (!satLockStatus.equals(status)) {
			satLockStatus = status;
			if (callback != null) {
				callback.onSatLockChange(satLockStatus);
			}
		}
	}

	/*
	 * this is the asynctask that runs to create the new cameraupdate which is
	 * used to move the map
	 */
	private class NewCameraUpdateTask extends
			AsyncTask<Location, Void, CameraUpdate> {

		private CameraPosition taskcurrentCameraPosition;
		private CameraPosition.Builder cameraPositionBuilder = CameraPosition
				.builder();
		private double taskexpectedDistanceMultiplier;

		public NewCameraUpdateTask() {

		}

		@Override
		protected void onPreExecute() {
			if (mMap == null) {
				this.cancel(true);
				return;
			}
			this.taskcurrentCameraPosition = mMap.getCameraPosition();
			this.taskexpectedDistanceMultiplier = expectedDistanceMultiplier;
		}

		@Override
		protected CameraUpdate doInBackground(Location... params) {
			// Location workingLocation = null;
			LatLng ll;

			float bearing = 0f;
			float speed;

			for (Location mlocation : params) {
				// zero is return when location has no speed.
				speed = mlocation.getSpeed();

				// camera position is saved before the start of each animation.

				if (mlocation.hasBearing()) {
					bearing = mlocation.getBearing();
				} else {
					bearing = taskcurrentCameraPosition.bearing;
				}

				// cant calc anything without speed
				if (speed == 0) {
					// workingLocation = mlocation;
					ll = new LatLng(mlocation.getLatitude(),
							mlocation.getLongitude());
					// previous bearing
				} else {

					// calculate the age of the location
					// atempt for animation to end a little bit past when
					// the
					// next
					// location arrives.
					// (location.getSpeed()m/s)(1/1000 interval seconds)(
					// 1/1000
					// km/m)
					// (1/6371 radians/km) = radians/6371000000.0

					double expectedDistance = taskexpectedDistanceMultiplier
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
					ll = new LatLng(expectedLatitudeDestination,
							expectedLongitudeDestination);
				}

				cameraPositionBuilder.zoom(taskcurrentCameraPosition.zoom)
				// is heading up tilt 75 north up tilt 0
						.tilt(isHeadingUp ? 75f : 0f)
						// new expected destination
						.target(ll)
						// north up or heading view
						.bearing((isHeadingUp) ? bearing : 0f);

				break;
			}

			return CameraUpdateFactory.newCameraPosition(cameraPositionBuilder
					.build());

			// if (workingLocation != null) {
			// if (workingLocation.hasBearing()) {
			// bearing = workingLocation.getBearing();
			// } else {
			// bearing = currentCameraPosition.bearing;
			// }
			// LatLng ll = new LatLng(workingLocation.getLatitude(),
			// workingLocation.getLongitude());
			// cameraPositionBuilder.zoom(currentCameraPosition.zoom)
			// // is heading up tilt 75 north up tilt 0
			// .tilt(isHeadingUp ? 75f : 0f)
			// // new expected destination
			// .target(ll)
			// // north up or heading view
			// .bearing((isHeadingUp) ? bearing : 0f);
			// newCameraUpdate = CameraUpdateFactory
			// .newCameraPosition(cameraPositionBuilder.build());

			// return newCameraUpdate;
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
		if (isAnimating || nextCameraUpdate == null) {
			return;
		}
		isAnimating = true;
		CameraUpdate animateCameraUpdate = nextCameraUpdate;
		nextCameraUpdate = null;
		mMap.animateCamera(animateCameraUpdate, interval, this);
	}

	// public boolean getGpsEnabled() {
	// if (locationManager!=null)
	// return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
	// }

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
			if (callback != null) {
				callback.onSpeedChange(displaySpeed);
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
				// diff rounded to .1;
				diff = Math.round(Math.abs(currentSpeed - actualSpeed) / n1)
						* n1;
				// divide by zero time less than zero too long of interval

				if (diff == 0) {
					currentSpeed = actualSpeed;
				} else {
					// calculate n1 for the speedCount;
					n1 = diff / speedCount;
					// if n1 is less than .1 recalc the speedInterval
					// to save CPU. This will be the case
					// when running at constant speed.
					// which should be most cases.
					if (n1 < .1) {
						n1 = .1;
						workDelay = (long) (interval / diff * .1);
					} else {
						workDelay = speedInterval;
					}
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

	public void setAccuracyAlgorythm(boolean isAccuracy) {
		isAccuracyAlgorythm = isAccuracy;
	}

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

	/**
	 * @param gpsInterval
	 * 
	 * @throws IllegalArgumentException
	 */
	public void setInterval(int newInterval) throws IllegalArgumentException {
		if (newInterval < 500 || newInterval > 60000) {
			throw new IllegalArgumentException(
					"interval not between 500 and 60000");
		}

		if (interval != newInterval) {
			interval = newInterval;
			expectedDistanceMultiplier = (double) interval / 6371000000.0;
			if (locationClient.isConnected()) {
				locationClient.disconnect();
				locationClient.connect();
			}
		}
	}

	/**
	 * @param gpsInterval
	 * 
	 * @throws IllegalArgumentException
	 */
	public void setDisplacement(float newDisplacement)
			throws IllegalArgumentException {
		if (newDisplacement < 0f || newDisplacement > 20f) {
			throw new IllegalArgumentException(
					"displacement not between 0 and 20");
		}
		if (displacement != newDisplacement) {
			displacement = newDisplacement;
			if (locationClient.isConnected()) {
				locationClient.disconnect();
				locationClient.connect();
			}
		}
	}
}

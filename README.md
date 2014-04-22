MapMover
========

This is a library to move the google map api v2 with the gps.  Yes googlemap api can already do this if the user clicks the mylocationbutton.  With MapMover you can move the map without the user clicking a button.  You pass in activity and google map and the map moves with gps.  The mapmover project has to be setup to use googleplayservices just like any project that uses google maps api v2 but you don't need the api key or anything in the manifiest.  You add the mapmover project to your project just like any other library project.  Or you can simply copy mapmover.java to your project.

in OnResume

    @Override
	  protected void onResume() {
        if (mm == null) {
			      mm = new MapMover(this, mMap);
		    }
        // action_track is boolean true map follows gps.  false map doesn't follow gps  pretty simple.
        mm.setAction_track(action_track);
    }

you should stop the mapmover in OnPause

    @Override
	  protected void onPause() {
		    super.onPause();
		    // actiontrack pauses when app pauses
		    if (mm != null) {
			     mm.setAction_track(false);
		    }
	  }


//there are other settings you can use

    mm.setHeadingUp(isHeadingUp);   //defaults to heading up pass in false for north up view.
    mm.setGpsReadsPerMinute(gpsReadsPerMinute);   // this defaults to 12 and because the map movement is animated the map movement is smooth.
		
The MapMover will return the speed in MPH or KPH to one decimal place and it has smooth runnable that increments or decrements the speed it works nice.  You have to implement the mapmover callbacks in your app for these to work.  Sorry you can't set the callbacks.  You have to use implements.

		mm.setShowSpeed(isSpeedChecked);  //true enables the mapmover onSpeedChange callabacks
		mm.setMPH(isMPH);  // true speed is returned in MPH
		
These are the mapmover callbacks

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

package com.wikiponder.touchmap.classes.vpr;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.camera.core.ImageAnalysis;

/**
 * How the phone is held while the camera looks at a place, independent of the screen's orientation
 * (RecordVisualPlaceActivity is locked to portrait, but the user may hold the phone sideways or
 * upside down):
 * <ul>
 * <li>keeps an ImageAnalysis' target rotation on the phone's physical rotation, so
 * {@link VprFrames#upright} turns every frame the way the world looks, sky up;</li>
 * <li>{@link #heading()}: the compass direction the back camera points to. AngleManager's azimuth
 * follows the top edge of the phone (right for the map held flat), which says nothing useful when
 * the phone is held up to film a building.</li>
 * </ul>
 */
public class CameraOrientation implements SensorEventListener {
	/** Below this horizontal share of the camera axis the phone points nearly straight up or down
	 * and its heading is meaningless. */
	private static final float MIN_HORIZONTAL = 0.3f;
	/** Low-pass weight of a new sensor reading. */
	private static final float SMOOTHING = 0.15f;

	private final SensorManager sensorManager;
	private final OrientationEventListener orientationListener;
	private ImageAnalysis analysis;
	private int rotation = Surface.ROTATION_0;
	private float east, north;
	private volatile float heading = Float.NaN;
	private volatile boolean front;

	public CameraOrientation(Context context) {
		sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		orientationListener = new OrientationEventListener(context) {
			@Override
			public void onOrientationChanged(int degrees) {
				if (degrees == ORIENTATION_UNKNOWN) return; // lying flat: keep the last rotation
				int r;
				if (degrees >= 45 && degrees < 135) r = Surface.ROTATION_270;
				else if (degrees >= 135 && degrees < 225) r = Surface.ROTATION_180;
				else if (degrees >= 225 && degrees < 315) r = Surface.ROTATION_90;
				else r = Surface.ROTATION_0;
				if (r == rotation) return;
				rotation = r;
				if (analysis != null) analysis.setTargetRotation(r);
			}
		};
	}

	/** Frames of this use case follow the phone's rotation from now on. */
	public void attach(ImageAnalysis analysis) {
		this.analysis = analysis;
		analysis.setTargetRotation(rotation);
	}

	/** The front camera looks out of the screen side, opposite to the back camera. */
	public void setFrontCamera(boolean front) {
		if (this.front == front) return;
		this.front = front;
		heading = Float.NaN;
	}

	public void start() {
		if (orientationListener.canDetectOrientation()) orientationListener.enable();
		Sensor sensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
	}

	public void stop() {
		orientationListener.disable();
		if (sensorManager != null) sensorManager.unregisterListener(this);
	}

	/** Compass degrees (0 = north, clockwise) the camera in use points to, or NaN if unknown.
	 * Always the direction the lens looks, whichever camera it is (the front camera's is the back
	 * camera's + 180°), so headings of frames saved with one camera compare directly with the live
	 * heading of the other: saved − live &gt; 0 means turn clockwise, i.e. right for a user facing
	 * the way the lens looks. */
	public float heading() {
		return heading;
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		float[] r = new float[9];
		SensorManager.getRotationMatrixFromVector(r, event.values);
		// The back camera looks along the phone's -Z axis, the front one along +Z; the third column
		// of r is +Z in world coordinates (east, north, up), whatever the phone's roll.
		float sign = front ? 1 : -1;
		float e = sign * r[2], n = sign * r[5];
		float horizontal = (float) Math.sqrt(e * e + n * n);
		if (horizontal < MIN_HORIZONTAL) return;
		e /= horizontal;
		n /= horizontal;
		if (Float.isNaN(heading)) {
			east = e;
			north = n;
		} else {
			east += SMOOTHING * (e - east);
			north += SMOOTHING * (n - north);
		}
		float deg = (float) Math.toDegrees(Math.atan2(east, north));
		heading = deg < 0 ? deg + 360 : deg;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}

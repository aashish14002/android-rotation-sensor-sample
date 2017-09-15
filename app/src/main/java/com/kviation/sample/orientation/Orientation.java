
package com.kviation.sample.orientation;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class Orientation implements SensorEventListener {

  public interface Listener {
    void onOrientationChanged(float pitch, float roll);
  }

  private static final int SENSOR_DELAY_MICROS = 500 * 1000; // 50ms

  private final WindowManager mWindowManager;

  private final SensorManager mSensorManager;

  @Nullable
  private final Sensor mAccelerometer;

  private final Sensor mGyroscope;
  private Activity mactivity;


  private int mLastAccuracy;
  private Listener mListener;

  public Orientation(Activity activity) {
    mWindowManager = activity.getWindow().getWindowManager();
    mSensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
    mactivity = activity;
    // Can be null if the sensor hardware is not available
    mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
  }

  public void startListening(Listener listener) {
    if (mListener == listener) {
      return;
    }
    mListener = listener;
    if (mAccelerometer == null && mGyroscope == null) {
      LogUtil.w("accelerometer and gyroscope vector sensor not available; will not provide orientation data.");
      return;
    }
    if(mAccelerometer != null) {
      mSensorManager.registerListener(this, mAccelerometer, SENSOR_DELAY_MICROS);
    }
    if(mGyroscope !=null) {
      mSensorManager.registerListener(this, mGyroscope, SENSOR_DELAY_MICROS);
    }

  }

  public void stopListening() {
    mSensorManager.unregisterListener(this);
    mListener = null;
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    if (mLastAccuracy != accuracy) {
      mLastAccuracy = accuracy;
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (mListener == null) {
      return;
    }
    if (mLastAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
      return;
    }
    if (event.sensor == mAccelerometer || event.sensor == mGyroscope) {
      String readings = event.sensor.getName()+","+event.values[0]+","+event.values[1]+","+event.values[2];
      writeSensorData(readings);
      updateOrientation(event.sensor.getName(), event.values);
    }
  }

  public void writeSensorData(String readings)
  {
    String fileName="sensor-data.txt";
    File file= new File(mactivity.getFilesDir(), fileName);
    String f = String.valueOf(file);
    try {
      FileOutputStream fout=new FileOutputStream(f,true);
      fout.write(readings.getBytes());
      fout.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }
  @SuppressWarnings("SuspiciousNameCombination")
  private void updateOrientation(String sensor, float[] rotationVector) {
    float[] rotationMatrix = new float[9];
    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);

    final int worldAxisForDeviceAxisX;
    final int worldAxisForDeviceAxisY;

    // Remap the axes as if the device screen was the instrument panel,
    // and adjust the rotation matrix for the device orientation.
    switch (mWindowManager.getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_0:
      default:
        worldAxisForDeviceAxisX = SensorManager.AXIS_X;
        worldAxisForDeviceAxisY = SensorManager.AXIS_Z;
        break;
      case Surface.ROTATION_90:
        worldAxisForDeviceAxisX = SensorManager.AXIS_Z;
        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
        break;
      case Surface.ROTATION_180:
        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Z;
        break;
      case Surface.ROTATION_270:
        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Z;
        worldAxisForDeviceAxisY = SensorManager.AXIS_X;
        break;
    }

    float[] adjustedRotationMatrix = new float[9];
    SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
        worldAxisForDeviceAxisY, adjustedRotationMatrix);

    // Transform rotation matrix into azimuth/pitch/roll
    float[] orientation = new float[3];
    SensorManager.getOrientation(adjustedRotationMatrix, orientation);

    // Convert radians to degrees
    float pitch = orientation[1] * -57;
    float roll = orientation[2] * -57;

    mListener.onOrientationChanged(pitch, roll);
  }
}

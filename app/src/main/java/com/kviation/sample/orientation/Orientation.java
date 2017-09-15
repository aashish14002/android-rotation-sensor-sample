
package com.kviation.sample.orientation;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class Orientation implements SensorEventListener {

  public interface Listener {
    void onOrientationChanged(float pitch, float roll);
  }

  private static final int SENSOR_DELAY_MICROS = 500 * 1000; // 50ms
  final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE=123;

  private  final WindowManager mWindowManager;

  private  final SensorManager mSensorManager;

  @Nullable
  private  final Sensor mAccelerometer;
  @Nullable
  private  final Sensor mGyroscope;

  private Activity mactivity;


  private int mLastAccuracy;
  private Listener mListener;

  // angular speeds from gyro
  private float[] gyro = new float[3];

  // rotation matrix from gyro data
  private float[] gyroMatrix = new float[9];

  // orientation angles from gyro matrix
  private float[] gyroOrientation = new float[3];

  // accelerometer vector
  private float[] accel = new float[3];

  // orientation angles from accel and magnet
  private float[] accMagOrientation = new float[3];

  // final orientation angles from sensor fusion
  private float[] fusedOrientation = new float[3];

  // accelerometer and magnetometer based rotation matrix
  private float[] rotationMatrix = new float[9];

  private final float[] deltaRotationVector = new float[4];

  public static final float EPSILON = 0.000000001f;

  private static final float NS2S = 1.0f / 1000000000.0f;
  private float timestamp;
  private boolean initState = true;

  private float[] accelRotationMatrix =new float[9];
  private float[] gyroRotationMatrix =new float[9];

  public Orientation(Activity activity) {
    mWindowManager = activity.getWindow().getWindowManager();
    mSensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
    mactivity = activity;
    gyroOrientation[0] = 0.0f;
    gyroOrientation[1] = 0.0f;
    gyroOrientation[2] = 0.0f;

    // initialise gyroMatrix with identity matrix
    gyroMatrix[0] = 1.0f; gyroMatrix[1] = 0.0f; gyroMatrix[2] = 0.0f;
    gyroMatrix[3] = 0.0f; gyroMatrix[4] = 1.0f; gyroMatrix[5] = 0.0f;
    gyroMatrix[6] = 0.0f; gyroMatrix[7] = 0.0f; gyroMatrix[8] = 1.0f;
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

    String readings = event.values[0]+","+event.values[1]+","+event.values[2]+"\n";
    onExtpu(event.sensor.getName(),readings);
    updateOrientation(event.sensor.getName(), event);

  }


  public void writeSensorData(String f,String readings)
  {
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


  public boolean isExternalStorageWritable()
  {
    String state = Environment.getExternalStorageState();
    if (Environment.MEDIA_MOUNTED.equals(state))
    {
      return true;
    }
    return false;
  }

  public void onExtpu(String sensor,String readings)
  {
    String f=sensor+".txt";
    if(permission())
    {
      if(!isExternalStorageWritable())
      {
        Log.e("error:","not available");
        Toast.makeText(mactivity.getApplicationContext(),"not available",Toast.LENGTH_SHORT).show();
        return;
      }
      File file=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),f);
      if (!file.mkdirs())
      {
        Log.e("error:", "Directory not created");
        //Toast.makeText(this,"not created",Toast.LENGTH_SHORT).show();
      }

      Toast.makeText(mactivity.getApplicationContext(),"Extpu."+String.valueOf(file),Toast.LENGTH_SHORT).show();
      Log.v("file ",String.valueOf(file));
      writeSensorData(file+file.separator+f,readings);

    }
    else
    {
      Toast.makeText(mactivity.getApplicationContext(),"Extpu. not done ",Toast.LENGTH_SHORT).show();

    }

  }

  public boolean permission()
  {
    if (ContextCompat.checkSelfPermission(mactivity.getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
    {

      ActivityCompat.requestPermissions(mactivity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
      return false;
    }
    else

      return true;

  }


  @SuppressWarnings("SuspiciousNameCombination")
  private void updateOrientation(String sensor, SensorEvent event) {

    if(sensor == "ACCELEROMETER"){
      SensorManager.getRotationMatrix(accelRotationMatrix,null,event.values,null);
    }
    else{
      if (timestamp != 0) {
        final float dT = (event.timestamp - timestamp) * NS2S;
        // Axis of the rotation sample, not normalized yet.
        float axisX = event.values[0];
        float axisY = event.values[1];
        float axisZ = event.values[2];

        // Calculate the angular speed of the sample
        float omegaMagnitude = (float)Math.sqrt(axisX*axisX + axisY*axisY + axisZ*axisZ);

        // Normalize the rotation vector if it's big enough to get the axis
        // (that is, EPSILON should represent your maximum allowable margin of error)
        if (omegaMagnitude > EPSILON) {
          axisX /= omegaMagnitude;
          axisY /= omegaMagnitude;
          axisZ /= omegaMagnitude;
        }

        // Integrate around this axis with the angular speed by the timestep
        // in order to get a delta rotation from this sample over the timestep
        // We will convert this axis-angle representation of the delta rotation
        // into a quaternion before turning it into the rotation matrix.
        float thetaOverTwo = omegaMagnitude * dT / 2.0f;
        float sinThetaOverTwo = (float)Math.sin(thetaOverTwo);
        float cosThetaOverTwo = (float)Math.cos(thetaOverTwo);
        deltaRotationVector[0] = sinThetaOverTwo * axisX;
        deltaRotationVector[1] = sinThetaOverTwo * axisY;
        deltaRotationVector[2] = sinThetaOverTwo * axisZ;
        deltaRotationVector[3] = cosThetaOverTwo;
      }
      timestamp = event.timestamp;

      SensorManager.getRotationMatrixFromVector(gyroRotationMatrix, deltaRotationVector);
      // User code should concatenate the delta rotation we computed with the current rotation
      // in order to get the updated rotation.
      // rotationCurrent = rotationCurrent * deltaRotationMatrix;
    }

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

    for(int i=0;i<9;i++){
      rotationMatrix[i]=gyroRotationMatrix[i]+accelRotationMatrix[i];
    }

    float[] adjustedRotationMatrix = new float[9];
    SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
        worldAxisForDeviceAxisY, adjustedRotationMatrix);

    // Transform rotation matrix into azimuth/pitch/roll
    float[] orientation = new float[3];
    SensorManager.getOrientation(adjustedRotationMatrix, orientation);

     //Convert radians to degrees
    float pitch = orientation[1] * -57;
    float roll = orientation[2] * -57;

    mListener.onOrientationChanged(pitch, roll);
  }
}

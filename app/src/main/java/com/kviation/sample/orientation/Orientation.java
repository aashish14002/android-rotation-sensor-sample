
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
  private final WindowManager mWindowManager;

  private final SensorManager mSensorManager;

  @Nullable
  private final Sensor mAccelerometer;

  private final Sensor mGyroscope;
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

  public static final float EPSILON = 0.000000001f;

  private static final float NS2S = 1.0f / 1000000000.0f;
  private float timestamp;
  private boolean initState = true;

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
    if (event.sensor == mAccelerometer) {
      System.arraycopy(event.values, 0, accel, 0, 3);
      calculateAccMagOrientation();
    } else if(event.sensor == mGyroscope){
      gyroFunction(event);
    }

    String readings = event.values[0]+","+event.values[1]+","+event.values[2]+"\n";
    onExtpu(event.sensor.getName(),readings);
    updateOrientation(event.sensor.getName(), event.values);

  }

  public void calculateAccMagOrientation() {
    if(SensorManager.getRotationMatrix(rotationMatrix, null, accel,null)) {
      SensorManager.getOrientation(rotationMatrix, accMagOrientation);
    }
  }

  private void getRotationVectorFromGyro(float[] gyroValues,
                                         float[] deltaRotationVector,
                                         float timeFactor)
  {
    float[] normValues = new float[3];

    // Calculate the angular speed of the sample
    float omegaMagnitude =
            (float)Math.sqrt(gyroValues[0] * gyroValues[0] +
                    gyroValues[1] * gyroValues[1] +
                    gyroValues[2] * gyroValues[2]);

    // Normalize the rotation vector if it's big enough to get the axis
    if(omegaMagnitude > EPSILON) {
      normValues[0] = gyroValues[0] / omegaMagnitude;
      normValues[1] = gyroValues[1] / omegaMagnitude;
      normValues[2] = gyroValues[2] / omegaMagnitude;
    }

    // Integrate around this axis with the angular speed by the timestep
    // in order to get a delta rotation from this sample over the timestep
    // We will convert this axis-angle representation of the delta rotation
    // into a quaternion before turning it into the rotation matrix.
    float thetaOverTwo = omegaMagnitude * timeFactor;
    float sinThetaOverTwo = (float)Math.sin(thetaOverTwo);
    float cosThetaOverTwo = (float)Math.cos(thetaOverTwo);
    deltaRotationVector[0] = sinThetaOverTwo * normValues[0];
    deltaRotationVector[1] = sinThetaOverTwo * normValues[1];
    deltaRotationVector[2] = sinThetaOverTwo * normValues[2];
    deltaRotationVector[3] = cosThetaOverTwo;
  }

  public void gyroFunction(SensorEvent event) {
    // don't start until first accelerometer/magnetometer orientation has been acquired
    if (accMagOrientation == null)
      return;

    // initialisation of the gyroscope based rotation matrix
    if(initState) {
      float[] initMatrix = new float[9];
      initMatrix = getRotationMatrixFromOrientation(accMagOrientation);
      float[] test = new float[3];
      SensorManager.getOrientation(initMatrix, test);
      gyroMatrix = matrixMultiplication(gyroMatrix, initMatrix);
      initState = false;
    }

    // copy the new gyro values into the gyro array
    // convert the raw gyro data into a rotation vector
    float[] deltaVector = new float[4];
    if(timestamp != 0) {
      final float dT = (event.timestamp - timestamp) * NS2S;
      System.arraycopy(event.values, 0, gyro, 0, 3);
      getRotationVectorFromGyro(gyro, deltaVector, dT / 2.0f);
    }

    // measurement done, save current time for next interval
    timestamp = event.timestamp;

    // convert rotation vector into rotation matrix
    float[] deltaMatrix = new float[9];
    SensorManager.getRotationMatrixFromVector(deltaMatrix, deltaVector);

    // apply the new rotation interval on the gyroscope based rotation matrix
    gyroMatrix = matrixMultiplication(gyroMatrix, deltaMatrix);

    // get the gyroscope based orientation from the rotation matrix
    SensorManager.getOrientation(gyroMatrix, gyroOrientation);
  }

  private float[] getRotationMatrixFromOrientation(float[] o) {
    float[] xM = new float[9];
    float[] yM = new float[9];
    float[] zM = new float[9];

    float sinX = (float)Math.sin(o[1]);
    float cosX = (float)Math.cos(o[1]);
    float sinY = (float)Math.sin(o[2]);
    float cosY = (float)Math.cos(o[2]);
    float sinZ = (float)Math.sin(o[0]);
    float cosZ = (float)Math.cos(o[0]);

    // rotation about x-axis (pitch)
    xM[0] = 1.0f; xM[1] = 0.0f; xM[2] = 0.0f;
    xM[3] = 0.0f; xM[4] = cosX; xM[5] = sinX;
    xM[6] = 0.0f; xM[7] = -sinX; xM[8] = cosX;

    // rotation about y-axis (roll)
    yM[0] = cosY; yM[1] = 0.0f; yM[2] = sinY;
    yM[3] = 0.0f; yM[4] = 1.0f; yM[5] = 0.0f;
    yM[6] = -sinY; yM[7] = 0.0f; yM[8] = cosY;

    // rotation about z-axis (azimuth)
    zM[0] = cosZ; zM[1] = sinZ; zM[2] = 0.0f;
    zM[3] = -sinZ; zM[4] = cosZ; zM[5] = 0.0f;
    zM[6] = 0.0f; zM[7] = 0.0f; zM[8] = 1.0f;

    // rotation order is y, x, z (roll, pitch, azimuth)
    float[] resultMatrix = matrixMultiplication(xM, yM);
    resultMatrix = matrixMultiplication(zM, resultMatrix);
    return resultMatrix;
  }

  private float[] matrixMultiplication(float[] A, float[] B) {
    float[] result = new float[9];

    result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
    result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
    result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

    result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
    result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
    result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

    result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
    result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
    result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

    return result;
  }

  public void writeSensorData(String f,String readings)
  {
//    String fileName="sensor-data.txt";
//    File file= new File(mactivity.getFilesDir(), fileName);
//    String f = String.valueOf(file);
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
  private void updateOrientation(String sensor, float[] rotationVector) {
//    float[] rotationMatrix = new float[9];
//    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
//
//    final int worldAxisForDeviceAxisX;
//    final int worldAxisForDeviceAxisY;
//
//    // Remap the axes as if the device screen was the instrument panel,
//    // and adjust the rotation matrix for the device orientation.
//    switch (mWindowManager.getDefaultDisplay().getRotation()) {
//      case Surface.ROTATION_0:
//      default:
//        worldAxisForDeviceAxisX = SensorManager.AXIS_X;
//        worldAxisForDeviceAxisY = SensorManager.AXIS_Z;
//        break;
//      case Surface.ROTATION_90:
//        worldAxisForDeviceAxisX = SensorManager.AXIS_Z;
//        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
//        break;
//      case Surface.ROTATION_180:
//        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
//        worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Z;
//        break;
//      case Surface.ROTATION_270:
//        worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Z;
//        worldAxisForDeviceAxisY = SensorManager.AXIS_X;
//        break;
//    }
//
//    float[] adjustedRotationMatrix = new float[9];
//    SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
//        worldAxisForDeviceAxisY, adjustedRotationMatrix);
//
//    // Transform rotation matrix into azimuth/pitch/roll
//    float[] orientation = new float[3];
//    SensorManager.getOrientation(adjustedRotationMatrix, orientation);

    float yaw=(float) Math.atan2(rotationMatrix[3],rotationMatrix[0]);
    float pitch=(float) Math.atan2(-rotationMatrix[6],Math.sqrt(Math.pow(rotationMatrix[7],2)+Math.pow(rotationMatrix[8],2)));
    float roll=(float)Math.atan2(rotationMatrix[7],rotationMatrix[8]);

    // Convert radians to degrees
//    float pitch = orientation[1] * -57;
//    float roll = orientation[2] * -57;

    mListener.onOrientationChanged(pitch, roll);
  }
}

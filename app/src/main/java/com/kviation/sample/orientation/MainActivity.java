package com.kviation.sample.orientation;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity implements Orientation.Listener {

  private Orientation mOrientation;
  private AttitudeIndicator mAttitudeIndicator;
  private Button mButton;
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    mOrientation = new Orientation(this);
    mAttitudeIndicator = (AttitudeIndicator) findViewById(R.id.attitude_indicator);
    mButton = (Button) findViewById(R.id.button);
    mButton.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if(mButton.getText()=="Stop"){
                stop();
            }else {
                start();
            }

        }
    });
  }

    protected void start(){
        mOrientation.startListening(this);
        mButton.setText("Stop");
    }

    protected void stop(){
        mOrientation.stopListening();
        mButton.setText("Start");
    }

//  @Override
//  protected void onStart() {
//    super.onStart();
//    mOrientation.startListening(this);
//  }
//
//  @Override
//  protected void onStop() {
//    super.onStop();
//    mOrientation.stopListening();
//  }

  @Override
  public void onOrientationChanged(float pitch, float roll) {
    mAttitudeIndicator.setAttitude(pitch, roll);
  }
}

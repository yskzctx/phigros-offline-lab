package com.phigros.offline;
public class ControlsModeRegression {
 static void reject(int mode,String score,String accuracy) {
  try {ControlsConfig.parse(mode,score,accuracy,"");throw new AssertionError("Active target accepted invalid input");}
  catch(IllegalArgumentException expected){}
 }
 public static void main(String[]args) {
  ControlsConfig accuracy=ControlsConfig.parse(2,"","90.25","");
  if(accuracy.mode!=2||accuracy.accuracy!=9025||accuracy.target!=1000000)throw new AssertionError("Accuracy mode needs only accuracy");
  ControlsConfig score=ControlsConfig.parse(1,"900000","","");
  if(score.target!=900000||score.accuracy!=10000)throw new AssertionError("Score mode needs only score");
  ControlsConfig ap=ControlsConfig.parse(0,"","","");
  if(ap.target!=1000000||ap.accuracy!=10000)throw new AssertionError("AP needs no target input");
  if(ControlsConfig.parse(2,"garbage","90.25","").accuracy!=9025)throw new AssertionError("Inactive invalid score rejected");
  if(ControlsConfig.parse(1,"900000","garbage","").target!=900000)throw new AssertionError("Inactive invalid accuracy rejected");
  if(ControlsConfig.parse(2,"950000","90.25","").target!=950000)throw new AssertionError("Valid inactive value lost");
  reject(1,"","100");reject(1,"1000001","");reject(2,"","101");reject(2,"1000000","90.333");reject(2,"1000000","");
  System.out.println("PASS active-mode validation, empty/invalid inactive input, valid inactive value retained, active invalid target rejected");
 }
}

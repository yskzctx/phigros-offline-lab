package com.phigros.offline;
public class ControlsPerfect173Test {
 public static void main(String[] args){
  for(int type=0;type<=4;type++)for(int pos=1;pos<=6;pos++){
   ControlsConfig c=ControlsConfig.parse(2,"","90.25","0:"+pos+":1:20:3:1,7,15:"+type);
   if(c.rules[0].judgment!=0||c.rules[0].noteType!=type)throw new AssertionError("Perfect/filter lost");
   if(c.accuracy!=9025)throw new AssertionError("Accuracy lost");
  }
  for(int j=4;j<=5;j++)if(ControlsConfig.parse(0,"","",j+":6:1:1:1:").rules[0].judgment!=j)throw new AssertionError("Good timing lost");
  for(String s:new String[]{"-1:6:1:1:1:","6:6:1:1:1:"}){
   try{ControlsConfig.parse(0,"","",s);throw new AssertionError("Invalid judgment accepted");}catch(IllegalArgumentException expected){}
  }
  System.out.println("PASS: Perfect in all position/type combinations and bounds");
 }
}

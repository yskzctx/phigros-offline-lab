package com.phigros.offline;
public class ControlsRulesTest {
 public static void main(String[]args){
  ControlsConfig c=ControlsConfig.parse(1,"900000","90.25","1:1:100:100:1:;2:1:50:50:1:;3:3:1:1:1:5,5,8");
  if(c.mode!=1||c.target!=900000||c.accuracy!=9025||c.rules.length!=3||c.rules[2].indices.length!=2)throw new AssertionError("Rules encoding / decimal goal mismatch");
  if(c.packed[0]!=3)throw new AssertionError("JNI count missing");
  for(String bad:new String[]{"1:2:80:50:1:","0:1:1:1:1:","1:3:1:1:1:","1:1:0:1:1:","1:1:1:1:1:1,"}){
   try{ControlsConfig.parse(0,"1000000","100",bad);throw new AssertionError("Invalid rule accepted "+bad);}catch(IllegalArgumentException good){}
  }
  try{ControlsConfig.parse(2,"1000000","90.333","");throw new AssertionError("Invalid accuracy accepted");}catch(IllegalArgumentException good){}
  System.out.println("PASS rule list validation, decimal accuracy, duplicate notes, JNI encoding");
 }
}

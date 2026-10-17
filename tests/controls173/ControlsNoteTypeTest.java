package com.phigros.offline;
import java.lang.reflect.Field;
public class ControlsNoteTypeTest {
 public static void main(String[] args)throws Exception {
  ControlsConfig c=ControlsConfig.parse(0,"1000000","100","2:6:1:1:1::3");
  Field f=ControlsConfig.Rule.class.getField("noteType");
  if(f.getInt(c.rules[0])!=3)throw new AssertionError("Hold filter was not retained");
  if(c.packed[0]!=-168||c.packed[1]!=1)throw new AssertionError("Typed rules need a versioned JNI packet");
  ControlsConfig old=ControlsConfig.parse(0,"1000000","100","1:6:1:1:1:");
  if(f.getInt(old.rules[0])!=0||old.packed[0]!=1)throw new AssertionError("Legacy all-type rules must remain compatible");
  for(int type=0;type<=4;type++){
   c=ControlsConfig.parse(2,"","90","1:4:1:1:17::"+type);
   if(f.getInt(c.rules[0])!=type)throw new AssertionError("Note type lost");
  }
  for(String bad:new String[]{"2:6:1:1:1::5","2:6:1:1:1::-1","2:6:1:1:1::","2:6:1:1:1::hold"}){
   try{ControlsConfig.parse(0,"","",bad);throw new AssertionError("Invalid note type accepted");}catch(IllegalArgumentException expected){}
  }
  System.out.println("PASS: five note filters, old configuration compatibility, active targets and invalid type rejection");
 }
}

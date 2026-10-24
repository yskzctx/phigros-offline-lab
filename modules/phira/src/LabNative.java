package com.phira.lab;
public final class LabNative {
 public static native boolean arm();public static native void disarm();public static native boolean setEnabled(boolean value);public static native int[] status();
 public static native boolean setTemporaryRoot(String directory);
 public static int[] safeStatus(){try{return status();}catch(UnsatisfiedLinkError ignored){return new int[5];}}
 private LabNative(){}
}

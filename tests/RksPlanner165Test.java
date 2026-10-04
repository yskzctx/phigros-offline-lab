package com.phigros.offline;
import java.io.*;import java.util.*;
public class RksPlanner165Test {
 public static void main(String[] args)throws Exception {
  ArrayList<RksPlanner.Chart> charts=new ArrayList<RksPlanner.Chart>();
  try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(args[0]),"UTF-8"))){String s;while((s=r.readLine())!=null){String[] p=s.split("\t");charts.add(new RksPlanner.Chart(p[0],Float.parseFloat(p[1])));}}
  if(charts.size()!=1037)throw new AssertionError("Wrong catalog");
  for(float target:new float[]{0,0.0019f,0.0037f,0.01f,0.1f,0.6f,0.6001f,1.9999f,2,8.12f,12.5f,15.9999f,16,16.25f,17.30f,17.3166f,17.32f}) {
   List<RksPlanner.Record> records=RksPlanner.generate(charts,target);
   float actual=RksPlanner.calculate(records);
   float expected=target>=17.3166f?RksPlanner.maximum(charts):target;
   if(Math.abs(actual-expected)>0.00004f)throw new AssertionError("RKS target "+target+" became "+actual);
   int ap=0;Set<String> ids=new HashSet<String>();
   for(RksPlanner.Record r:records){if(!ids.add(r.chart.key))throw new AssertionError("Duplicate");if(r.ap)ap++;if(r.score<0||r.score>1000000||r.accuracy<0||r.accuracy>100)throw new AssertionError("Invalid record");}
   if(target>=17.3166f && ap!=charts.size())throw new AssertionError("Maximum must restore every chart to AP");
   if(target==16 && (ap<3 || ap>charts.size()/2))throw new AssertionError("Target16 must preserve some AP and replace most AP");
   System.out.println("target="+target+" actual="+actual+" AP="+ap);
  }
  for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,18}) {
   try{RksPlanner.generate(charts,invalid);throw new AssertionError("Invalid target accepted");}catch(IllegalArgumentException expected){}
  }
  System.out.println("PASS: native float formula, decimal targets, majority non-AP, achievable bounds");
 }
}

package com.phigros.offline;
import java.util.*;
public strictfp final class RksPlanner {
    public static final class Chart {
        public final String key;public final float difficulty;
        public Chart(String k,float d){key=k;difficulty=d;}
    }
    public static final class Record {
        public final Chart chart;public float accuracy;public int score;public boolean ap;public int combo;
        Record(Chart c,float a,boolean p){chart=c;accuracy=a;ap=p;}
        public String json(){return "{\"s\":"+score+",\"a\":"+Float.toString(accuracy)+",\"c\":"+combo+"}";}
    }
    public static float contribution(float difficulty,float accuracy) {
        if(accuracy<=70f)return 0f;
        float ratio=(accuracy-55f)/45f;return ratio*ratio*difficulty;
    }
    public static float calculate(List<Record> records) {
        ArrayList<Float> best=new ArrayList<Float>(),phi=new ArrayList<Float>();
        for(Record r:records){if(r.accuracy>70f)best.add(contribution(r.chart.difficulty,r.accuracy));
            if(Math.abs(r.accuracy-100f)<0.0001)phi.add(r.chart.difficulty);}
        Collections.sort(best,Collections.reverseOrder());Collections.sort(phi,Collections.reverseOrder());
        float sum=0f,apSum=0f;for(int i=0;i<Math.min(27,best.size());i++)sum+=best.get(i);
        for(int i=0;i<Math.min(3,phi.size());i++)apSum+=phi.get(i);
        return (sum+apSum)/30f;
    }
    public static float maximum(List<Chart> charts) {
        ArrayList<Record> all=new ArrayList<Record>();for(Chart c:charts)all.add(new Record(c,100,true));return calculate(all);
    }
    public static float minimumPositive(List<Chart> charts) {
        float minimum=Float.POSITIVE_INFINITY;for(Chart c:charts)minimum=Math.min(minimum,c.difficulty);
        return minimum/270f;
    }
    private static double variation(String s) {return (Integer.toUnsignedLong(s.hashCode())%10007)/10007.0;}
    public static List<Record> allAP(List<Chart> charts) {
        ArrayList<Record> records=new ArrayList<Record>();
        for(Chart c:charts)records.add(new Record(c,100f,true));
        finishScores(records);return records;
    }
    private static List<Record> profile(List<Chart> charts,Set<String> fixed,float target,double scale) {
        ArrayList<Record> result=new ArrayList<Record>();
        for(Chart c:charts) {
            boolean ap=fixed.contains(c.key);float acc;
            if(ap)acc=100f;
            else {
                double potential=Math.min(c.difficulty,Math.max(0,target+0.7))*(0.93+0.07*variation(c.key));
                double rks=Math.min(c.difficulty,scale*potential);
                acc=(float)Math.min(99.9998,55+45*Math.sqrt(rks/c.difficulty));
                if(acc<=70f)acc=(float)(54+15*variation(c.key+"low"));
            }
            result.add(new Record(c,acc,ap));
        }
        return result;
    }
    public static List<Record> generate(List<Chart> charts,float target) {
        float maximum=maximum(charts);
        float shownMaximum=Math.round(maximum*100f)/100f;
        if(charts.size()<30 || !Float.isFinite(target) || target<0 || target>shownMaximum)
            throw new IllegalArgumentException("目标 RKS 超出此版本可达范围");
        // The menu accepts four decimals; the displayed upper edge means restore
        // all histories, not merely the B27/AP3 subset that determines RKS.
        if(target>=(float)(Math.floor(maximum*10000.0)/10000.0))return allAP(charts);
        if(target>0 && target<minimumPositive(charts))throw new IllegalArgumentException("该低 RKS 在真实公式中不可达");
        ArrayList<Chart> sorted=new ArrayList<Chart>(charts);
        Collections.sort(sorted,new Comparator<Chart>(){public int compare(Chart a,Chart b){return Float.compare(b.difficulty,a.difficulty);}});
        if(target>0 && target<2) {
            // For a very low RKS, only a few records should contribute at all.
            float need=target*30f,capacity=0;Set<String> active=new HashSet<String>();
            if(need<=sorted.get(0).difficulty) {
                Chart one=sorted.get(0);
                for(Chart c:sorted)if(c.difficulty>=need)one=c;
                active.add(one.key);capacity=one.difficulty;
            } else for(Chart c:sorted){active.add(c.key);capacity+=c.difficulty;if(capacity>=need)break;}
            float acc=(float)Math.min(99.9998,Math.max(70.00001,55+45*Math.sqrt(need/capacity)));
            ArrayList<Record> low=new ArrayList<Record>();
            for(Chart c:charts)low.add(new Record(c,active.contains(c.key)?acc:(float)(54+15*variation(c.key+"low")),false));
            if(Math.abs(calculate(low)-target)>0.00004f)throw new IllegalArgumentException("该低 RKS 在真实公式中不可达");
            finishScores(low);return low;
        }
        Set<String> fixed=new HashSet<String>();
        if(target>0) {
            int phi=0;for(Chart c:sorted)if(c.difficulty<=target || target>=16.9f){fixed.add(c.key);if(++phi==3)break;}
            for(Chart c:charts)if(c.difficulty<=target-1.4f && variation(c.key+"phi")<0.16)fixed.add(c.key);
        }
        // At the attainable upper edge, allow additional AP on the hardest charts.
        for(Chart c:sorted) {
            if(calculate(profile(charts,fixed,target,16))>=target)break;
            fixed.add(c.key);
        }
        double lo=0,hi=16;
        for(int i=0;i<60;i++) {double mid=(lo+hi)/2;if(calculate(profile(charts,fixed,target,mid))<=target)lo=mid;else hi=mid;}
        List<Record> result=profile(charts,fixed,target,lo);
        // Accuracy <=70 contributes zero, so the function has small jumps.
        // Bridge a jump by tuning one already-ranked non-AP record, using the same float formula.
        for(int pass=0;pass<4 && Math.abs(calculate(result)-target)>0.00001f;pass++) {
            float before=calculate(result);if(before>target)break;
            ArrayList<Record> best=new ArrayList<Record>(result);
            Collections.sort(best,new Comparator<Record>(){public int compare(Record a,Record b){return Float.compare(contribution(b.chart.difficulty,b.accuracy),contribution(a.chart.difficulty,a.accuracy));}});
            Record tune=null;float room=0;
            for(int i=0;i<Math.min(27,best.size());i++) {
                Record r=best.get(i);float head=contribution(r.chart.difficulty,99.9998f)-contribution(r.chart.difficulty,r.accuracy);
                if(!r.ap && r.accuracy>70 && head>room){tune=r;room=head;}
            }
            if(tune==null)break;
            float a=tune.accuracy,b=99.9998f;
            for(int i=0;i<40;i++){float mid=(a+b)/2;tune.accuracy=mid;if(calculate(result)<target)a=mid;else b=mid;}
            tune.accuracy=a;float errA=Math.abs(calculate(result)-target);tune.accuracy=b;
            if(Math.abs(calculate(result)-target)>errA)tune.accuracy=a;
        }
        if(Math.abs(calculate(result)-target)>0.00004f)throw new IllegalArgumentException("该目标无法按真实公式精确生成，请换一个小数值");
        finishScores(result);return result;
    }
    private static void finishScores(List<Record> result) {
        for(Record r:result) {
            if(r.ap){r.score=1000000;r.combo=1;}
            else {
                // Synthetic local history, 90% accuracy + 10% max-combo component.
                boolean fc=r.accuracy>=92 && variation(r.chart.key+"fc")<0.2;
                float comboRatio=fc?1f:(float)(Math.max(0.2,0.65+0.34*variation(r.chart.key+"combo")));
                r.score=Math.min(999999,Math.max(0,Math.round(9000f*r.accuracy+100000f*comboRatio)));
                r.combo=fc?1:0;
            }
        }
    }
}

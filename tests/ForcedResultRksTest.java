package com.phigros.offline;

import java.util.ArrayList;
import java.util.List;

/**
 * 强制结算写入历史后，RKS 必须跟着变——这是用户选定的行为（显示 + 历史 + 影响 RKS）。
 *
 * 游戏里 RKS 不是独立字段，而是由全部谱面记录派生出来的（见 RksPlanner.calculate：
 * 取前 27 个最高贡献加前 3 个 AP 定数，再除以 30）。所以「写入一条强制成绩」的效果
 * 等价于「RKS 随之变化」，不需要另写一个 RKS 字段。这组测试把这个派生关系固定下来，
 * 避免以后有人误以为要单独改 RKS 而重复写入。
 */
public class ForcedResultRksTest {
    private static int failures;

    private static void check(boolean condition, String label) {
        if (!condition) {
            System.out.println("FAIL " + label);
            failures++;
        }
    }

    private static RksPlanner.Record record(float difficulty, float accuracy, int score) {
        RksPlanner.Record record = new RksPlanner.Record(
            new RksPlanner.Chart("chart-" + difficulty + "-" + accuracy, difficulty), accuracy, accuracy >= 99.999f);
        record.score = score;
        record.combo = 1000;
        return record;
    }

    public static void main(String[] args) {
        // ---- RKS rises when a forced high-accuracy record is inserted ---------
        List<RksPlanner.Record> baseline = new ArrayList<RksPlanner.Record>();
        for (int i = 0; i < 30; i++) baseline.add(record(10f, 80f, 800000));
        float before = RksPlanner.calculate(baseline);

        List<RksPlanner.Record> forced = new ArrayList<RksPlanner.Record>(baseline);
        forced.add(record(16f, 100f, 1000000));
        float after = RksPlanner.calculate(forced);
        check(after > before, "forced AP record raises RKS");

        // ---- an impossible score does not break the calculation --------------
        List<RksPlanner.Record> huge = new ArrayList<RksPlanner.Record>(baseline);
        huge.add(record(15f, 100f, 99999999));
        float hugeRks = RksPlanner.calculate(huge);
        check(Float.isFinite(hugeRks), "impossible score keeps RKS finite");
        check(hugeRks >= before, "impossible score does not lower RKS");

        // ---- a forced low-accuracy record does not raise RKS -----------------
        List<RksPlanner.Record> low = new ArrayList<RksPlanner.Record>(baseline);
        low.add(record(16f, 40f, 400000));
        float lowRks = RksPlanner.calculate(low);
        check(lowRks <= before + 1e-4f, "forced low-accuracy record does not raise RKS");

        // ---- the calculation is order-independent ----------------------------
        List<RksPlanner.Record> shuffled = new ArrayList<RksPlanner.Record>(forced);
        java.util.Collections.reverse(shuffled);
        check(Math.abs(RksPlanner.calculate(shuffled) - after) < 1e-4f, "RKS is order-independent");

        // ---- the AP bonus only counts three charts ---------------------------
        List<RksPlanner.Record> manyAp = new ArrayList<RksPlanner.Record>();
        for (int i = 0; i < 30; i++) manyAp.add(record(10f + i * 0.1f, 100f, 1000000));
        float allAp = RksPlanner.calculate(manyAp);
        float maximum = RksPlanner.maximum(charts(manyAp));
        check(Math.abs(allAp - maximum) < 1e-3f, "all-AP equals the catalogue maximum");

        if (failures == 0) {
            System.out.println("PASS forced result feeds RKS through the derived record path "
                + "(before=" + before + ", after=" + after + ")");
        } else {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
    }

    private static List<RksPlanner.Chart> charts(List<RksPlanner.Record> records) {
        List<RksPlanner.Chart> charts = new ArrayList<RksPlanner.Chart>();
        for (RksPlanner.Record record : records) charts.add(record.chart);
        return charts;
    }
}

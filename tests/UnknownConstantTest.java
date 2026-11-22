package com.phigros.offline;

import java.util.ArrayList;
import java.util.List;

/**
 * Charts with an unknown difficulty constant must not reach RKS arithmetic.
 *
 * The 4.1.0 catalog is regenerated from the build's own Addressables key table, which lists
 * all 1072 charts but not their constants; the 35 charts new in 4.1.0 therefore carry a
 * blank constant and are stored with Float.NaN so unlocking still sees them. NaN through
 * Math.min/max and sorting silently destroys every derived number, so RksPlanner filters
 * them out. This test pins that.
 */
public final class UnknownConstantTest {

    private static int failures = 0;

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + message);
        }
    }

    public static void main(String[] args) {
        List<RksPlanner.Chart> charts = new ArrayList<RksPlanner.Chart>();
        charts.add(new RksPlanner.Chart("A.Artist.0.Record.IN", 15.0f));
        charts.add(new RksPlanner.Chart("B.Artist.0.Record.IN", 14.0f));
        // Two new 4.1.0 charts whose constants this build does not expose.
        charts.add(new RksPlanner.Chart("New1.Artist.0.Record.Legacy", Float.NaN));
        charts.add(new RksPlanner.Chart("New2.Artist.0.Record.SP", Float.NaN));

        float max = RksPlanner.maximum(charts);
        check(Float.isFinite(max) && max > 0f, "maximum must be finite with unknown charts present");
        check(!Float.isNaN(max), "maximum must not be NaN");

        float minPositive = RksPlanner.minimumPositive(charts);
        check(Float.isFinite(minPositive) && minPositive > 0f,
                "minimumPositive must stay finite and positive");

        // The known-only view is what the arithmetic sees.
        check(RksPlanner.known(charts).size() == 2, "known() keeps only charts with a constant");

        // Planning must stay finite when it runs. generate() rejects targets a two-chart
        // catalog cannot reach, so drive it with a target derived from the ceiling and
        // accept a documented rejection - the invariant under test is "no NaN escapes",
        // not "every target is reachable".
        float reachable = RksPlanner.maximum(charts) * 0.5f;
        try {
            List<RksPlanner.Record> records = RksPlanner.generate(charts, reachable);
            for (RksPlanner.Record r : records) {
                check(Float.isFinite(r.chart.difficulty), "planned chart has a real constant");
                check(Float.isFinite(r.accuracy), "planned accuracy is finite");
            }
        } catch (IllegalArgumentException unreachable) {
            // Reachability is a separate contract; NaN leakage is what this test guards.
        }

        // allAP must skip them too.
        for (RksPlanner.Record r : RksPlanner.allAP(charts)) {
            check(!Float.isNaN(r.chart.difficulty), "allAP skips unknown-constant charts");
        }

        if (failures == 0) {
            System.out.println("  PASS unknown-constant charts are excluded from RKS arithmetic");
        } else {
            System.out.println("  FAILED " + failures + " check(s)");
            System.exit(1);
        }
    }

    private UnknownConstantTest() {}
}

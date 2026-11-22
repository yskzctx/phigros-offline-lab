package com.phigros.offline;

/** 判定保护 mode 3 与强制结算显示的回归测试（纯逻辑，无需 Android）。 */
public class GuardAndForcedResultTest {
    private static int failures;

    private static void check(boolean condition, String label) {
        if (!condition) {
            System.out.println("FAIL " + label);
            failures++;
        }
    }

    private static void reject(String label, Runnable action) {
        try {
            action.run();
            System.out.println("FAIL " + label + " was accepted");
            failures++;
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        // ---- mode 3 is a real mode, not an error ------------------------------
        ControlsConfig fc = ControlsConfig.parse(3, "1000000", "100", "", "2", "0");
        check(fc.mode == 3, "mode 3 accepted");
        check(fc.guardKind == 2, "protect FC kind");
        check(fc.guardScore == 0, "protect FC has no score floor");

        ControlsConfig ap = ControlsConfig.parse(3, "", "", "", "1", "");
        check(ap.guardKind == 1, "protect AP accepts an empty floor");

        ControlsConfig score = ControlsConfig.parse(3, "", "", "", "3", "990000");
        check(score.guardKind == 3 && score.guardScore == 990000, "protect score floor");

        // ---- protection parameters never leak into other modes ----------------
        check(ControlsConfig.parse(0, "1000000", "100", "").guardKind == 0, "mode 0 clears guard");
        check(ControlsConfig.parse(1, "900000", "", "").guardKind == 0, "mode 1 clears guard");
        check(ControlsConfig.parse(2, "", "90.25", "").guardKind == 0, "mode 2 clears guard");

        // ---- invalid protection is rejected, not silently downgraded ---------
        reject("guard kind 0", () -> ControlsConfig.parse(3, "", "", "", "0", "0"));
        reject("guard kind 4", () -> ControlsConfig.parse(3, "", "", "", "4", "0"));
        reject("protect score without floor", () -> ControlsConfig.parse(3, "", "", "", "3", "0"));
        reject("protect score over range", () -> ControlsConfig.parse(3, "", "", "", "3", "1000001"));
        reject("unknown mode 4", () -> ControlsConfig.parse(4, "", "", ""));

        // ---- forced result parsing accepts impossible combinations -----------
        ForcedResult.Values odd = ForcedResult.parse(true, "1234567", "33.33", "11", "22", "33", "44", "55");
        check(odd.enabled, "forced enabled");
        check(odd.score == 1234567, "forced score kept verbatim");
        check(odd.accuracy == 3333, "forced accuracy to basis points");
        check(odd.perfect == 11 && odd.good == 22 && odd.bad == 33 && odd.miss == 44, "forced counts kept");
        check(odd.combo == 55, "forced combo kept");

        // An impossible combination must survive: more good+bad+miss than notes.
        ForcedResult.Values impossible = ForcedResult.parse(true, "1", "0", "999", "999", "999", "999", "999");
        check(impossible.perfect == 999 && impossible.miss == 999, "impossible combination accepted");

        // ---- Good early/late are settable, and default to the old behaviour -----
        ForcedResult.Values plain = ForcedResult.parse(true, "100", "99", "10", "5", "0", "0", "15");
        check(!plain.earlyLateOn && plain.early == 0 && plain.late == 0,
              "early/late default off, so an untouched config behaves as before");

        ForcedResult.Values timing = ForcedResult.parse(true, "100", "99", "10", "5", "0", "0", "15",
                                                        true, "3", "2");
        check(timing.earlyLateOn, "early/late opt-in honoured");
        check(timing.early == 3 && timing.late == 2, "early/late values kept");

        reject("early over range", () -> ForcedResult.parse(true, "1", "", "", "", "", "", "", true, "1000000", ""));
        reject("negative late", () -> ForcedResult.parse(true, "1", "", "", "", "", "", "", true, "", "-1"));

        // ---- settlement symbol: 0 none, 1 Phi, 2 FC --------------------------
        ForcedResult.Values symAuto = ForcedResult.parse(true, "100", "99", "10", "5", "1", "1", "15", false, "", "", 0);
        check(symAuto.symbol == 0, "symbol defaults to no override");
        ForcedResult.Values symPhi = ForcedResult.parse(true, "100", "100", "10", "0", "0", "0", "15", false, "", "", 1);
        check(symPhi.symbol == 1, "symbol Phi selected");
        reject("symbol out of range", () -> ForcedResult.parse(true, "1", "", "", "", "", "", "", false, "", "", 3));
        reject("negative symbol", () -> ForcedResult.parse(true, "1", "", "", "", "", "", "", false, "", "", -1));

        // A score far beyond the real maximum is still accepted.
        ForcedResult.Values huge = ForcedResult.parse(true, "99999999", "100", "", "", "", "", "");
        check(huge.score == 99999999, "score above real maximum accepted");

        // ---- forced result validation still guards its own ranges -----------
        reject("accuracy over 100", () -> ForcedResult.parse(true, "1", "100.01", "", "", "", "", ""));
        reject("negative score", () -> ForcedResult.parse(true, "-1", "", "", "", "", "", ""));
        reject("non-numeric count", () -> ForcedResult.parse(true, "1", "", "abc", "", "", "", ""));
        reject("count over range", () -> ForcedResult.parse(true, "1", "", "1000000", "", "", "", ""));

        // ---- accuracy formatting round-trips ---------------------------------
        check("99.5".equals(ForcedResult.formatAccuracy(9950)), "format 99.50");
        check(ForcedResult.parseAccuracy("99.50") == 9950, "parse 99.50");
        check(ForcedResult.parseAccuracy("0") == 0, "parse 0");
        check(ForcedResult.parseAccuracy("100") == 10000, "parse 100");

        if (failures == 0) {
            System.out.println("PASS guard mode 3, protection isolation, forced-result parsing "
                + "and impossible-combination acceptance");
        } else {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
    }
}

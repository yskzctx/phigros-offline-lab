package com.phigros.offline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 皮肤包「非图片」样式的回归测试。
 *
 * 这一组功能全部通过游戏自己的 PlayerPrefs 实现，不需要原生偏移，所以可以纯
 * 逻辑测试：解析 info.yml 字段、拒绝非法值、只写包内提供的项。
 */
public class SkinStyleTest {
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

    private static Map<String, String> meta(String... pairs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    public static void main(String[] args) {
        // ---- a bare image-only pack changes nothing else ---------------------
        SkinStyle.Style bare = SkinStyle.fromMeta(meta());
        check(bare.isEmpty(), "image-only pack is empty");
        check(SkinStyle.summary(bare).contains("不改动"), "image-only summary says nothing changes");

        // ---- every supported key is picked up -------------------------------
        SkinStyle.Style full = SkinStyle.fromMeta(meta(
            "hitFxIsOn", "true",
            "hitFxVolume", "80",
            "soundVolume", "55",
            "noteScale", "120",
            "lineColor", "0xFF00FF00",
            "colorPerfect", "0xFFFFD700",
            "colorGood", "0xFF87CEEB"));
        check(Boolean.TRUE.equals(full.hitFxEnabled), "hitFxIsOn parsed");
        check(full.hitFxVolume == 80, "hitFxVolume parsed");
        check(full.soundVolume == 55, "soundVolume parsed");
        check(full.noteScale == 120, "noteScale parsed");
        check(full.lineColor != null && full.lineColor == 0xFF00FF00, "lineColor parsed");
        check(full.colorPerfect != null && full.colorPerfect == 0xFFFFD700, "colorPerfect parsed");
        check(full.colorGood != null && full.colorGood == 0xFF87CEEB, "colorGood parsed");
        check(!full.isEmpty(), "full style is not empty");

        // ---- colour accepts decimal and hex, rejects out of range -----------
        check(SkinStyle.fromMeta(meta("lineColor", "4278255360")).lineColor == 0xFF00FF00,
              "decimal colour accepted");
        reject("colour over 32 bits", () -> SkinStyle.fromMeta(meta("lineColor", "0x1FFFFFFFF")));
        reject("colour not a number", () -> SkinStyle.fromMeta(meta("lineColor", "green")));

        // ---- percentages are bounded ----------------------------------------
        reject("hitFxVolume over 100", () -> SkinStyle.fromMeta(meta("hitFxVolume", "101")));
        reject("soundVolume negative", () -> SkinStyle.fromMeta(meta("soundVolume", "-1")));
        reject("noteScale not numeric", () -> SkinStyle.fromMeta(meta("noteScale", "loud")));

        // ---- an explicit "off" is a real instruction, not "unset" -----------
        SkinStyle.Style off = SkinStyle.fromMeta(meta("hitFxIsOn", "false"));
        check(Boolean.FALSE.equals(off.hitFxEnabled), "explicit false kept");
        check(!off.isEmpty(), "explicit false still counts as a change");

        // ---- summary names what changes -------------------------------------
        String text = SkinStyle.summary(full);
        check(text.contains("特效音量 80%"), "summary names hitFxVolume");
        check(text.contains("音效音量 55%"), "summary names soundVolume");
        check(text.contains("音符缩放 120%"), "summary names noteScale");
        check(text.contains("判定线颜色"), "summary names line colour");

        if (failures == 0) {
            System.out.println("PASS skin style: parse, bounds, explicit-off and summary");
        } else {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
    }
}

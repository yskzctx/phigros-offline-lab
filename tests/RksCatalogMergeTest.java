package com.phigros.offline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RksCatalogMergeTest {
    public static void main(String[] args) throws Exception {
        String current = "<?xml version='1.0'?><map>"
            + "<string name=\"existing\">player-value</string>"
            + "<string name=\"unknown-local\">keep-me</string></map>";
        String seed = "<map>"
            + "<string name=\"existing\">ap-value</string>"
            + "<string name=\"new-chart\">new-ap</string></map>";
        String merged = CatalogXmlMerge.mergeMissing(current, seed);
        require(merged.contains("name=\"existing\">player-value</string>"), "existing record changed");
        require(merged.contains("name=\"unknown-local\">keep-me</string>"), "unknown local record removed");
        require(merged.contains("name=\"new-chart\">new-ap</string>"), "new catalogue record missing");
        require(merged.indexOf("</map>") == merged.lastIndexOf("</map>"), "map close element duplicated");
        require(merged.equals(CatalogXmlMerge.mergeMissing(merged, seed)), "merge is not idempotent");

        if (args.length == 2) {
            String live = new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8);
            String asset = new String(Files.readAllBytes(Path.of(args[1])), StandardCharsets.UTF_8);
            String all = CatalogXmlMerge.mergeMissing(live, asset);
            require(all.contains("</map>"), "live XML invalid");
        }
        System.out.println("PASS: incremental catalogue merge preserves existing history, appends missing charts, and is idempotent");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

package com.phigros.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Round-trip test for save import / export.
 *
 * The picker and the Activity need a device, but everything that can go wrong quietly -
 * the ZIP layout, the validation rules, the backup-before-write order, the restore cycle -
 * is plain file I/O. Those are exercised here against a temp directory, so the feature is
 * actually executed rather than assumed to work.
 */
public final class SaveTransferTest {

    private static int failures = 0;
    private static File root;

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + message);
        }
    }

    private static final String SAVE =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
            + "<map>\n"
            + "    <string name=\"playerID\">abc123</string>\n"
            + "    <string name=\"Song.Artist.0.Record.IN\">deadbeef</string>\n"
            + "    <string name=\"offset\">120</string>\n"
            + "</map>\n";

    private static final String REPLACEMENT =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
            + "<map>\n"
            + "    <string name=\"playerID\">zzz999</string>\n"
            + "</map>\n";

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("savetransfer").toFile();
        File prefs = new File(root, "shared_prefs");
        prefs.mkdirs();
        File save = new File(prefs, "com.PigeonGames.Phigros.v2.playerprefs.xml");
        writeBytes(save, SAVE.getBytes(StandardCharsets.UTF_8));
        File backups = new File(new File(root, "files"), "save-transfer/backups");

        // ---- 1. inspect a raw XML -------------------------------------------
        SaveTransfer.Plan plan = SaveTransfer.inspect(stream(SAVE));
        check(plan.entries == 3, "raw XML should report 3 entries, got " + plan.entries);
        check(plan.source != null && !plan.source.isEmpty(), "source label is set");

        // ---- 2. export produces a real ZIP with the save inside -------------
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        SaveTransfer.exportFrom(save, zip);
        byte[] bytes = zip.toByteArray();
        check(bytes.length > 0, "export produced bytes");
        check(bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K', "export is a ZIP");

        List<String> names = new ArrayList<String>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes));
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) names.add(entry.getName());
        } finally {
            zin.close();
        }
        boolean hasSave = false;
        for (String name : names) if (name.endsWith("playerprefs.xml")) hasSave = true;
        check(hasSave, "exported ZIP contains the save, names=" + names);
        check(names.contains("phigros-save.txt"), "exported ZIP contains the manifest");

        // ---- 3. the export re-imports ---------------------------------------
        SaveTransfer.Plan replan = SaveTransfer.inspect(new ByteArrayInputStream(bytes));
        check(replan.entries == 3, "exported ZIP re-imports 3 entries, got " + replan.entries);

        // ---- 4. apply writes and keeps a backup ------------------------------
        SaveTransfer.Plan incoming = SaveTransfer.inspect(stream(REPLACEMENT));
        File backup = SaveTransfer.applyTo(save, incoming, backups);
        check(backup != null && backup.isFile(), "apply created a backup");
        String afterApply = new String(readAll(save), StandardCharsets.UTF_8);
        check(afterApply.contains("zzz999"), "apply wrote the imported save");

        // ---- 5. restore brings the original back ----------------------------
        SaveTransfer.restoreTo(save, backup, backups);
        String afterRestore = new String(readAll(save), StandardCharsets.UTF_8);
        check(afterRestore.contains("abc123"), "restore recovered the original save");
        check(afterRestore.equals(SAVE), "restore is byte-identical to the original");

        // ---- 6. a restore cannot escape the backup directory ----------------
        File outside = new File(root, "outside.xml");
        writeBytes(outside, REPLACEMENT.getBytes(StandardCharsets.UTF_8));
        boolean rejected = false;
        try {
            SaveTransfer.restoreTo(save, outside, backups);
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "restore refuses a file outside the backup directory");

        // ---- 7. bad input is refused ----------------------------------------
        check(rejects(""), "empty input refused");
        check(rejects("hello world"), "non-save text refused");
        check(rejects("<map></map>"), "save with no entries refused");

        if (failures == 0) {
            System.out.println("  PASS save transfer round-trip (inspect/export/apply/restore/refuse)");
        } else {
            System.out.println("  FAILED " + failures + " check(s)");
            System.exit(1);
        }
    }

    private static boolean rejects(String payload) {
        try {
            SaveTransfer.inspect(stream(payload));
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static void writeBytes(File file, byte[] data) throws IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(File file) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private SaveTransferTest() {}
}

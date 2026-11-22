package com.phigros.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Import and export of the local save.
 *
 * The save is the game's own PlayerPrefs XML, whose entry names and values are encrypted
 * with the game's key (see RksEditor). Two shapes are accepted so a user can move data
 * between builds without hand-editing:
 *
 *   * a raw playerprefs XML exactly as the game writes it - copied in verbatim;
 *   * a portable ZIP produced by this class, which also carries a manifest naming the
 *     source build and the entry count so an import can be sanity-checked first.
 *
 * Importing replaces the save wholesale, so every entry is validated before anything is
 * written and the previous save is backed up first. Nothing here decrypts or re-encrypts
 * values: the XML is copied as the game wrote it, which is what keeps account identity
 * and settings intact.
 */
public final class SaveTransfer {

    /** The game's preference file, relative to the app data directory. */
    static final String PREF_NAME = "com.PigeonGames.Phigros.v2.playerprefs.xml";
    private static final String MANIFEST = "phigros-save.txt";
    private static final int MAX_ENTRIES = 200000;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final Pattern ENTRY =
            Pattern.compile("<string\\b[^>]*\\bname=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern MAP_TAG = Pattern.compile("<(/?)(map|set)\\b", Pattern.CASE_INSENSITIVE);

    private SaveTransfer() {}

    static File prefsFile(Context c) {
        return new File(new File(c.getApplicationInfo().dataDir, "shared_prefs"), PREF_NAME);
    }

    static File backupDir(Context c) {
        return new File(new File(c.getFilesDir(), "save-transfer"), "backups");
    }

    /** Summary shown in the panel: what is currently on disk. */
    public static String summary(Context c) {
        File save = prefsFile(c);
        if (!save.isFile()) return "当前没有本地存档文件";
        try {
            String raw = read(save);
            int entries = ENTRY.matcher(raw).groupCount() >= 0 ? countEntries(raw) : 0;
            return String.format("本地存档：" + entries + " 项 · " + (save.length() / 1024) + " KB");
        } catch (IOException e) {
            return "本地存档无法读取：" + e.getMessage();
        }
    }

    private static int countEntries(String raw) {
        Matcher m = ENTRY.matcher(raw);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Read and validate a save blob without applying it.
     *
     * A raw XML must parse as a PlayerPrefs map; a ZIP must contain the preference file.
     * The returned plan is what the UI confirms before the write happens.
     */
    public static Plan inspect(InputStream in) throws IOException {
        byte[] data = drain(in, MAX_BYTES);
        if (data.length == 0) throw new IOException("所选文件为空");
        Plan plan = new Plan();
        if (isZip(data)) {
            Map<String, byte[]> members = unzip(data);
            byte[] xml = members.get(PREF_NAME);
            if (xml == null) {
                // Accept any single playerprefs.xml inside the archive.
                for (Map.Entry<String, byte[]> e : members.entrySet()) {
                    if (e.getKey().endsWith("playerprefs.xml")) { xml = e.getValue(); break; }
                }
            }
            if (xml == null) throw new IOException("压缩包内没有 playerprefs.xml");
            plan.xml = xml;
            String manifest = members.containsKey(MANIFEST)
                    ? new String(members.get(MANIFEST), StandardCharsets.UTF_8) : "";
            plan.source = manifest.isEmpty() ? "外部压缩包" : firstLine(manifest);
        } else {
            plan.xml = data;
            plan.source = "原始存档 XML";
        }
        String text = new String(plan.xml, StandardCharsets.UTF_8);
        if (!text.contains("<map") && !text.contains("<set")) {
            throw new IOException("不是有效的存档文件（缺少 PlayerPrefs 结构）");
        }
        plan.entries = countEntries(text);
        if (plan.entries == 0) throw new IOException("存档里没有任何条目");
        if (plan.entries > MAX_ENTRIES) throw new IOException("存档条目过多，已拒绝");
        return plan;
    }

    /** A validated import, ready to apply. */
    public static final class Plan {
        public byte[] xml;
        public String source = "";
        public int entries;

        public String describe() {
            return "来源：" + source + "\n条目：" + entries + " 项\n大小：" + (xml.length / 1024) + " KB";
        }
    }

    /**
     * Apply a validated plan: back up the current save, then replace it atomically.
     *
     * The backup is kept so an import that turns out wrong can be undone from the same
     * panel rather than by reinstalling.
     */
    public static File apply(Context c, Plan plan) throws IOException {
        return applyTo(prefsFile(c), plan, backupDir(c));
    }

    /** File-level apply, so the backup/write cycle can be tested without a device. */
    static File applyTo(File save, Plan plan, File dir) throws IOException {
        if (plan == null || plan.xml == null || plan.xml.length == 0) {
            throw new IOException("没有可导入的内容");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建存档备份目录");

        File backup = null;
        if (save.isFile() && save.length() > 0) {
            backup = new File(dir, "before-import-" + System.currentTimeMillis() + ".xml");
            copyAtomic(new FileInputStream(save), backup, MAX_BYTES);
        }
        // The game rewrites this file on exit; writing it while the game holds it cached
        // would be lost, so the panel tells the user to restart afterwards.
        copyAtomic(new ByteArrayInputStream(plan.xml), save, MAX_BYTES);
        Log.i("PhigrosOffline", "save.import entries=" + plan.entries + " from=" + plan.source);
        return backup;
    }

    /** List backups newest first, for the restore list. */
    public static List<File> backups(Context c) {
        List<File> result = new ArrayList<File>();
        File[] files = backupDir(c).listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().endsWith(".xml")) result.add(f);
        }
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    /** Restore one of our own backups, after backing up what is there now. */
    public static void restore(Context c, File backup) throws IOException {
        restoreTo(prefsFile(c), backup, backupDir(c));
    }

    /** File-level restore, so the safety-backup cycle can be tested without a device. */
    static void restoreTo(File save, File backup, File dir) throws IOException {
        if (!backup.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
            throw new IOException("备份路径无效");
        }
        if (!backup.isFile()) throw new IOException("备份文件不存在");
        if (save.isFile() && save.length() > 0) {
            File safety = new File(dir, "before-restore-" + System.currentTimeMillis() + ".xml");
            copyAtomic(new FileInputStream(save), safety, MAX_BYTES);
        }
        copyAtomic(new FileInputStream(backup), save, MAX_BYTES);
        Log.i("PhigrosOffline", "save.restore from=" + backup.getName());
    }

    /** Labels for the restore list: file name plus a readable size. */
    public static String label(File backup) {
        String name = backup.getName();
        long kb = Math.max(1, backup.length() / 1024);
        // Turn before-import-1712345678.xml into a readable time.
        java.util.regex.Matcher m = Pattern.compile("(\\d{10,})").matcher(name);
        if (m.find()) {
            long stamp = Long.parseLong(m.group(1));
            String kind = name.startsWith("before-import") ? "导入前" : "恢复前";
            return kind + " " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                    .format(new java.util.Date(stamp)) + " · " + kb + " KB";
        }
        return name + " · " + kb + " KB";
    }

    /** Export the current save as a portable ZIP with a manifest. */
    public static void export(Context c, OutputStream out) throws IOException {
        exportFrom(prefsFile(c), out);
    }

    /** File-level export, so the ZIP layout can be tested without a device. */
    static void exportFrom(File save, OutputStream out) throws IOException {
        if (!save.isFile()) throw new IOException("当前没有可导出的存档");
        byte[] xml = drain(new FileInputStream(save), MAX_BYTES);
        String manifest = "Phigros offline save\nentries=" + countEntries(new String(xml, StandardCharsets.UTF_8))
                + "\nexported=" + System.currentTimeMillis() + "\n";
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            zip.putNextEntry(new ZipEntry(PREF_NAME));
            zip.write(xml);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            zip.finish();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.trim();
    }

    private static boolean isZip(byte[] data) {
        return data.length > 4 && data[0] == 'P' && data[1] == 'K';
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            return unzipEntries(zip, members);
        } catch (java.io.EOFException truncated) {
            // A file that starts with PK but is not a complete archive.
            throw new IOException("压缩包不完整或已损坏");
        } finally {
            zip.close();
        }
    }

    private static Map<String, byte[]> unzipEntries(ZipInputStream zip, Map<String, byte[]> members)
            throws IOException {
        ZipEntry entry;
        int count = 0;
        while ((entry = zip.getNextEntry()) != null) {
            if (++count > 64) throw new IOException("压缩包条目过多");
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (name.contains("..")) throw new IOException("压缩包路径异常");
            members.put(name, drainEntry(zip, MAX_BYTES));
        }
        return members;
    }

    private static byte[] drain(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) throw new IOException("文件过大");
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /**
     * Read one ZIP entry without closing the archive.
     *
     * drain() closes its stream, which would end the whole ZipInputStream after the first
     * entry - so an export with a save plus a manifest would only ever yield one member.
     */
    private static byte[] drainEntry(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) throw new IOException("文件过大");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String read(File file) throws IOException {
        return new String(drain(new FileInputStream(file), MAX_BYTES), StandardCharsets.UTF_8);
    }

    private static void copyAtomic(InputStream in, File target, long limit) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录");
        }
        File temp = new File(parent, target.getName() + ".pending");
        try {
            byte[] data = drain(in, limit);
            try (OutputStream out = new java.io.FileOutputStream(temp)) {
                out.write(data);
                out.flush();
                if (out instanceof java.io.FileOutputStream) {
                    ((java.io.FileOutputStream) out).getFD().sync();
                }
            }
            java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(temp.toPath());
        }
    }
}

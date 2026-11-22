package com.phigros.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Import / export the local save.
 *
 * Import is two-step on purpose: the picked file is inspected and summarised, and only
 * when the user confirms does anything get written. The previous save is backed up first
 * and listed here, so a wrong import is one tap away from being undone.
 *
 * The game caches its preferences in memory, so the panel says plainly that a full
 * restart is required - writing the file underneath a running game would be overwritten.
 *
 * This activity is declared in the manifest by tools/register_activities.py; launching an
 * undeclared activity throws ActivityNotFoundException, which is why SkinActivity could
 * not open before that step existed.
 */
public final class SaveTransferActivity extends Activity {

    public static final String ACTION_IMPORT = "com.phigros.offline.action.IMPORT_SAVE";
    public static final String ACTION_EXPORT = "com.phigros.offline.action.EXPORT_SAVE";
    private static final int PICK_IMPORT = 181;
    private static final int PICK_EXPORT = 182;

    private TextView status;
    private LinearLayout backups;
    private boolean busy;

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(0xff17283c);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xff2158a6);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffeaf2ff));
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xfff1f5f9);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("导入 / 导出存档", 22);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("返回游戏");
        back.setOnClickListener(v -> finish());
        header.addView(back);
        root.addView(header);

        status = text(SaveTransfer.summary(this), 16);
        root.addView(status);
        root.addView(text("可导入游戏原始的 playerprefs.xml，或本面板导出的 ZIP。"
                + "导入前会自动备份当前存档。导入后请完全退出并重启游戏。", 14));

        LinearLayout actions = new LinearLayout(this);
        Button importButton = button("选择存档文件");
        Button exportButton = button("导出当前存档");
        actions.addView(importButton, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(exportButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        importButton.setOnClickListener(v -> pick(PICK_IMPORT));
        exportButton.setOnClickListener(v -> pick(PICK_EXPORT));

        ScrollView scroll = new ScrollView(this);
        backups = new LinearLayout(this);
        backups.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(backups);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshBackups();

        String action = getIntent() == null ? null : getIntent().getAction();
        if (ACTION_IMPORT.equals(action)) pick(PICK_IMPORT);
        else if (ACTION_EXPORT.equals(action)) pick(PICK_EXPORT);
    }

    private void pick(int request) {
        if (busy) return;
        Intent intent;
        if (request == PICK_IMPORT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        } else {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "phigros-save.zip");
        }
        try {
            startActivityForResult(intent, request);
        } catch (RuntimeException e) {
            showError("无法打开文件选择器：" + e.getMessage());
        }
    }

    private void refreshBackups() {
        backups.removeAllViews();
        List<File> list = SaveTransfer.backups(this);
        if (list.isEmpty()) {
            backups.addView(text("还没有备份。导入时会自动创建。", 14));
            return;
        }
        backups.addView(text("可恢复的备份（点一下即可回退）", 15));
        for (File file : list.subList(0, Math.min(8, list.size()))) {
            Button b = button(SaveTransfer.label(file));
            b.setOnClickListener(v -> confirmRestore(file));
            backups.addView(b, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void confirmRestore(File file) {
        new AlertDialog.Builder(this)
                .setTitle("恢复这个备份？")
                .setMessage(SaveTransfer.label(file) + "\n\n当前存档会先被备份，再写入这个备份。")
                .setPositiveButton("恢复", (d, w) -> {
                    try {
                        SaveTransfer.restore(this, file);
                        refresh();
                        Toast.makeText(this, "已恢复，请完全退出并重启游戏", Toast.LENGTH_LONG).show();
                    } catch (IOException e) {
                        showError(String.valueOf(e.getMessage()));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (request == PICK_IMPORT) {
            busy = true;
            status.setText("正在检查存档…");
            new Thread(() -> {
                String error = null;
                SaveTransfer.Plan plan = null;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取所选文件");
                    plan = SaveTransfer.inspect(in);
                } catch (Throwable e) {
                    error = String.valueOf(e.getMessage());
                }
                final String failure = error;
                final SaveTransfer.Plan inspected = plan;
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    refresh();
                    if (failure != null) showError(failure);
                    else confirmImport(inspected);
                });
            }, "PhigrosSaveInspect").start();
        } else if (request == PICK_EXPORT) {
            busy = true;
            status.setText("正在导出…");
            new Thread(() -> {
                String error = null;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法写入所选位置");
                    SaveTransfer.export(this, out);
                } catch (Throwable e) {
                    error = String.valueOf(e.getMessage());
                }
                final String failure = error;
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    refresh();
                    if (failure != null) showError(failure);
                    else Toast.makeText(this, "存档已导出", Toast.LENGTH_LONG).show();
                });
            }, "PhigrosSaveExport").start();
        }
    }

    private void confirmImport(SaveTransfer.Plan plan) {
        new AlertDialog.Builder(this)
                .setTitle("导入这个存档？")
                .setMessage(plan.describe() + "\n\n当前存档会先备份，可随时恢复。导入后需完全退出并重启游戏。")
                .setPositiveButton("导入", (d, w) -> {
                    try {
                        SaveTransfer.apply(this, plan);
                        refresh();
                        Toast.makeText(this, "已导入，请完全退出并重启游戏", Toast.LENGTH_LONG).show();
                    } catch (IOException e) {
                        showError(String.valueOf(e.getMessage()));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refresh() {
        status.setText(SaveTransfer.summary(this));
        refreshBackups();
    }

    private void showError(String error) {
        new AlertDialog.Builder(this)
                .setTitle("操作未完成")
                .setMessage(error == null ? "未知错误" : error)
                .setPositiveButton("知道了", null)
                .show();
    }
}

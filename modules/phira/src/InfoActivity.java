package com.phira.lab;
import android.app.*;import android.os.Bundle;import android.widget.*;
public final class InfoActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(28,24,28,24);box.setBackgroundColor(0xfff1f5f9);scroll.addView(box);setContentView(scroll);
  TextView title=new TextView(this);title.setText("Phira 本地实验模块");title.setTextSize(25);title.setTextColor(0xff17283c);box.addView(title);
  TextView body=new TextView(this);body.setTextSize(16);body.setTextColor(0xff31445c);body.setText("\n开发候选 0.1 · 尚未真机验收\n\n仅作用于原版 Phira 0.8.2（39）arm64。在 LSPosed 中仅勾选 Phira，随后冷启动。音量上打开面板，下关闭；关闭面板后音量下正常调节音量。\n\n使用全新的独立实验存档，不复制原账号和谱面索引。可以通过原版导入器重新导入谱面、完整皮肤资源包并在原版页面整理。\n\n提供原版标准 AP 自动游玩，冷启动默认关闭。实验游玩强制使用原版离线结算；开启自动游玩后，本次会话使用原版不计成绩标记。未修改成绩完整性检查。\n\n面板可将实验存档内已导入谱面的历史记录设为全 AP，也可恢复修改前的记录；完全退出并冷启动后生效。\n\n目前目标分数、准确率、逐音符规则和 RKS 尚未接入，不能称为修改版全功能移植。\n\n禁用模块并冷启动，恢复原始数据路径；不要覆盖或清除原版数据。安装、真实启动、导入、结算与网络行为仍需验收。");box.addView(body);
 }
}

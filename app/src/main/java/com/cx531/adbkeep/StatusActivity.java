package com.cx531.adbkeep;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 状态页：桌面上看不到（没有 LAUNCHER category），只能手动拉起：
 *
 *   adb shell am start -n com.cx531.adbkeep/.StatusActivity
 *
 * 内容：权限状态、adb_enabled 当前值、最近一次检查/修复时间、总开关。
 */
public class StatusActivity extends Activity {

    private TextView body;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("adbkeeper");
        title.setTextSize(20);
        root.addView(title);

        body = new TextView(this);
        body.setTextSize(14);
        body.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(body);

        Button runNow = new Button(this);
        runNow.setText("立即检查");
        runNow.setOnClickListener(v -> {
            boolean changed = Keeper.ensure(this, "manual");
            render(changed ? "已重新打开 ADB" : "检查完成");
        });
        root.addView(runNow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            boolean next = !Keeper.isEnabled(this);
            Keeper.setEnabled(this, next);
            render(next ? "自动恢复已启用" : "自动恢复已停用");
        });
        root.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 电视屏幕上要撑满宽度，否则内容会缩在左上角一小块
        setContentView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        KeepJobService.schedule(this);
        render(null);
    }

    private void render(String note) {
        int adb = Keeper.adbEnabled(this);
        StringBuilder sb = new StringBuilder();
        if (note != null) {
            sb.append("▶ ").append(note).append("\n\n");
        }
        sb.append("写权限 WRITE_SECURE_SETTINGS: ")
                .append(Keeper.hasPermission(this) ? "已授予" : "未授予（需 adb grant）")
                .append("\n");
        sb.append("adb_enabled: ").append(adb == 1 ? "1（开）" : adb == 0 ? "0（关）" : "读取失败")
                .append("\n");
        sb.append("自动恢复: ").append(Keeper.isEnabled(this) ? "启用" : "停用").append("\n");
        sb.append("最近检查: ").append(Keeper.lastRun(this)).append("\n");
        sb.append("最近修复: ").append(Keeper.lastChange(this)).append("\n");
        body.setText(sb.toString());
        toggle.setText(Keeper.isEnabled(this) ? "停用自动恢复" : "启用自动恢复");
    }
}

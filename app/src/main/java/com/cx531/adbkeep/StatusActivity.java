package com.cx531.adbkeep;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 状态页：桌面上看不到（没有 LAUNCHER category），只能手动拉起：
 *
 *   adb shell am start -n com.cx531.adbkeep/.StatusActivity
 *
 * 打开它就是最可靠的"激活"动作：显式启动会解除首次安装的 stopped 状态，
 * 同时立即检查一次并在 Android 5.0+ 上排期周期任务。
 *
 * 内容：权限状态、adb_enabled 当前值、adbd 是否在监听、最近一次检查/修复时间、总开关。
 */
public class StatusActivity extends Activity {

    private TextView body;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;

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
            render(changed ? "已修复（写入了 adb_enabled=1）" : "检查完成，无需改动");
        });
        root.addView(runNow, new LinearLayout.LayoutParams(wrap, wrap));

        Button kick = new Button(this);
        kick.setText("强制重启 ADB");
        kick.setOnClickListener(v -> {
            Keeper.kickAdbd(this);
            render(Keeper.adbdListening() ? "已重启，端口已在监听" : "已执行重启，端口仍未监听");
        });
        root.addView(kick, new LinearLayout.LayoutParams(wrap, wrap));

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            boolean next = !Keeper.isEnabled(this);
            Keeper.setEnabled(this, next);
            render(next ? "自动恢复已启用" : "自动恢复已停用");
        });
        root.addView(toggle, new LinearLayout.LayoutParams(wrap, wrap));

        // 电视屏幕上要撑满宽度，否则内容会缩在左上角一小块
        setContentView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Keeper.ensure(this, "ui");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            KeepJobService.schedule(this);
        }
        render(null);
    }

    private void render(String note) {
        int adb = Keeper.adbEnabled(this);
        boolean listening = Keeper.adbdListening();
        StringBuilder sb = new StringBuilder();
        if (note != null) {
            sb.append("▶ ").append(note).append("\n\n");
        }
        sb.append("写权限 WRITE_SECURE_SETTINGS: ")
                .append(Keeper.hasPermission(this) ? "已授予" : "未授予（需 adb grant）")
                .append("\n");
        sb.append("adb_enabled: ").append(adb == 1 ? "1（开）" : adb == 0 ? "0（关）" : "读取失败")
                .append("\n");
        sb.append("adbd 监听 127.0.0.1:").append(Keeper.adbPort()).append(": ")
                .append(listening ? "是" : "否").append("\n");
        if (!Keeper.tcpPortConfigured()) {
            sb.append("（未配置 persist.adb.tcp.port，可能只在 USB 上监听，不会自动重启）\n");
        }
        sb.append("自动恢复: ").append(Keeper.isEnabled(this) ? "启用" : "停用").append("\n");
        sb.append("最近检查: ").append(Keeper.lastRun(this)).append("\n");
        sb.append("最近修复: ").append(Keeper.lastChange(this)).append("\n");
        body.setText(sb.toString());
        toggle.setText(Keeper.isEnabled(this) ? "停用自动恢复" : "启用自动恢复");
    }
}

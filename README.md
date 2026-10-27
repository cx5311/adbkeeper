# adbkeeper

**极简 Android 工具（14 KB）：让 `adb_enabled` 在重启、待机、异常之后自动恢复为开，把"远程救砖"这条路焊死。**

> A tiny (14 KB) Android app that keeps `adb_enabled` at 1 so you can always reach the device over ADB — even after a crash, a black screen, or a ROM that clears the switch on standby.

---

## 这个项目解决什么问题

如果你把 Android TV / 电视盒子 / 车机的默认桌面换成了第三方启动器（比如 [Aerial](https://github.com/pengchujin/aerial-tv)），并把原厂桌面 `pm disable-user` 掉，那么一旦出现下面任一情况：

- 第三方桌面崩溃、起不来
- 系统把 HOME 解析回已被禁用的原厂桌面 → **黑屏**

你就只剩一条活路：**ADB 能连上**，然后在电脑上 `pm enable <原厂桌面>` 救回来。

但很多 ROM 会在**待机 / 关机 / 重启**时把 `Settings.Global.ADB_ENABLED` 清零。此时如果桌面恰好也坏了，没有任何界面能让你把 ADB 重新打开 —— 只能恢复出厂。

**adbkeeper 就是那个"始终把开关拨回去"的独立进程。**

## 工作原理

只做一件事：`ADB_ENABLED != 1` 时写回 `1`。

为什么这就够了：

| 事实 | 说明 |
|---|---|
| `adb_enabled` 是持久设置 | 写一次就一直有效，不必每次开机都成功 |
| `adbd` 在 system_server 侧 | 桌面应用崩溃 / 黑屏，都不影响 ADB 守护进程 |
| 端口由 `persist.adb.tcp.port` 决定 | 电视通常是固定的 `5555`，与开关无关 |

**与手机的差异**（参考 [Jocala/wifiadb](https://github.com/Jocala/wifiadb) 的分析）：手机 Android 11+ 的"无线调试"走 `adb_wifi_enabled` + 随机 TLS 端口，普通应用写不了，只能靠无障碍服务点快捷磁贴；而电视/盒子走传统 `adb_enabled` + 固定端口，`WRITE_SECURE_SETTINGS` 直接可写，**不需要无障碍**。

## 设计取舍

| 设计 | 原因 |
|---|---|
| **无桌面图标**（不加 `LAUNCHER` category） | 不占磁贴、不会被误点；需要时用 `am start` 打开状态页 |
| **无常驻服务、无轮询线程** | 只在系统广播 / JobScheduler 触发时被拉起几十毫秒 |
| **四重触发** | `LOCKED_BOOT_COMPLETED`（解锁前最早）+ `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` + 15 分钟周期 Job（带联网约束，跨重启持久） |
| **零第三方依赖** | 纯 Java + 系统 API，APK 14 KB |
| **只写一个值** | 已经是 1 就不写，避免无意义的设置变更 |

## 安装

```bash
adb install adb-keeper-1.0.apk

# 关键：授予签名级权限（安装时无法自动获得）
adb shell pm grant com.cx531.adbkeep android.permission.WRITE_SECURE_SETTINGS

# 首次安装后立即激活：注册周期任务并检查一次
# （系统对首次安装的包不会发任何广播，必须显式唤醒一次）
adb shell am broadcast -a com.cx531.adbkeep.PING
```

需要 Android 7.0+（`minSdk 21`，但 `directBootAware` 相关逻辑在 24+ 更完整）。

## 查看状态

桌面不会出现图标，用命令打开状态页：

```bash
adb shell am start -n com.cx531.adbkeep/.StatusActivity
```

> 打开状态页也会注册周期任务。如果之前已经做过 `PING` 激活，则不需要再打开。

状态页显示：写权限是否已授予、`adb_enabled` 当前值、自动恢复开关、最近一次检查/修复时间。

也可以直接看日志：

```bash
adb logcat -s AdbKeeper
```

## 验证它真的有用

```bash
# 1) 手动把开关清零（模拟 ROM 的行为）
adb shell settings put global adb_enabled 0

# 2) 等 15 分钟内，或直接触发一次
adb shell cmd jobscheduler run -f com.cx531.adbkeep 1001

# 3) 看是否被写回 1
adb shell settings get global adb_enabled
```

断电重启后再看一次，能连上即为生效。

## 必须知道的局限

- **它会强制打开 ADB。** 你手动关掉，它会在 15 分钟内改回来。想彻底关闭：状态页停用总开关，或 `adb shell pm disable-user com.cx531.adbkeep`。
- **网络断了救不了。** ADB over TCP 依赖设备联网，WiFi 密码变更 / 路由器故障属于物理层问题。
- **不解决 `system_server` 崩溃。** 那种情况 `adbd` 本身不存在。
- 需要 `WRITE_SECURE_SETTINGS`，所以**必须先用 ADB 授权一次**——这是个"鸡生蛋"约束，装之前请确保设备 ADB 可用。

## 构建

```bash
# 纯英文路径下构建（AGP 不支持中文路径）
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

`release` 使用 debug 签名，方便直接安装测试；正式分发请自行配置签名。

## 参考项目

- [kevinlee-06/HDB](https://github.com/kevinlee-06/HDB) —— 61 KB 的 `adb_enabled` 管理器（反向用途：关调试以绕过检测），极简结构可借鉴
- [Jocala/wifiadb](https://github.com/Jocala/wifiadb) —— 重启后恢复无线调试，README 里对 ROM 清零机制的分析很有价值
- [thedroidgeek/oculus-wireless-adb](https://github.com/thedroidgeek/oculus-wireless-adb) —— 用 `WRITE_SECURE_SETTINGS` 在设备内开启无线 ADB

## License

[MIT](LICENSE)

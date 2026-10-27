# adbkeeper

[![Build APK](https://github.com/cx5311/adbkeeper/actions/workflows/build.yml/badge.svg)](https://github.com/cx5311/adbkeeper/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/cx5311/adbkeeper)](https://github.com/cx5311/adbkeeper/releases)
[![License](https://img.shields.io/github/license/cx5311/adbkeeper)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-19-blue)](https://developer.android.com/about/versions)

**极简 Android 工具（约 16 KB）：让 `adb_enabled` 在重启、待机、异常之后自动恢复为开，把"远程救砖"这条路焊死。**

> A tiny (~16 KB) Android app that keeps `adb_enabled` at 1 so you can always reach the device over ADB — even after a crash, a black screen, or a ROM that clears the switch on standby.

---

## 下载

**https://github.com/cx5311/adbkeeper/releases/latest**

当前版本 **v1.2**，`versionCode 3`，APK 约 16 KB。

| 版本 | 说明 |
|---|---|
| v1.2 | 修首装激活失效、Job 联网约束、增加 adbd 监听探测、minSdk 降到 19 |
| v1.1 | 修老系统崩溃、首装不激活、状态页布局 |
| v1.0 | 首个版本 |

## 这个项目解决什么问题

如果你把 Android TV / 电视盒子 / 车机的默认桌面换成了第三方启动器（比如 [Aerial](https://github.com/pengchujin/aerial-tv)），并把原厂桌面 `pm disable-user` 掉，那么一旦出现下面任一情况：

- 第三方桌面崩溃、起不来
- 系统把 HOME 解析回已被禁用的原厂桌面 → **黑屏**

你就只剩一条活路：**ADB 能连上**，然后在电脑上 `pm enable <原厂桌面>` 救回来。

但很多 ROM 会在**待机 / 关机 / 重启**时把 `Settings.Global.ADB_ENABLED` 清零。此时如果桌面恰好也坏了，没有任何界面能让你把 ADB 重新打开 —— 只能恢复出厂。

**adbkeeper 就是那个"始终把开关拨回去"的独立进程。**

## 工作原理

只做一件事：**确保 ADB 真的可用**。

判据有两层：

1. `Settings.Global.ADB_ENABLED != 1` → 写回 `1`（写之前顺带确保"开发者选项"总开关是开的，部分 ROM 会因此忽略 `adb_enabled`）
2. 值已经是 1，但 `127.0.0.1:<adb 端口>` 连不上 → 说明 adbd 进程没了而设置值还在，做一次 `0 → 1` 翻转把 adbd 拉起来（连续 2 次探测失败才动手，避免偶发抖动误伤正在用的连接）

为什么这就够了：

| 事实 | 说明 |
|---|---|
| `adb_enabled` 是持久设置 | 写一次就一直有效，不必每次开机都成功 |
| `adbd` 在 system_server 侧 | 桌面应用崩溃 / 黑屏，都不影响 ADB 守护进程 |
| 端口由 `persist.adb.tcp.port` 决定 | 电视通常是固定的 `5555`，与开关无关；本应用会读这个值来决定探测哪个端口 |
| `0 → 1` 翻转能重启 adbd | `adb_enabled` 由 system_server 监听，写 0 停、写 1 拉起 |

**与手机的差异**（参考 [Jocala/wifiadb](https://github.com/Jocala/wifiadb) 的分析）：手机 Android 11+ 的"无线调试"走 `adb_wifi_enabled` + 随机 TLS 端口，普通应用写不了，只能靠无障碍服务点快捷磁贴；而电视/盒子走传统 `adb_enabled` + 固定端口，`WRITE_SECURE_SETTINGS` 直接可写，**不需要无障碍**。

## 特性

| 设计 | 原因 |
|---|---|
| **无桌面图标**（不加 `LAUNCHER` category） | 不占磁贴、不会被误点；需要时用 `am start` 打开状态页 |
| **无常驻服务、无轮询线程** | 只在系统广播 / JobScheduler 触发时被拉起几十毫秒 |
| **多重触发** | `LOCKED_BOOT_COMPLETED`（解锁前最早）+ `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` + 15 分钟周期 Job（无网络约束、跨重启持久） |
| **双重判据** | 不只看设置值，还探测 adbd 是否真的在监听 |
| **零第三方依赖** | 纯 Java + 系统 API，APK 约 16 KB |
| **只在需要时写设置** | 已经是 1 且端口正常就什么都不做，避免无意义的设置变更 |

## 安装

### 全新安装

```bash
adb install adbkeeper-1.2.apk

# 关键：授予签名级权限（安装时无法自动获得）
adb shell pm grant com.cx531.adbkeep android.permission.WRITE_SECURE_SETTINGS

# 首次安装后立即激活：这一步必做
adb shell am start -n com.cx531.adbkeep/.StatusActivity
```

**为什么必须激活**：首次安装的包处于 stopped 状态，系统不会给它发任何广播，15 分钟周期任务也就永远排不上。而 `am start` 是显式启动，会立刻解除 stopped 状态、打开状态页时顺带检查一次并排期任务。

> 也可以发广播激活，但要注意两条系统限制：Android 8+ 静态注册的 receiver **收不到隐式广播**，且 stopped 包**默认被排除**。两条都要绕开才行：
> ```bash
> adb shell am broadcast --include-stopped-packages \
>     -n com.cx531.adbkeep/.BootReceiver -a com.cx531.adbkeep.PING
> ```
> 相比之下 `am start` 没有任何前置条件，**推荐用它**。

### 从旧版升级

```bash
adb install -r adbkeeper-1.2.apk
```

覆盖安装会保留已授予的 `WRITE_SECURE_SETTINGS`，并自动触发 `MY_PACKAGE_REPLACED` 完成激活，**无需重新 grant**。

## 验证是否生效

```bash
# 1) 手动把开关清零（模拟 ROM 的行为）
adb shell settings put global adb_enabled 0

# 2) 立即触发一次（或等 15 分钟内的周期任务）
adb shell cmd jobscheduler run -f com.cx531.adbkeep 1001

# 3) 看是否被写回 1
adb shell settings get global adb_enabled

# 4) 确认 adbd 真的在监听（返回 5555 端口有内容即正常）
adb shell netstat -tlnp 2>/dev/null | grep 5555 || adb shell cat /proc/net/tcp | head -20
```

断电重启后再看一次，能连上即为生效。

## 查看状态

桌面不会出现图标，用命令打开状态页（这同时也是激活动作）：

```bash
adb shell am start -n com.cx531.adbkeep/.StatusActivity
```

状态页显示：写权限是否已授予、`adb_enabled` 当前值、`adbd` 是否在 `127.0.0.1:<端口>` 监听、自动恢复开关、最近一次检查/修复时间，并提供三个按钮：立即检查、强制重启 ADB、停用/启用自动恢复。

日志：

```bash
adb logcat -s AdbKeeper
```

## 必须知道的局限

- **它会强制打开 ADB。** 你手动关掉，它会在下一个周期（通常 15 分钟内）改回来。想彻底关闭：状态页停用总开关，或 `adb shell pm disable-user com.cx531.adbkeep`。
- **15 分钟是"尽力而为"。** 电视进入待机（Doze）后系统会放宽周期任务的调度，实际间隔可能是几十分钟到几小时。开机、解锁、升级这几个触发点是准时的。
- **Android 4.4 / 4.4W 没有周期任务。** `JobScheduler` 是 API 21 才有的，这些设备上本应用只在**开机**和**升级**时检查一次，运行中被清零不会自动补。5.0 及以上功能完整。
- **网络断了救不了。** ADB over TCP 依赖设备联网，WiFi 密码变更 / 路由器故障属于物理层问题。
- **不解决 `system_server` 崩溃。** 那种情况 `adbd` 本身不存在。
- **不修改 ADB 端口。** 依赖设备原本就能在 `persist.adb.tcp.port` 指定的端口上监听；端口没配好的设备，本应用不会自动重启 adbd（避免误伤 USB 调试）。
- 需要 `WRITE_SECURE_SETTINGS`，所以**必须先用 ADB 授权一次**——这是个"鸡生蛋"约束，装之前请确保设备 ADB 可用。

## 常见问题

**Q：为什么桌面/应用列表里看不到它？**
故意不加 `LAUNCHER` category。它是一种"后台设施"，不是一个要天天点的应用。想看状态用 `am start` 打开状态页。

**Q：为什么不像手机那样用无障碍权限？**
不需要。电视/盒子走传统 `adb_enabled`，`WRITE_SECURE_SETTINGS` 就能直接写；无障碍是手机无线调试（system API 写不了）才被迫采用的手段。少一个无障碍权限，少一份隐私面。

**Q：为什么要 `INTERNET` 权限？**
只用来探测 `127.0.0.1` 上的 adbd 是否在监听（本地回环 socket，超时 400ms）。代码里没有任何访问外网的行为——没有 HTTP 客户端、没有连接外部地址。Android 上创建 socket 需要这个权限，本地连接也不例外。

**Q：装完没重启，是不是就不生效了？**
执行一次 `adb shell am start -n com.cx531.adbkeep/.StatusActivity` 就生效了，它会立即检查并排期周期任务，不需要重启。

**Q：Android 4.4 的老盒子能用吗？**
能装、开机能自动恢复，但没有周期任务（见"局限"）。5.0 及以上才享受完整的 15 分钟兜底。

**Q：能在手机上用吗？**
Android 11+ 的手机基本无效——那边是 `adb_wifi_enabled` + 随机 TLS 端口，写 `adb_enabled` 不会开启无线调试。详见上面的"与手机的差异"。

**Q：会不会被系统杀掉？**
它没有常驻进程可杀。系统每次广播或 Job 触发时才会拉起进程，执行完立刻结束。作业用 `setPersisted(true)`，跨重启保留。

## 构建

```bash
# 纯英文路径下构建（AGP 不支持中文路径）
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

`release` 使用 debug 签名，方便直接安装测试；正式分发请自行配置签名。

CI 会在每次 push 时自动构建，产物可在 Actions → Build APK → Artifacts 下载。

## 参考项目

- [kevinlee-06/HDB](https://github.com/kevinlee-06/HDB) —— 61 KB 的 `adb_enabled` 管理器（反向用途：关调试以绕过检测），极简结构可借鉴
- [Jocala/wifiadb](https://github.com/Jocala/wifiadb) —— 重启后恢复无线调试，README 里对 ROM 清零机制的分析很有价值
- [thedroidgeek/oculus-wireless-adb](https://github.com/thedroidgeek/oculus-wireless-adb) —— 用 `WRITE_SECURE_SETTINGS` 在设备内开启无线 ADB

## License

[MIT](LICENSE)

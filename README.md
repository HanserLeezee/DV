# DV · Deliver Vision

## 画质（v0.4）

- 默认「画质优先」：H.265 编码，USB 80 Mbps / Wi-Fi 35 Mbps，适合看视频和 VR
- 操作电脑、玩游戏想要更低延迟：`DV.bat --mode fast`
- 手机上**双击**画面：在「完整显示」和「填满屏幕」之间切换（VR 盒子用填满）
- 播放器请全屏播放视频，这样画面占满整个屏幕，清晰度最高

## 一键使用（v0.2）

- **电脑**：双击 `DV.bat`（只需开一次，开着就行）
- **USB**：插上手机 → 自动转发端口、自动安装/更新 DV、自动打开并出画面
- **Wi-Fi**：手机打开 DV → 自动找到电脑并连接（优先上次连过的那台）
- 第一次用 USB 需要：手机开 USB 调试；电脑认不到手机时运行一次 `adb\修复ADB.bat`

把 PC 屏幕通过局域网实时投到安卓手机上。

```
PC: 屏幕抓取(DXGI) → NVENC 硬件编码 H.264(超低延迟模式) → TCP
手机: TCP 接收 → MediaCodec 硬件解码(低延迟模式) → 直接渲染到 Surface
```

## 一、PC 端（Windows）

1. 安装 Python 3.8+（只用标准库，不需要 pip 装东西）。
2. 下载 ffmpeg（≥ 6.0，需要带 ddagrab 和 nvenc）：https://www.gyan.dev/ffmpeg/builds/ → `ffmpeg-release-full.7z`，
   把里面 `bin\ffmpeg.exe` 复制到 `pc\` 文件夹（和 `dv_server.py` 放一起）。
3. 双击 `启动DV.bat`。第一次运行 Windows 防火墙会弹窗，**勾选"专用网络"并允许**。
4. 窗口里会显示本机 IP，以及自动选中的编码器（优先 NVENC 显卡编码）。

常用参数（可以加在 bat 里 `python dv_server.py` 后面）：

| 参数 | 说明 |
|---|---|
| `--fps 120` | 帧率，默认 60 |
| `--bitrate 30` | 码率 Mbps，默认 20。画面糊就调高，卡顿就调低 |
| `--width 1920` | 缩放后再传，降低带宽和手机解码压力 |
| `--monitor 1` | 投第二块屏幕 |
| `--encoder x264` | 强制软件编码（auto / nvenc / qsv / x264） |

## 二、手机端（编译 APK）

`android\` 是完整的 Android Studio 工程（纯 Java，无第三方依赖，最低 Android 7.0）。

**方法 A：Android Studio**  打开 `android` 文件夹 → 等 Gradle 同步完 → Build → Build APK(s)。

**方法 B：GitHub 自动打包**  把 `android` 文件夹作为一个仓库推到 GitHub，
Actions 会自动编译，在 Actions 页面下载 `DV-apk`。

## 三、使用

1. PC 和手机连同一个 Wi-Fi（电脑最好用网线连路由器，手机连 5GHz）。
2. 打开 DV → 自动搜索 → 点电脑名字即可；搜不到就手动输 IP。
3. 播放时**单击屏幕**显示统计信息（帧率 / 码率 / 解码延迟 / 丢帧），返回键退出。

## 四、有线模式（USB）

不用改任何代码：手机开 USB 调试，电脑执行

```
adb reverse tcp:27183 tcp:27183
```

然后在 DV 里输入 `127.0.0.1` 连接。走 USB 延迟更稳定，不受 Wi-Fi 干扰。

## 降低延迟的设计

- 编码：NVENC `p1 + ull + zerolatency`，无 B 帧，小 VBV 缓冲，避免大帧排队
- 传输：FLV 封装逐帧切分（不用等下一帧起始码），TCP_NODELAY
- 拥塞：发送端积压超过 3 帧就丢弃到下一个关键帧，延迟不会越积越大
- 解码：`KEY_LOW_LATENCY` + 高通/三星/海思/联发科私有低延迟参数，优先选支持低延迟的硬解
- 显示：解码出多帧时只显示最新一帧；高刷手机自动切最高刷新率；Wi-Fi 低延迟锁

## 仓库结构

| 路径 | 内容 |
|---|---|
| `app/` | 安卓端 (Java, 无第三方依赖) |
| `pc/dv_server.py` | 电脑端推流服务 (只用 Python 标准库 + ffmpeg) |
| `DV.bat` | 电脑端一键启动 |
| `adb/` | USB 驱动修复脚本 (adb 本体在 Release 压缩包里) |
| `release-notes/` | 各版本说明 |

发新版：改 `app/build.gradle` 的 versionName（和 versionCode、`pc/dv_server.py` 的 APP_VERSION_CODE），并加一个 `release-notes/v<版本>.md`，推送到 main 后 GitHub Actions 会自动编译 APK、打包含 ffmpeg 和 adb 的电脑端，并发布 Release。

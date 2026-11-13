#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DV (Deliver Vision) —— PC 端推流服务

抓取 PC 屏幕 -> 显卡硬件编码 H.265/H.264 (ffmpeg) -> TCP 发给手机上的 DV App。
只依赖 Python 标准库 + ffmpeg.exe。

用法:
    python dv_server.py                 # 画质优先 (默认): H.265, USB 80Mbps / Wi-Fi 35Mbps
    python dv_server.py --mode fast     # 延迟优先: 适合操作电脑、玩游戏
    python dv_server.py --bitrate 120   # 手动指定码率 (Mbps)
    python dv_server.py --codec h264    # 手机不支持 H.265 时
    python dv_server.py --monitor 1     # 第二个显示器

协议 (服务端 -> 手机), 每条消息:
    uint32 payload_len | uint8 type | int64 pts_us | payload
    type 0 = INFO   (UTF-8 JSON: width, height, fps, codec, encoder, bitrate, name)
    type 1 = CONFIG (uint8 codec 0=H.264 1=H.265 | uint8 n | n * (uint16 len + 参数集NAL, 不带起始码))
    type 2 = 关键帧  (Annex-B, 00 00 00 01 起始码)
    type 3 = 普通帧  (Annex-B)
局域网发现: 手机向 UDP 27184 广播 b"DV?", 服务端回 b"DV!" + JSON。
TCP 握手: 手机连上后先发 4 字节, b"DV1P" = 探测 (服务端回 b"DV!"+JSON 后断开), b"DV1S" = 开始推流。
USB: 自动对插上的手机执行 adb reverse, 并打开手机上的 DV。
"""

import argparse
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from collections import deque

TCP_PORT = 27183
DISCOVERY_PORT = 27184

MSG_INFO, MSG_CONFIG, MSG_KEY, MSG_FRAME = 0, 1, 2, 3
HEADER = struct.Struct(">IBq")


def log(*a):
    print(time.strftime("[%H:%M:%S]"), *a, flush=True)


# ---------------------------------------------------------------- ffmpeg 命令

def find_ffmpeg():
    here = os.path.dirname(os.path.abspath(__file__))
    for name in ("ffmpeg.exe", "ffmpeg"):
        p = os.path.join(here, name)
        if os.path.isfile(p):
            return p
    p = shutil.which("ffmpeg")
    if p:
        return p
    sys.exit("找不到 ffmpeg。请把 ffmpeg.exe 放到本脚本同一文件夹, 或加入 PATH。")


ENCODERS = {  # (编码器族, 编码格式) -> ffmpeg 编码器名
    ("nvenc", "hevc"): "hevc_nvenc", ("nvenc", "h264"): "h264_nvenc",
    ("qsv", "hevc"): "hevc_qsv", ("qsv", "h264"): "h264_qsv",
    ("x264", "hevc"): "libx265", ("x264", "h264"): "libx264",
}


def build_cmd(ffmpeg, pipeline, a, bitrate_mbps):
    """pipeline: (采集方式, 编码器族, 编码格式) 例如 ("direct", "nvenc", "hevc")"""
    mode, fam, codec = pipeline
    enc = ENCODERS[(fam, codec)]
    quality = a.mode == "quality"
    br = int(bitrate_mbps * 1_000_000)
    gop = max(1, int(a.fps * a.gop))
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
           "-fflags", "nobuffer", "-flags", "low_delay"]

    if a.source == "test":
        cmd += ["-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate={a.fps}"]
        vf = []
    elif a.source == "gdigrab":
        cmd += ["-f", "gdigrab", "-framerate", str(a.fps), "-draw_mouse", "1", "-i", "desktop"]
        vf = []
    else:  # Desktop Duplication API
        cmd += ["-f", "lavfi", "-i",
                f"ddagrab=output_idx={a.monitor}:framerate={a.fps}:draw_mouse=1"]
        vf = [] if mode == "direct" else ["hwdownload", "format=bgra"]

    if a.width:
        if mode == "direct" and a.source == "ddagrab":
            vf = ["hwdownload", "format=bgra"]
        vf.append(f"scale={a.width}:-2:flags=lanczos")
    if fam in ("qsv", "x264") and (vf or a.source != "ddagrab"):
        vf.append("format=nv12" if fam == "qsv" else "format=yuv420p")
    if vf:
        cmd += ["-vf", ",".join(vf)]

    cmd += ["-c:v", enc]
    if fam == "nvenc":
        if quality:
            # 画质优先: 慢一档的预设 + 自适应量化 + 码率上限内尽量高画质 (延迟多十几毫秒, 看视频无感)
            cmd += ["-preset", "p5", "-tune", "ll", "-rc", "vbr", "-cq", "16",
                    "-b:v", str(br), "-maxrate", str(int(br * 1.5)), "-bufsize", str(br),
                    "-spatial-aq", "1", "-aq-strength", "8", "-multipass", "qres"]
            if codec == "h264":
                cmd += ["-temporal-aq", "1"]
        else:
            cmd += ["-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0",
                    "-rc", "cbr", "-b:v", str(br), "-maxrate", str(br), "-bufsize", str(br // 4)]
        cmd += ["-g", str(gop), "-bf", "0", "-forced-idr", "1",
                "-profile:v", "main" if codec == "hevc" else "high"]
    elif fam == "qsv":
        cmd += ["-preset", "medium" if quality else "veryfast", "-async_depth", "1",
                "-look_ahead", "0", "-b:v", str(br), "-maxrate", str(int(br * 1.5)),
                "-bufsize", str(br if quality else br // 4), "-g", str(gop), "-bf", "0"]
    else:
        cmd += ["-preset", "veryfast" if quality else "ultrafast", "-tune", "zerolatency",
                "-b:v", str(br), "-maxrate", str(int(br * 1.5)),
                "-bufsize", str(br if quality else br // 4), "-g", str(gop), "-bf", "0"]
        if codec == "hevc":
            cmd += ["-x265-params", "log-level=none"]

    cmd += ["-an", "-flush_packets", "1", "-flvflags", "no_duration_filesize",
            "-f", "flv", "pipe:1"]
    return cmd


def candidate_pipelines(a):
    fams = {"auto": ["nvenc", "qsv", "x264"], "nvenc": ["nvenc"], "qsv": ["qsv"], "x264": ["x264"]}[a.encoder]
    codecs = {"auto": ["hevc", "h264"], "hevc": ["hevc"], "h264": ["h264"]}[a.codec]
    out = []
    for codec in codecs:
        for fam in fams:
            if a.source != "ddagrab":
                out.append(("download", fam, codec))
            else:
                if fam == "nvenc":
                    out.append(("direct", fam, codec))
                out.append(("download", fam, codec))
    if a.source == "test" and a.encoder == "auto":  # 沙盒测试: 只用软件编码
        out = [p for p in out if p[1] == "x264"]
    return out


def probe_pipeline(ffmpeg, a):
    for p in candidate_pipelines(a):
        cmd = build_cmd(ffmpeg, p, a, 10)
        log(f"测试编码管线: {ENCODERS[(p[1], p[2])]} (采集={p[0]}) ...")
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError as e:
            log("  启动失败:", e)
            continue
        got = 0
        try:
            t0 = time.time()
            for kind, _ in FlvReader(proc.stdout):
                if kind in ("key", "frame"):
                    got += 1
                if got >= 3 or time.time() - t0 > 6:
                    break
        except Exception:
            got = 0
        proc.kill()
        err = proc.stderr.read().decode("utf-8", "replace").strip()
        proc.wait()
        if got >= 3:
            log("  可用 ✓")
            return p
        log("  不可用:", (err.splitlines() or ["无输出"])[-1][:200])
    sys.exit("没有可用的编码管线, 请确认 ffmpeg 版本 >= 6.1。")


# ---------------------------------------------------------------- FLV 解析

class FlvReader:
    """从 ffmpeg 的 FLV 输出里逐帧取出视频。每个 FLV tag 正好是一帧, 不用等下一帧的起始码。
    H.264 用传统 FLV; H.265 用 Enhanced FLV (FourCC hvc1)。"""

    def __init__(self, f):
        self.f = f
        self.nal_len_size = 4
        self.codec = "h264"
        self.meta = {}

    def _read(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.f.read(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return bytes(buf)

    def __iter__(self):
        """产出 ("meta", {width,height}) / ("config", [参数集...]) / ("key"|"frame", (pts_us, annexb))"""
        try:
            hdr = self._read(9)
            if hdr[:3] != b"FLV":
                raise ValueError("不是 FLV 数据")
            self._read(hdr[8] - 9 + 4)
            while True:
                th = self._read(11)
                ttype = th[0] & 0x1F
                size = int.from_bytes(th[1:4], "big")
                ts = int.from_bytes(th[4:7], "big") | (th[7] << 24)
                data = self._read(size)
                self._read(4)
                if ttype == 18:
                    m = self._parse_meta(data)
                    if m:
                        yield "meta", m
                    continue
                if ttype != 9 or len(data) < 5:
                    continue
                b0 = data[0]
                if b0 & 0x80:  # Enhanced FLV
                    frame_type = (b0 >> 4) & 7
                    pkt = b0 & 0x0F
                    if data[1:5] != b"hvc1":
                        raise ValueError("不支持的编码 " + data[1:5].decode("latin1"))
                    self.codec = "hevc"
                    if pkt == 0:
                        yield "config", self._parse_hvcc(data[5:])
                    elif pkt in (1, 3):
                        cts = int.from_bytes(data[5:8], "big", signed=True) if pkt == 1 else 0
                        annexb = self._to_annexb(data[8:] if pkt == 1 else data[5:])
                        if annexb:
                            yield ("key" if frame_type == 1 else "frame"), ((ts + cts) * 1000, annexb)
                    continue
                frame_type = b0 >> 4
                if b0 & 0x0F != 7:
                    raise ValueError("不支持的编码")
                self.codec = "h264"
                cts = int.from_bytes(data[2:5], "big", signed=True)
                if data[1] == 0:
                    yield "config", self._parse_avcc(data[5:])
                elif data[1] == 1:
                    annexb = self._to_annexb(data[5:])
                    if annexb:
                        yield ("key" if frame_type == 1 else "frame"), ((ts + cts) * 1000, annexb)
        except EOFError:
            return

    @staticmethod
    def _parse_meta(d):
        out = {}
        for key in (b"width", b"height"):
            i = d.find(len(key).to_bytes(2, "big") + key + b"\x00")
            if i >= 0:
                j = i + 2 + len(key) + 1
                out[key.decode()] = int(struct.unpack(">d", d[j:j + 8])[0])
        return out

    def _parse_avcc(self, b):
        self.nal_len_size = (b[4] & 3) + 1
        i, out = 5, []
        for count_mask in (0x1F, 0xFF):
            n = b[i] & count_mask
            i += 1
            for _ in range(n):
                l = int.from_bytes(b[i:i + 2], "big")
                out.append(b[i + 2:i + 2 + l])
                i += 2 + l
        return out  # [SPS..., PPS...]

    def _parse_hvcc(self, b):
        self.nal_len_size = (b[21] & 3) + 1
        n_arrays = b[22]
        i, ps = 23, {32: [], 33: [], 34: []}
        for _ in range(n_arrays):
            t = b[i] & 0x3F
            n = int.from_bytes(b[i + 1:i + 3], "big")
            i += 3
            for _ in range(n):
                l = int.from_bytes(b[i:i + 2], "big")
                if t in ps:
                    ps[t].append(b[i + 2:i + 2 + l])
                i += 2 + l
        return ps[32] + ps[33] + ps[34]  # VPS, SPS, PPS

    def _to_annexb(self, b):
        out = bytearray()
        n = self.nal_len_size
        i = 0
        hevc = self.codec == "hevc"
        while i + n <= len(b):
            l = int.from_bytes(b[i:i + n], "big")
            i += n
            nal = b[i:i + l]
            i += l
            if not nal:
                continue
            t = (nal[0] >> 1) & 0x3F if hevc else nal[0] & 0x1F
            if t == (35 if hevc else 9):  # AUD
                continue
            out += b"\x00\x00\x00\x01" + nal
        return bytes(out)


# ---------------------------------------------------------------- SPS 解析 (取分辨率)

class _Bits:
    def __init__(self, data):
        rbsp = bytearray()
        zeros = 0
        for x in data:
            if zeros >= 2 and x == 3:
                zeros = 0
                continue
            rbsp.append(x)
            zeros = zeros + 1 if x == 0 else 0
        self.d, self.p = rbsp, 0

    def u(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | ((self.d[self.p >> 3] >> (7 - (self.p & 7))) & 1)
            self.p += 1
        return v

    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
        return (1 << z) - 1 + self.u(z)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


def sps_resolution(sps):
    b = _Bits(sps[1:])
    profile = b.u(8)
    b.u(16)
    b.ue()
    chroma = 1
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = b.ue()
        if chroma == 3:
            b.u(1)
        b.ue(); b.ue(); b.u(1)
        if b.u(1):
            for i in range(8 if chroma != 3 else 12):
                if b.u(1):
                    last, nxt = 8, 8
                    for _ in range(16 if i < 6 else 64):
                        if nxt:
                            nxt = (last + b.se() + 256) % 256
                        last = nxt or last
    b.ue()
    poc = b.ue()
    if poc == 0:
        b.ue()
    elif poc == 1:
        b.u(1); b.se(); b.se()
        for _ in range(b.ue()):
            b.se()
    b.ue(); b.u(1)
    w_mbs = b.ue() + 1
    h_map = b.ue() + 1
    frame_mbs_only = b.u(1)
    if not frame_mbs_only:
        b.u(1)
    b.u(1)
    w, h = w_mbs * 16, h_map * 16 * (2 - frame_mbs_only)
    if b.u(1):
        l, r, t, bo = b.ue(), b.ue(), b.ue(), b.ue()
        cx = 2 if chroma in (1, 2) else 1
        cy = (2 if chroma == 1 else 1) * (2 - frame_mbs_only)
        w -= (l + r) * cx
        h -= (t + bo) * cy
    return w, h


# ---------------------------------------------------------------- 推流会话

class Session:
    """一个手机连接 = 一个 ffmpeg 进程。新手机连上会顶掉旧连接。"""

    def __init__(self, conn, addr, ffmpeg, pipeline, args, bitrate):
        self.conn, self.addr = conn, addr
        self.ffmpeg, self.pipeline, self.args = ffmpeg, pipeline, args
        self.bitrate = bitrate
        self.queue = deque()
        self.cv = threading.Condition()
        self.alive = True
        self.proc = None
        self.skip_until_key = False
        self.dropped = 0

    def stop(self):
        with self.cv:
            self.alive = False
            self.cv.notify_all()
        if self.proc:
            try:
                self.proc.kill()
            except Exception:
                pass
        try:
            self.conn.close()
        except Exception:
            pass

    def _push(self, item):
        with self.cv:
            kind = item[0]
            if kind in ("key", "frame"):
                if self.skip_until_key and kind == "frame":
                    self.dropped += 1
                    return
                frames = sum(1 for x in self.queue if x[0] in ("key", "frame"))
                if frames >= self.args.max_queue and kind == "frame":
                    # 网络跟不上: 扔掉积压, 等下一个关键帧, 防止延迟越积越大
                    self.dropped += frames + 1
                    self.queue = deque(x for x in self.queue if x[0] not in ("key", "frame"))
                    self.skip_until_key = True
                    return
                if kind == "key":
                    self.skip_until_key = False
            self.queue.append(item)
            self.cv.notify()

    def _encode_loop(self):
        a = self.args
        cmd = build_cmd(self.ffmpeg, self.pipeline, a, self.bitrate)
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     bufsize=0)
        meta = {}
        codec = self.pipeline[2]
        try:
            for kind, val in FlvReader(self.proc.stdout):
                if not self.alive:
                    break
                if kind == "meta":
                    meta = val
                elif kind == "config":
                    w, h = meta.get("width", 0), meta.get("height", 0)
                    if (not w or not h) and codec == "h264":
                        try:
                            w, h = sps_resolution(val[0])
                        except Exception:
                            pass
                    info = {"width": w, "height": h, "fps": a.fps, "codec": codec,
                            "encoder": ENCODERS[(self.pipeline[1], codec)],
                            "bitrate": self.bitrate, "name": socket.gethostname()}
                    log(f"编码 {w}x{h}@{a.fps} {info['encoder']} {self.bitrate:g}Mbps "
                        f"({'画质优先' if a.mode == 'quality' else '延迟优先'})")
                    self._push(("info", json.dumps(info).encode()))
                    payload = bytes([1 if codec == "hevc" else 0, len(val)])
                    for nal in val:
                        payload += struct.pack(">H", len(nal)) + nal
                    self._push(("config", payload))
                else:
                    self._push((kind, val))
        finally:
            log("编码进程结束")
            self.stop()

    def run(self):
        c = self.conn
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        c.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 512 * 1024)
        threading.Thread(target=self._encode_loop, daemon=True).start()
        types = {"info": MSG_INFO, "config": MSG_CONFIG, "key": MSG_KEY, "frame": MSG_FRAME}
        sent_bytes, sent_frames, t_last = 0, 0, time.time()
        try:
            while True:
                with self.cv:
                    while self.alive and not self.queue:
                        self.cv.wait(0.5)
                    if not self.alive:
                        return
                    kind, val = self.queue.popleft()
                if kind in ("key", "frame"):
                    pts, payload = val
                else:
                    pts, payload = 0, val
                c.sendall(HEADER.pack(len(payload), types[kind], pts) + payload)
                if kind in ("key", "frame"):
                    sent_frames += 1
                sent_bytes += len(payload)
                now = time.time()
                if now - t_last >= 5:
                    log(f"{self.addr[0]}: {sent_frames / (now - t_last):.0f} fps, "
                        f"{sent_bytes * 8 / (now - t_last) / 1e6:.1f} Mbps, 累计丢帧 {self.dropped}")
                    sent_bytes, sent_frames, t_last = 0, 0, now
        except OSError:
            pass
        finally:
            log(f"手机断开: {self.addr[0]}")
            self.stop()


# ---------------------------------------------------------------- 发现 & 主循环

def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def discovery_loop():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVERY_PORT))
    reply = b"DV!" + json.dumps({"name": socket.gethostname(), "port": TCP_PORT}).encode()
    while True:
        try:
            data, addr = s.recvfrom(64)
            if data.startswith(b"DV?"):
                s.sendto(reply, addr)
        except OSError:
            time.sleep(0.2)


# ---------------------------------------------------------------- USB (adb) 自动化

APP_PACKAGE = "com.hans.dv"
APP_COMPONENT = APP_PACKAGE + "/.MainActivity"
APP_VERSION_CODE = 5  # 和 android/app/build.gradle 的 versionCode 保持一致
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def find_adb():
    here = os.path.dirname(os.path.abspath(__file__))
    for d in (here, os.path.join(here, "..", "adb"), os.path.join(here, "adb")):
        for name in ("adb.exe", "adb"):
            p = os.path.normpath(os.path.join(d, name))
            if os.path.isfile(p):
                return p
    return shutil.which("adb")


def _adb(adb, *args, timeout=8):
    try:
        r = subprocess.run([adb, *args], capture_output=True, timeout=timeout,
                           creationflags=NO_WINDOW)
        return r.returncode, r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def find_apk():
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (os.path.join(here, "..", "DV.apk"), os.path.join(here, "DV.apk")):
        if os.path.isfile(p):
            return os.path.normpath(p)
    return None


def ensure_app(adb, serial):
    """手机上没装 DV 或版本旧, 且文件夹里有 DV.apk, 就自动装上。"""
    _, out = _adb(adb, "-s", serial, "shell", "dumpsys", "package", APP_PACKAGE)
    installed = 0
    for tok in out.split():
        if tok.startswith("versionCode="):
            try:
                installed = int(tok.split("=", 1)[1])
            except ValueError:
                pass
            break
    if installed >= APP_VERSION_CODE:
        return
    apk = find_apk()
    if not apk:
        if installed == 0:
            log("手机上还没装 DV: 把 DV.apk 放到 DV 文件夹里, 下次插上会自动安装")
        return
    log("正在给手机" + ("安装" if installed == 0 else "更新") + " DV ... (手机上如有提示请点允许)")
    code, msg = _adb(adb, "-s", serial, "install", "-r", apk, timeout=120)
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in msg or "signatures do not match" in msg:
        # 旧版是用别的签名打包的, 没法覆盖: 卸载后重装 (DV 没有需要保留的数据)
        log("旧版签名不同, 先卸载旧版再安装 ...")
        _adb(adb, "-s", serial, "uninstall", APP_PACKAGE, timeout=60)
        code, msg = _adb(adb, "-s", serial, "install", apk, timeout=120)
    log("安装完成" if code == 0 and "Success" in msg else "安装失败: " + msg.strip()[-200:])


def adb_watch(adb, port, autolaunch):
    """每 2 秒看一次 USB 上的手机: 新插上的自动做端口转发, 并在手机上打开 DV。"""
    _adb(adb, "start-server", timeout=20)
    ready, warned, tick = set(), set(), 0
    while True:
        code, out = _adb(adb, "devices")
        states = {}
        if code == 0:
            for line in out.splitlines()[1:]:
                parts = line.split()
                if len(parts) >= 2:
                    states[parts[0]] = parts[1]
        tick += 1
        for serial, state in states.items():
            if state == "device":
                need = serial not in ready
                if not need and tick % 5 == 0:  # 偶尔检查转发是否还在 (adb 被别的软件重启过)
                    _, lst = _adb(adb, "-s", serial, "reverse", "--list")
                    need = f"tcp:{port}" not in lst
                if need:
                    c, msg = _adb(adb, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}")
                    if c == 0:
                        first = serial not in ready
                        ready.add(serial)
                        if first:
                            log(f"USB 手机已就绪 ({serial})")
                            ensure_app(adb, serial)
                            if autolaunch:
                                _adb(adb, "-s", serial, "shell", "am", "start", "-n", APP_COMPONENT,
                                     "-f", "0x14000000", "--es", "host", "127.0.0.1",
                                     "--ei", "port", str(port))
                    else:
                        log("adb 端口转发失败:", msg.strip()[:200])
            elif state == "unauthorized" and serial not in warned:
                warned.add(serial)
                log("检测到 USB 手机但未授权: 请解锁手机, 在弹窗里勾选「始终允许」并确定")
        gone = ready - {s for s, st in states.items() if st == "device"}
        for s in gone:
            log(f"USB 手机已拔出 ({s})")
        ready -= gone
        warned &= set(states)
        time.sleep(2)


# ---------------------------------------------------------------- 主循环

def bind_server(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if os.name == "nt":  # Windows 的 SO_REUSEADDR 会允许两个实例抢同一个端口, 用独占模式
        srv.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_EXCLUSIVEADDRUSE", -5), 1)
    else:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("0.0.0.0", port))
    except OSError:
        sys.exit(f"端口 {port} 已被占用: DV 可能已经在运行了 (看看任务栏有没有另一个 DV 窗口)。")
    srv.listen(8)
    return srv


def main():
    global TCP_PORT
    ap = argparse.ArgumentParser(description="DV PC 端推流服务")
    ap.add_argument("--mode", choices=["quality", "fast"], default="quality",
                    help="quality=画质优先(看视频/VR, 默认)  fast=延迟优先(操作电脑/游戏)")
    ap.add_argument("--codec", choices=["auto", "hevc", "h264"], default="auto",
                    help="auto 优先 H.265, 同码率下画质明显好于 H.264")
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--bitrate", type=float, default=0,
                    help="码率 Mbps。默认: USB 80 / Wi-Fi 35 (fast 模式 40 / 20)")
    ap.add_argument("--width", type=int, default=0, help="缩放到指定宽度, 0=原始分辨率")
    ap.add_argument("--monitor", type=int, default=0, help="显示器序号, 0=主显示器")
    ap.add_argument("--encoder", choices=["auto", "nvenc", "qsv", "x264"], default="auto")
    ap.add_argument("--gop", type=float, default=2.0, help="关键帧间隔(秒)")
    ap.add_argument("--max-queue", type=int, default=0, help="积压超过多少帧就丢帧追赶 (默认 画质8/延迟3)")
    ap.add_argument("--port", type=int, default=TCP_PORT)
    ap.add_argument("--source", choices=["ddagrab", "gdigrab", "test"], default="ddagrab")
    ap.add_argument("--no-usb", action="store_true", help="不自动处理 USB 手机")
    ap.add_argument("--no-autolaunch", action="store_true", help="插上手机时不自动打开 DV")
    a = ap.parse_args()
    TCP_PORT = a.port
    if not a.max_queue:
        a.max_queue = 8 if a.mode == "quality" else 3

    srv = bind_server(TCP_PORT)  # 先占端口, 避免重复启动
    ffmpeg = find_ffmpeg()
    log("ffmpeg:", ffmpeg)
    pipeline = probe_pipeline(ffmpeg, a)

    threading.Thread(target=discovery_loop, daemon=True).start()
    adb = None if a.no_usb else find_adb()
    if adb:
        threading.Thread(target=adb_watch, args=(adb, TCP_PORT, not a.no_autolaunch),
                         daemon=True).start()

    log("=" * 50)
    log("DV 已启动。")
    if adb:
        log("  USB : 插上手机即可 (会自动打开手机上的 DV)")
    log("  Wi-Fi: 在同一网络下打开手机 DV, 会自动找到这台电脑")
    for ip in local_ips():
        log(f"        (本机 IP {ip})")
    log("=" * 50)

    state = {"current": None}
    lock = threading.Lock()

    def handle(conn, addr):
        # 握手: 手机先发 4 字节。DV1P = 探测(回一句就关), DV1S = 开始推流
        try:
            conn.settimeout(3)
            hello = b""
            while len(hello) < 4:
                chunk = conn.recv(4 - len(hello))
                if not chunk:
                    raise OSError("closed")
                hello += chunk
            conn.settimeout(None)
        except OSError:
            conn.close()
            return
        if hello == b"DV1P":
            try:
                conn.sendall(b"DV!" + json.dumps({"name": socket.gethostname(), "port": TCP_PORT}).encode())
            except OSError:
                pass
            conn.close()
            return
        if hello != b"DV1S":
            conn.close()
            return
        usb = addr[0].startswith("127.")  # adb reverse 转发进来的就是 USB
        if a.bitrate:
            br = a.bitrate
        elif a.mode == "quality":
            br = 80 if usb else 35
        else:
            br = 40 if usb else 20
        log(f"手机连接: {'USB' if usb else addr[0]}")
        with lock:
            if state["current"]:
                state["current"].stop()
            sess = Session(conn, addr, ffmpeg, pipeline, a, br)
            state["current"] = sess
        sess.run()

    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

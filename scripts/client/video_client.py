#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Daemon 视频流客户端库。

专门负责与 scrcpy server 建立 ROLE_VIDEO 类型的 Socket 连接，
接收 H.264/H.265/AV1 视频流、解析并保存，支持使用 FFmpeg 解码视频流并截图。
"""

import os
import socket
import struct
import time
import threading
import subprocess
from typing import Optional, Tuple


ROLE_VIDEO = 0


class VideoClient:
    """用于连接并捕获 daemon 模式下虚拟显示器视频流的客户端。"""

    def __init__(self, host: str, port: int, session_id: int, display_id: int, log_prefix: str = "VideoClient"):
        self.host = host
        self.port = port
        self.session_id = session_id
        self.display_id = display_id
        self.log_prefix = f"[{log_prefix}-Display{display_id}]"
        
        self.sock: Optional[socket.socket] = None
        self.is_running = False
        self.video_data = bytearray()
        self.width = 0
        self.height = 0
        self._thread: Optional[threading.Thread] = None

    def connect(self, timeout: float = 10.0):
        """建立视频流 socket 握手。"""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect((self.host, self.port))

        # 1. 发送 Socket 角色为 ROLE_VIDEO
        self.sock.sendall(struct.pack('B', ROLE_VIDEO))
        
        # 2. 发送对应的 Control 握手得到的 sessionId (4字节 Big-Endian)
        self.sock.sendall(struct.pack('>i', self.session_id))
        
        # 将 socket 恢复或设定超时
        self.sock.settimeout(1.0)

    def start_capture(self, duration_seconds: float = 10.0):
        """启动后台线程接收并累积视频帧数据。"""
        self.is_running = True
        self.video_data.clear()

        def video_reader():
            start_time = time.time()
            try:
                while self.is_running:
                    # 检查是否超时
                    if time.time() - start_time > duration_seconds:
                        break
                    try:
                        chunk = self.sock.recv(65536)
                        if not chunk:
                            break
                        self.video_data.extend(chunk)
                    except socket.timeout:
                        continue
                    except Exception as e:
                        break
            finally:
                self.is_running = False

        self._thread = threading.Thread(target=video_reader, daemon=True)
        self._thread.start()

    def stop_capture(self):
        """停止数据接收线程。"""
        self.is_running = False
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None

    def extract_raw_h264(self, output_path: str) -> bool:
        """
        解析视频数据，剥离 scrcpy 的包头/元数据，提取出纯 Annex B 格式的 H.264 NAL 单元流。
        
        根据 scrcpy 视频流协议：
        - 头部有 4字节 codec_id
        - 每个 packet：pts_and_flags (8B) + packet_size (4B) + raw_data (packet_size Bytes)
        - 如果 pts_and_flags 中包含 SESSION_FLAG (1<<63)，则为会话元数据帧 (12B)，不写入文件。
        """
        data = bytes(self.video_data)
        print(f"\n[VideoClient] extract_raw_h264: len(data) = {len(data)}")
        print(f"[VideoClient] data prefix: {data[:32].hex()}")
        if len(data) < 4:
            return False

        pos = 0
        if len(data) >= 4:
            first_4_bytes = struct.unpack('>I', data[0:4])[0]
            # 常见 codec_id: h264 (0x68323634), h265 (0x68323635), av1 (0x00617631)
            if first_4_bytes in (0x68323634, 0x68323635, 0x00617631):
                pos += 4
                print(f"[VideoClient] Detected codec_id: {hex(first_4_bytes)}, pos advanced to {pos}")
            else:
                print(f"[VideoClient] No codec_id header found, starting parsing from pos 0")

        SESSION_FLAG = 1 << 63
        CONFIG_FLAG = 1 << 62
        KEYFRAME_FLAG = 1 << 61

        nals = []
        session_info = None

        while pos + 12 <= len(data):
            pts_and_flags = struct.unpack('>Q', data[pos:pos+8])[0]
            packet_size = struct.unpack('>I', data[pos+8:pos+12])[0]

            if pts_and_flags & SESSION_FLAG:
                # 解析 session info，包含宽高
                width = struct.unpack('>I', data[pos+4:pos+8])[0]
                height = struct.unpack('>I', data[pos+8:pos+12])[0]
                session_info = (width, height)
                pos += 12
                continue

            if pos + 12 + packet_size > len(data):
                break

            nal_data = data[pos+12:pos+12+packet_size]
            nals.append(nal_data)
            pos += 12 + packet_size

        if session_info:
            self.width, self.height = session_info

        # 写入 raw stream 文件
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, 'wb') as f:
            for nal in nals:
                f.write(nal)

        return os.path.exists(output_path) and os.path.getsize(output_path) > 0

    def _probe_video_size(self, raw_stream_path: str):
        """
        使用 ffprobe 从 H.264 原始流中探测实际视频分辨率。

        当 send_stream_meta=false 时，视频流中不包含 session meta 帧，
        无法从包头解析宽高，必须通过 ffprobe 解析 SPS/PPS 获取。
        返回 (width, height) 或 None。
        """
        try:
            cmd = [
                'ffprobe', '-v', 'error', '-select_streams', 'v:0',
                '-show_entries', 'stream=width,height', '-of', 'csv=p=0',
                '-f', 'h264', raw_stream_path
            ]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                parts = result.stdout.strip().split(',')
                if len(parts) == 2:
                    w, h = int(parts[0]), int(parts[1])
                    if w > 0 and h > 0:
                        return w, h
        except Exception:
            pass
        return None

    def take_screenshot(self, raw_stream_path: str, output_png: str) -> bool:
        """
        使用 FFmpeg 解码 raw_stream_path 第一帧并保存为 output_png。

        如果没有 Pillow/numpy，会使用 FFmpeg 直接转码。
        如果有，则配合 numpy/Pillow 确保对图片数据进行合理的处理和验证。
        """
        if not os.path.exists(raw_stream_path) or os.path.getsize(raw_stream_path) == 0:
            return False

        yuv_path = raw_stream_path + ".yuv"
        try:
            # 1. 使用 ffmpeg 解码第一帧为 YUV420P 原始帧
            cmd = [
                'ffmpeg', '-v', 'error', '-y',
                '-f', 'h264', '-i', raw_stream_path,
                '-frames:v', '1',
                '-f', 'rawvideo', '-pix_fmt', 'yuv420p', yuv_path
            ]
            result = subprocess.run(cmd, capture_output=True, timeout=15)
            if result.returncode != 0 or not os.path.exists(yuv_path):
                return False

            # 2. 如果能够引入 numpy & Pillow，则通过 YUV -> RGB 的方式生成 png (复用原 test_multi_client.py 像素处理，确保一致性)
            try:
                import numpy as np
                from PIL import Image

                # 优先使用 ffprobe 探测实际分辨率（send_stream_meta=false 时唯一可靠来源），
                # 其次用 session meta 解析到的 self.width/self.height，
                # 最后退化为 1280x720。
                w, h = self.width, self.height
                if w == 0 or h == 0:
                    probed = self._probe_video_size(raw_stream_path)
                    if probed:
                        w, h = probed
                        self.width, self.height = w, h
                    else:
                        w, h = 1280, 720

                with open(yuv_path, 'rb') as f:
                    yuv_bytes = f.read()

                y_size = w * h
                uv_size = w * h // 4
                if len(yuv_bytes) < y_size + 2 * uv_size:
                    return False

                y = np.frombuffer(yuv_bytes[:y_size], dtype=np.uint8).reshape(h, w).astype(np.float32)
                u = np.frombuffer(yuv_bytes[y_size:y_size + uv_size], dtype=np.uint8).reshape(h // 2, w // 2)
                v = np.frombuffer(yuv_bytes[y_size + uv_size:y_size + 2 * uv_size], dtype=np.uint8).reshape(h // 2, w // 2)

                u_full = np.repeat(np.repeat(u, 2, axis=0), 2, axis=1).astype(np.float32)
                v_full = np.repeat(np.repeat(v, 2, axis=0), 2, axis=1).astype(np.float32)

                r = y + 1.402 * (v_full - 128)
                g = y - 0.344136 * (u_full - 128) - 0.714136 * (v_full - 128)
                b = y + 1.772 * (u_full - 128)

                rgb = np.stack([r, g, b], axis=-1)
                rgb = np.clip(rgb, 0, 255).astype(np.uint8)

                os.makedirs(os.path.dirname(os.path.abspath(output_png)), exist_ok=True)
                img = Image.fromarray(rgb, 'RGB')
                img.save(output_png)
                return True

            except ImportError:
                #  fallback: 如果未装 Pillow/numpy，则直接用 ffmpeg 转成 png 写入
                cmd_fallback = [
                    'ffmpeg', '-v', 'error', '-y',
                    '-f', 'h264', '-i', raw_stream_path,
                    '-frames:v', '1',
                    '-f', 'image2', output_png
                ]
                subprocess.run(cmd_fallback, capture_output=True, timeout=15)
                return os.path.exists(output_png) and os.path.getsize(output_png) > 0

        except Exception:
            return False
        finally:
            if os.path.exists(yuv_path):
                try:
                    os.remove(yuv_path)
                except OSError:
                    pass

    def close(self):
        """断开连接。"""
        self.stop_capture()
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    @staticmethod
    def images_differ(path_a: str, path_b: str, threshold: float = 12.0) -> bool:
        """
        将两张 PNG 缩放到 32x32 后比较平均绝对像素差，超过 threshold 视为内容不同。

        用于校验两个屏幕的视频流确实捕获了不同内容（防止错误地捕获到同一屏幕）。
        跨分辨率安全：缩放到统一尺寸后再比较。
        """
        try:
            import numpy as np
            from PIL import Image
        except ImportError:
            # 无 numpy/Pillow 时退化为文件哈希比较：不同则视为不同
            import hashlib
            ha = hashlib.md5(open(path_a, 'rb').read()).hexdigest()
            hb = hashlib.md5(open(path_b, 'rb').read()).hexdigest()
            return ha != hb

        a = np.asarray(Image.open(path_a).convert('RGB').resize((32, 32)), dtype=np.float32)
        b = np.asarray(Image.open(path_b).convert('RGB').resize((32, 32)), dtype=np.float32)
        return float(np.abs(a - b).mean()) > threshold

    @staticmethod
    def mean_rgb(png_path: str):
        """返回 PNG 的平均 (R, G, B)，用于诊断日志。"""
        try:
            import numpy as np
            from PIL import Image
            arr = np.asarray(Image.open(png_path).convert('RGB'), dtype=np.float32)
            return tuple(round(float(v), 1) for v in arr.reshape(-1, 3).mean(axis=0))
        except Exception:
            return None

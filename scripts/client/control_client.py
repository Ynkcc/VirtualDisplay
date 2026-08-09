#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Daemon 控制协议客户端库。

实现 docs/README.md 中定义的二进制协议，用于在 daemon 模式下通过 TCP 与 scrcpy server 进行通信。
"""

import socket
import struct
import threading
from typing import Optional, List, Dict, Any

# Socket 角色 (必须与 TcpDesktopConnection 中的常量保持一致)
ROLE_VIDEO = 0
ROLE_AUDIO = 1
ROLE_CONTROL = 2

DEVICE_NAME_FIELD_LENGTH = 64

# 控制消息类型 (客户端 -> 服务端)
TYPE_CREATE_VIRTUAL_DISPLAY = 201
TYPE_RELEASE_VIRTUAL_DISPLAY = 202
TYPE_RESIZE_VIRTUAL_DISPLAY = 203
TYPE_START_ACTIVITY = 204
TYPE_GET_ACTIVE_DISPLAY_IDS = 205
TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID = 206
TYPE_SWITCH_DISPLAY = 207
TYPE_EXIT_DAEMON = 208
TYPE_START_VIDEO_STREAM = 209
TYPE_STOP_VIDEO_STREAM = 210
TYPE_GET_ROTATION = 211
TYPE_FREEZE_ROTATION = 212
TYPE_THAW_ROTATION = 213
TYPE_IS_ROTATION_FROZEN = 214
TYPE_GET_ACTIVE_DISPLAY_INFOS = 215

# 设备响应类型 (服务端 -> 客户端)
TYPE_RESPONSE_GENERIC = 100
TYPE_RESPONSE_ACTIVE_DISPLAYS = 101
TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS = 102


class ControlClient:
    """用于 scrcpy daemon 控制协议的客户端。"""

    def __init__(self, host: str = "127.0.0.1", port: int = 27183):
        self.host = host
        self.port = port
        self.sock: Optional[socket.socket] = None
        self._sequence_lock = threading.Lock()
        self._sequence = 0
        self.session_id = -1
        self.device_name = ""

    def connect(self, timeout: float = 10.0):
        """连接到 daemon 服务端并进行 TCP 握手。"""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect((self.host, self.port))

        # 1. 发送 Socket 角色为 ROLE_CONTROL
        self.sock.sendall(bytes([ROLE_CONTROL]))

        # 2. 接收服务端分配的唯一 4 字节 sessionId (Big-Endian)
        session_data = self._recv_exact(4)
        self.session_id = struct.unpack(">i", session_data)[0]

        # 3. 接收 64 字节的设备名称
        meta_data = self._recv_exact(DEVICE_NAME_FIELD_LENGTH)
        self.device_name = meta_data.rstrip(b'\x00').decode('utf-8', errors='replace')

        # 握手完毕后，将 socket 改为阻塞模式或保持带超时的状态（推荐不设置永久阻塞，以免测试 hang 住）
        self.sock.settimeout(timeout)

    def close(self):
        """关闭连接。"""
        if self.sock:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    def _next_sequence(self) -> int:
        with self._sequence_lock:
            self._sequence += 1
            return self._sequence

    def _send(self, msg_type: int, sequence: int, payload: bytes):
        header = struct.pack(">Bq", msg_type, sequence)
        self.sock.sendall(header + payload)

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError(f"连接已关闭，期望读取 {n} 字节，实际只读取了 {len(buf)} 字节")
            buf.extend(chunk)
        return bytes(buf)

    def _read_response(self, expected_sequence: int) -> Dict[str, Any]:
        """从服务端读取响应。返回解析后的响应字典。"""
        type_byte = self._recv_exact(1)
        resp_type = type_byte[0]

        if resp_type == TYPE_RESPONSE_GENERIC:
            # 协议字段: seq(8B), status_code(4B), display_id(4B), msg_length(4B) -> 共 20 字节
            data = self._recv_exact(20)
            seq, status_code, display_id, msg_len = struct.unpack(">qiii", data)
            msg = self._recv_exact(msg_len).decode("utf-8") if msg_len > 0 else ""
            return {
                "type": resp_type,
                "sequence": seq,
                "status_code": status_code,
                "display_id": display_id,
                "msg": msg,
            }
        elif resp_type == TYPE_RESPONSE_ACTIVE_DISPLAYS:
            # 协议字段: seq(8B), count(4B) -> 共 12 字节
            data = self._recv_exact(12)
            seq, count = struct.unpack(">qi", data)
            ids = []
            if count > 0:
                ids_data = self._recv_exact(count * 4)
                ids = list(struct.unpack(f">{count}i", ids_data))
            return {
                "type": resp_type,
                "sequence": seq,
                "count": count,
                "display_ids": ids,
            }
        elif resp_type == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS:
            # 协议字段: seq(8B), count(4B), count × [id,w,h,dpi,rotation](5×4B)
            data = self._recv_exact(12)
            seq, count = struct.unpack(">qi", data)
            displays = []
            for _ in range(count):
                entry = self._recv_exact(20)
                did, w, h, dpi, rot = struct.unpack(">iiiii", entry)
                displays.append({
                    "display_id": did,
                    "width": w,
                    "height": h,
                    "dpi": dpi,
                    "rotation": rot,
                })
            return {
                "type": resp_type,
                "sequence": seq,
                "count": count,
                "displays": displays,
            }
        else:
            raise ValueError(f"未知的响应类型: {resp_type}")

    def request(self, msg_type: int, payload: bytes) -> Dict[str, Any]:
        """向服务端发送请求并等待序列号匹配的响应。"""
        if not self.sock:
            raise RuntimeError("Socket 未连接")
        sequence = self._next_sequence()
        self._send(msg_type, sequence, payload)
        resp = self._read_response(sequence)
        if resp["sequence"] != sequence:
            raise ValueError(
                f"序列号不匹配: 发送了 {sequence}，但收到了 {resp['sequence']}"
            )
        return resp

    # === Daemon 控制命令接口 ===

    def create_virtual_display(
        self, name: str, width: int, height: int, dpi: int, flags: int
    ) -> Dict[str, Any]:
        """TYPE 201: 创建虚拟显示器。"""
        name_bytes = name.encode("utf-8")
        payload = struct.pack(">i", len(name_bytes)) + name_bytes
        payload += struct.pack(">iiii", width, height, dpi, flags)
        return self.request(TYPE_CREATE_VIRTUAL_DISPLAY, payload)

    def release_virtual_display(self, display_id: int) -> Dict[str, Any]:
        """TYPE 202: 释放虚拟显示器。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_RELEASE_VIRTUAL_DISPLAY, payload)

    def resize_virtual_display(
        self, display_id: int, width: int, height: int, dpi: int
    ) -> Dict[str, Any]:
        """TYPE 203: 调整虚拟显示器分辨率/DPI。"""
        payload = struct.pack(">iiii", display_id, width, height, dpi)
        return self.request(TYPE_RESIZE_VIRTUAL_DISPLAY, payload)

    def start_activity(self, package_name: str, display_id: int) -> Dict[str, Any]:
        """TYPE 204: 在指定显示器上启动 Activity。"""
        pkg_bytes = package_name.encode("utf-8")
        payload = struct.pack(">i", len(pkg_bytes)) + pkg_bytes
        payload += struct.pack(">i", display_id)
        return self.request(TYPE_START_ACTIVITY, payload)

    def get_active_display_ids(self) -> Dict[str, Any]:
        """TYPE 205: 获取当前活跃的显示器 ID 列表。"""
        return self.request(TYPE_GET_ACTIVE_DISPLAY_IDS, b"")

    def inject_input_event_with_display_id(
        self, display_id: int, is_key_event: bool, parcel_bytes: bytes
    ) -> Dict[str, Any]:
        """TYPE 206: 向指定显示器注入按键/触摸等 Input Event。"""
        payload = struct.pack(">i", display_id)
        payload += struct.pack(">B", 1 if is_key_event else 0)
        payload += struct.pack(">i", len(parcel_bytes))
        payload += parcel_bytes
        return self.request(TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, payload)

    def switch_display(self, display_id: int) -> Dict[str, Any]:
        """TYPE 207: 切换显示器。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_SWITCH_DISPLAY, payload)

    def exit_daemon(self) -> Dict[str, Any]:
        """TYPE 208: 退出 Daemon 进程。"""
        return self.request(TYPE_EXIT_DAEMON, b"")

    def start_video_stream(self, display_id: int) -> Dict[str, Any]:
        """TYPE 209: 启动指定显示器的视频流。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_START_VIDEO_STREAM, payload)

    def stop_video_stream(self) -> Dict[str, Any]:
        """TYPE 210: 停止当前连接的视频流。"""
        return self.request(TYPE_STOP_VIDEO_STREAM, b"")

    def get_rotation(self, display_id: int) -> Dict[str, Any]:
        """TYPE 211: 查询指定显示器的当前旋转角度 (0-3)。响应 msg 为旋转值字符串。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_GET_ROTATION, payload)

    def freeze_rotation(self, display_id: int, rotation: int) -> Dict[str, Any]:
        """TYPE 212: 将指定显示器冻结到指定旋转角度 (0-3)。"""
        payload = struct.pack(">ii", display_id, rotation)
        return self.request(TYPE_FREEZE_ROTATION, payload)

    def thaw_rotation(self, display_id: int) -> Dict[str, Any]:
        """TYPE 213: 解冻指定显示器的旋转。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_THAW_ROTATION, payload)

    def is_rotation_frozen(self, display_id: int) -> Dict[str, Any]:
        """TYPE 214: 查询指定显示器旋转是否已冻结。响应 msg 为 0/1。"""
        payload = struct.pack(">i", display_id)
        return self.request(TYPE_IS_ROTATION_FROZEN, payload)

    def get_active_display_infos(self) -> Dict[str, Any]:
        """TYPE 215: 获取活跃虚拟显示器的详细信息列表 (id/width/height/dpi/rotation)。"""
        return self.request(TYPE_GET_ACTIVE_DISPLAY_INFOS, b"")

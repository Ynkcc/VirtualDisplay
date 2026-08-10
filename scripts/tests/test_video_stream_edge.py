# -*- coding: utf-8 -*-
"""
视频流边界用例（适配 b7aef962 之后的自动推流协议）。

核心协议变更：TYPE_START_VIDEO_STREAM(209) / TYPE_STOP_VIDEO_STREAM(210) 已从
服务端移除。视频流生命周期现与 (ROLE_VIDEO, displayId) socket 绑定：
  - VideoClient.connect() 绑定 socket → 服务端 bindVideoSocket 自动启动编码器并推流
  - socket 关闭 / 会话清理 → 自动停止
因此本模块不再调用 start_video_stream/stop_video_stream，而是通过 connect/close
与 capture 的组合来验证边界场景。
"""

import os
import time

import pytest

from client.video_client import VideoClient

OUTPUT_DIR = "/tmp/scrcpy_test"


def _capture_bytes(video_client: VideoClient, duration: float = 3.0) -> int:
    """捕获指定时长并返回提取出的 H.264 字节数。"""
    video_client.start_capture(duration)
    time.sleep(duration + 1.0)
    video_client.stop_capture()
    path = os.path.join(OUTPUT_DIR, "edge_tmp.h264")
    ok = video_client.extract_raw_h264(path)
    return os.path.getsize(path) if ok and os.path.exists(path) else 0


def test_video_auto_starts_on_socket_bind(new_client_factory, daemon_config):
    """ROLE_VIDEO socket 绑定即自动推流 —— 无需任何显式 start 命令。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    client = new_client_factory()
    # 主物理屏始终存在内容，绑定 socket 后应立即开始推流。
    video_client = VideoClient(host, port, client.session_id, 0, log_prefix="AutoStart")
    video_client.connect()
    try:
        size = _capture_bytes(video_client, 3.0)
        assert size > 0, "自动推流未产生任何视频字节 —— bindVideoSocket 未启动编码器"
        print(f"[AutoStart] 捕获 {size}B —— 自动推流正常")
    finally:
        video_client.close()


def test_video_nonexistent_display_session_survives(new_client_factory, daemon_config):
    """为不存在的 displayId 绑定 video socket：ack 正常返回，且会话不被拖垮。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    fake_did = 9999

    client = new_client_factory()
    video_client = VideoClient(host, port, client.session_id, fake_did, log_prefix="NoDisplay")
    # connect() 应能读到 displayId ack（服务端在路由判定后才写 ack）。
    video_client.connect()
    try:
        # 即使推流因 display 不存在而启动失败，会话仍应存活——后续命令可用。
        resp = client.get_active_display_ids()
        assert "display_ids" in resp, "为不存在 displayId 绑定 video socket 后会话失效"
        print(f"[NoDisplay] displayId={fake_did} ack 收到，会话存活 (active_ids={resp.get('display_ids')})")
    finally:
        video_client.close()


def test_video_rebind_same_display(new_client_factory, daemon_config):
    """同一 displayId 重新绑定 video socket：旧 socket 被替换，新 socket 继续推流。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    client = new_client_factory()
    resp = client.create_virtual_display("RebindDisp", 720, 1280, 240, 0)
    assert resp["status_code"] == 0
    did = resp["display_id"]
    client.start_activity("com.android.settings", did)
    time.sleep(0.8)

    # 第一轮：绑定 + 捕获
    vc1 = VideoClient(host, port, client.session_id, did, log_prefix="Rebind1")
    vc1.connect()
    try:
        size1 = _capture_bytes(vc1, 3.0)
        assert size1 > 0, "首次绑定未捕获到视频"
    finally:
        vc1.close()

    # 第二轮：重新绑定（新 socket）+ 捕获
    time.sleep(0.5)
    vc2 = VideoClient(host, port, client.session_id, did, log_prefix="Rebind2")
    vc2.connect()
    try:
        size2 = _capture_bytes(vc2, 3.0)
        assert size2 > 0, "重新绑定后未捕获到视频 —— re-bind 未恢复推流"
        print(f"[Rebind] 第一轮 {size1}B，第二轮 {size2}B —— 重新绑定恢复推流正常")
    finally:
        vc2.close()
        client.release_virtual_display(did)


def test_video_stream_restart_cycle(new_client_factory, daemon_config):
    """start→stop→start 循环（自动推流版）：connect→close→connect→close。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    client = new_client_factory()
    resp = client.create_virtual_display("RestartDisp", 720, 1280, 240, 0)
    assert resp["status_code"] == 0
    did = resp["display_id"]
    client.start_activity("com.android.settings", did)
    time.sleep(0.8)

    for r in (1, 2):
        vc = VideoClient(host, port, client.session_id, did, log_prefix=f"RestartR{r}")
        vc.connect()
        try:
            size = _capture_bytes(vc, 3.0)
            assert size > 0, f"第 {r} 轮捕获为空"
            print(f"[Restart] 第 {r} 轮捕获 {size}B")
        finally:
            vc.close()
        time.sleep(0.3)

    client.release_virtual_display(did)

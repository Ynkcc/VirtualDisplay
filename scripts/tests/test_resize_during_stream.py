# -*- coding: utf-8 -*-
"""
流式过程中 resize 显示器（验证编码器重建 + display_infos 一致性）。

协议要点：视频流绑定到 (ROLE_VIDEO, displayId) socket，resize 显示器会触发
服务端编码器重建。本用例验证：
  1. resize 前视频流正常产出帧；
  2. resize 后视频流恢复产出帧（编码器重建成功，不黑屏/断流）；
  3. get_active_display_infos 反映 resize 后的新尺寸。
"""

import os
import time

from client.control_client import TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS
from client.video_client import VideoClient

OUTPUT_DIR = "/tmp/scrcpy_test"


def _capture_bytes(video_client: VideoClient, tag: str, duration: float = 2.5) -> int:
    path = os.path.join(OUTPUT_DIR, f"resize_{tag}.h264")
    video_client.start_capture(duration)
    time.sleep(duration + 0.8)
    video_client.stop_capture()
    ok = video_client.extract_raw_h264(path)
    return os.path.getsize(path) if ok and os.path.exists(path) else 0


def test_resize_during_stream(new_client_factory, daemon_config):
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    client = new_client_factory()

    # 初始 800x600
    resp = client.create_virtual_display("ResizeStream", 800, 600, 240, 0)
    assert resp["status_code"] == 0, f"创建显示器失败: {resp.get('msg')}"
    did = resp["display_id"]
    client.start_activity("com.android.settings", did)
    time.sleep(0.8)

    vc = VideoClient(host, port, client.session_id, did, log_prefix="Resize")
    vc.connect()
    try:
        # 1. resize 前先捕获，确认流在跑
        size_before = _capture_bytes(vc, "before", 2.0)
        assert size_before > 0, "resize 前未捕获到视频 —— 流未启动"

        # 2. 中途 resize 到 1280x720
        r = client.resize_virtual_display(did, 1280, 720, 240)
        assert r["status_code"] == 0, f"resize 失败: {r.get('msg')}"

        # 3. 验证 get_active_display_infos 反映新尺寸
        infos = client.get_active_display_infos()
        assert infos["type"] == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS, "get_active_display_infos 响应类型异常"
        target = next((d for d in infos["displays"] if d["display_id"] == did), None)
        assert target is not None, f"resize 后 display_infos 未包含 did={did}"
        assert target["width"] == 1280, f"width 未更新为 1280: {target}"
        assert target["height"] == 720, f"height 未更新为 720: {target}"

        # 4. resize 后继续捕获，验证流恢复（编码器重建）
        time.sleep(0.5)  # 让编码器重建
        size_after = _capture_bytes(vc, "after", 2.5)
        assert size_after > 0, "resize 后未恢复视频流 —— 编码器重建失败"
        print(f"[ResizeDuringStream] before={size_before}B after={size_after}B, "
              f"infos=1280x720 —— 流恢复 + 尺寸更新正常")
    finally:
        vc.close()
        client.release_virtual_display(did)

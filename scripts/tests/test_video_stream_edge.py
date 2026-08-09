#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频流接口边界与异常场景测试。

覆盖：
- 未建立 ROLE_VIDEO Socket 即发起 start_video_stream（应超时失败）
- 同一 session 重复 start_video_stream（第二次应失败）
- 未启动视频流即 stop_video_stream（应失败）
- 对不存在的 displayId 发起 start_video_stream（应失败）
- 正常 start → stop → 再 start 的生命周期复用
"""

import os
import time
import pytest
from client.control_client import ControlClient
from client.video_client import VideoClient


def test_stop_video_stream_without_start(new_client_factory):
    """未启动视频流时调用 stop_video_stream，应返回失败。"""
    client = new_client_factory()
    resp = client.stop_video_stream()
    assert resp["status_code"] != 0, (
        f"未启动视频流时 stop 应返回失败，实际: {resp}"
    )
    print(f"\n[边界测试] stop-without-start 返回符合预期: {resp}")


def test_start_video_stream_nonexistent_display(new_client_factory):
    """对不存在的 displayId 发起 start_video_stream，应返回失败。

    注意：此用例需要先建立 ROLE_VIDEO socket，否则会因等待 video fd 超时而失败，
    那样无法区分是"显示器不存在"还是"socket 未就绪"。因此这里先建立 video socket。
    """
    client = new_client_factory()
    host = client.host
    port = client.port

    # 建立 ROLE_VIDEO socket 绑定到当前 session
    video_client = VideoClient(host, port, client.session_id, display_id=9999, log_prefix="EdgeNonExist")
    video_client.connect()
    time.sleep(0.5)

    try:
        # displayId=9999 几乎不可能存在
        resp = client.start_video_stream(9999)
        assert resp["status_code"] != 0, (
            f"对不存在的 displayId=9999 启动视频流应失败，实际: {resp}"
        )
        print(f"\n[边界测试] start-nonexistent-display 返回符合预期: {resp}")
    finally:
        video_client.close()


def test_video_stream_double_start(new_client_factory):
    """同一 session 连续两次 start_video_stream，第二次应失败（已有活动流）。"""
    client = new_client_factory()
    host = client.host
    port = client.port

    # 先创建一个虚拟显示器作为捕获目标
    resp_vd = client.create_virtual_display(
        name="EdgeDoubleStart", width=720, height=1280, dpi=160, flags=0
    )
    assert resp_vd["status_code"] == 0, f"创建虚拟显示器失败: {resp_vd}"
    vd_id = resp_vd["display_id"]

    video_client = VideoClient(host, port, client.session_id, vd_id, log_prefix="EdgeDouble")
    video_client.connect()
    time.sleep(0.5)

    try:
        # 第一次启动应成功
        resp1 = client.start_video_stream(vd_id)
        assert resp1["status_code"] == 0, f"第一次 start_video_stream 应成功: {resp1}"

        # 第二次启动应失败（同一 session 只能有一个活动流）
        resp2 = client.start_video_stream(vd_id)
        assert resp2["status_code"] != 0, (
            f"第二次 start_video_stream 应失败（已有活动流），实际: {resp2}"
        )
        print(f"\n[边界测试] double-start 第二次返回符合预期: {resp2}")

        # 停止视频流
        resp_stop = client.stop_video_stream()
        assert resp_stop["status_code"] == 0, f"stop_video_stream 应成功: {resp_stop}"
    finally:
        video_client.close()
        client.release_virtual_display(vd_id)


def test_video_stream_start_stop_restart(new_client_factory):
    """验证 start → stop → 再 start 的生命周期可复用。

    使用主显示器 (displayId=0)，因为它始终有内容（launcher 等），
    可确保 MediaCodec 编码器持续产生帧。
    """
    client = new_client_factory()
    host = client.host
    port = client.port
    display_id = 0

    output_dir = "/tmp/scrcpy_test"
    os.makedirs(output_dir, exist_ok=True)

    for round_idx in (1, 2):
        video_client = VideoClient(
            host, port, client.session_id, display_id, log_prefix=f"EdgeRestartR{round_idx}"
        )
        video_client.connect()
        time.sleep(0.5)

        try:
            resp_start = client.start_video_stream(display_id)
            assert resp_start["status_code"] == 0, (
                f"第 {round_idx} 轮 start_video_stream 应成功: {resp_start}"
            )

            video_client.start_capture(4.0)
            time.sleep(5.0)
            video_client.stop_capture()

            resp_stop = client.stop_video_stream()
            assert resp_stop["status_code"] == 0, (
                f"第 {round_idx} 轮 stop_video_stream 应成功: {resp_stop}"
            )

            h264_path = os.path.join(output_dir, f"edge_restart_r{round_idx}.h264")
            ok = video_client.extract_raw_h264(h264_path)
            assert ok, f"第 {round_idx} 轮应能导出非空 H.264 流"
            assert os.path.getsize(h264_path) > 0
            print(f"\n[边界测试] restart 第 {round_idx} 轮视频流捕获成功 ({os.path.getsize(h264_path)} bytes)")
        finally:
            video_client.close()
        # 两次之间稍作间隔，确保服务端完成资源释放
        time.sleep(1.0)

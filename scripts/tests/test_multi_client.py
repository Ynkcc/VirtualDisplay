#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多客户端并发连接、视频流捕获及截图验证测试用例。
"""

import os
import time
import pytest
from client.control_client import ControlClient
from client.video_client import VideoClient


def test_multi_client_video_stream(new_client_factory, daemon_config):
    """
    多客户端多屏幕并发测试：
    1. 主客户端创建虚拟屏幕
    2. 主屏幕启动 Settings，虚拟屏幕启动 Calculator
    3. 主屏幕与虚拟屏幕客户端并发建立 ROLE_VIDEO 连接
    4. 分别拉取 5 秒视频流并导出 NAL 帧
    5. 调用 FFmpeg 解码第一帧为 PNG 并验证其大小
    6. 自动释放虚拟屏幕
    """
    host = daemon_config["bind"]
    port = daemon_config["port"]
    output_dir = "/tmp/scrcpy_test"
    os.makedirs(output_dir, exist_ok=True)

    # 1. 建立主客户端连接并校验活跃显示器
    client0 = new_client_factory()
    resp_displays = client0.get_active_display_ids()
    assert resp_displays["type"] == 101

    # 2. 创建虚拟显示器
    vd_name = "TestDisplay_MultiClient"
    status, vd_id = -1, None
    try:
        resp_vd = client0.create_virtual_display(name=vd_name, width=1280, height=720, dpi=240, flags=0)
        assert resp_vd["status_code"] == 0, f"创建虚拟显示器失败: {resp_vd.get('msg')}"
        vd_id = resp_vd["display_id"]
        assert vd_id > 0

        # 3. 在各自显示器上启动对应的 Activity
        # 主显示器启动 Settings
        ok_main = client0.start_activity("com.android.settings", 0)
        assert ok_main["status_code"] == 0

        # 虚拟显示器启动 Calculator
        # 创建 client1 控制虚拟显示器
        client1 = new_client_factory()
        ok_vd = client1.start_activity("com.android.calculator2", vd_id)
        # 很多设备上没有内置的计算器应用包 "com.android.calculator2"，
        # 即使无法成功启动也并不算作整体流测试的失败，这里我们不强依赖其状态码为0，但如果启动成功最好。
        # 这里记录日志即可，断言不硬性卡住，但我们会尝试启动。
        print(f"\n[多客户端测试] 在显示器 {vd_id} 上启动计算器的响应: {ok_vd}")

        # 4. 创建各自的视频连接并连接
        video_client_main = VideoClient(host, port, client0.session_id, 0, log_prefix="Main")
        video_client_vd = VideoClient(host, port, client1.session_id, vd_id, log_prefix="Virtual")

        video_client_main.connect()
        video_client_vd.connect()

        # 稍微等待 0.5 秒，以确保服务端线程完成 Video FD 的绑定和反射，避免 race condition
        time.sleep(0.5)

        # 5. 向服务端发起启动视频流请求
        resp_stream_main = client0.start_video_stream(0)
        assert resp_stream_main["status_code"] == 0, f"主显示器启动视频流请求失败: {resp_stream_main.get('msg')}"

        resp_stream_vd = client1.start_video_stream(vd_id)
        assert resp_stream_vd["status_code"] == 0, f"虚拟显示器启动视频流请求失败: {resp_stream_vd.get('msg')}"

        # 6. 后台并发录制视频流数据 (持续 6 秒)
        capture_duration = 6.0
        video_client_main.start_capture(capture_duration)
        video_client_vd.start_capture(capture_duration)
        
        print(f"\n[多客户端测试] 正在并发捕获主显示器和虚拟显示器 {vd_id} 的视频流 ({capture_duration}s)...")
        time.sleep(capture_duration + 1)

        video_client_main.stop_capture()
        video_client_vd.stop_capture()

        # 7. 向服务端发送停止视频流请求
        client0.stop_video_stream()
        client1.stop_video_stream()

        # 8. 提取原始 NAL 字节并保存到 H.264
        h264_main = os.path.join(output_dir, "session_main.h264")
        h264_vd = os.path.join(output_dir, f"session_vd_{vd_id}.h264")

        ok_h264_main = video_client_main.extract_raw_h264(h264_main)
        ok_h264_vd = video_client_vd.extract_raw_h264(h264_vd)

        assert ok_h264_main, "主显示器应该能够成功导出非空 H.264 视频流"
        assert ok_h264_vd, f"虚拟显示器 {vd_id} 应该能够成功导出非空 H.264 视频流"

        # 9. 解码并生成截图
        png_main = os.path.join(output_dir, "session_main.png")
        png_vd = os.path.join(output_dir, f"session_vd_{vd_id}.png")

        ok_png_main = video_client_main.take_screenshot(h264_main, png_main)
        ok_png_vd = video_client_vd.take_screenshot(h264_vd, png_vd)

        # 进行断言
        assert ok_png_main, "主显示器 YUV->PNG 截图转换应该成功"
        assert ok_png_vd, "虚拟显示器 YUV->PNG 截图转换应该成功"

        assert os.path.exists(png_main) and os.path.getsize(png_main) > 0, "主显示器截图文件不存在或为空"
        assert os.path.exists(png_vd) and os.path.getsize(png_vd) > 0, "虚拟显示器截图文件不存在或为空"

        print(f"[多客户端测试] 截图已成功导出：\n  - 主显示器: {png_main}\n  - 虚拟显示器: {png_vd}")

    finally:
        # 10. 清理：关闭 video connections 并释放虚拟屏幕，防止显示器泄漏
        if 'video_client_main' in locals():
            video_client_main.close()
        if 'video_client_vd' in locals():
            video_client_vd.close()
        if vd_id is not None:
            print(f"[多客户端测试] 正在释放虚拟显示器 {vd_id}...")
            try:
                client0.release_virtual_display(vd_id)
            except Exception as e:
                print(f"[WARN] 自动释放虚拟显示器失败: {e}")

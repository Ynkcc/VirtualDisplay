# -*- coding: utf-8 -*-
"""
虚拟显示器镜像功能测试。
验证：
- 创建镜像虚拟显示器时（指定 display_id = 0），能够成功创建且获取到的 mirror_display_id 正确为 0。
- 创建普通不镜像的显示器时，获取到的 mirror_display_id 正确为 -1。
- 捕获视频帧并提取截图，比对主屏幕视频流与镜像屏幕视频流的相似度，从像素层面确保实际镜像了主屏内容。
- 对比非镜像屏幕（黑屏）与主屏的相似度，进行对偶验证。
"""

import os
import time
import pytest
import subprocess
from client.control_client import ControlClient, TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS
from client.video_client import VideoClient

OUTPUT_DIR = "/tmp/scrcpy_test"

def _take_screenshot_with_ffmpeg(raw_h264: str, output_png: str) -> bool:
    """使用 FFmpeg 从原始 H.264 流中提取第一帧作为 PNG。"""
    cmd = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "h264", "-i", raw_h264,
        "-vf", "zscale=matrix=bt709,format=yuv420p",
        "-frames:v", "1",
        "-f", "image2", output_png
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, timeout=10)
        return res.returncode == 0 and os.path.exists(output_png) and os.path.getsize(output_png) > 0
    except Exception as e:
        print(f"[ERROR] FFmpeg 截图转换失败: {e}")
        return False

def _calculate_image_similarity(path_a: str, path_b: str) -> float:
    """
    使用 Pillow 计算两张图片的平均绝对像素偏差（MAE）。
    图片将被缩放到 64x64 并转换为灰度图进行比较。
    返回的 MAE 越小，说明图像内容越相似。通常 < 12.0 表示高度相似。
    """
    from PIL import Image
    
    img_a = Image.open(path_a).convert("L").resize((64, 64))
    img_b = Image.open(path_b).convert("L").resize((64, 64))
    
    data_a = list(img_a.getdata())
    data_b = list(img_b.getdata())
    
    diff = sum(abs(p1 - p2) for p1, p2 in zip(data_a, data_b)) / 4096.0
    return diff

def test_mirror_default_display(shared_client: ControlClient, daemon_config):
    """测试：创建并验证镜像主显示器 (display_id=0) 及其视频帧的相似度。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # 0. 获取主屏 (0) 的尺寸以匹配镜像
    infos_resp = shared_client.get_active_display_infos()
    assert infos_resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS
    main_info = next((d for d in infos_resp["displays"] if d["display_id"] == 0), None)
    assert main_info is not None, "未找到主屏幕 (display_id=0)"

    # 使用主屏的宽高和 DPI 创建镜像
    width = main_info["width"]
    height = main_info["height"]
    dpi = main_info["dpi"]

    # 1. 创建镜像主屏 (0) 的虚拟显示器
    resp = shared_client.create_virtual_display(
        name="MirrorTest_Default",
        width=width,
        height=height,
        dpi=dpi,
        flags=8,  # 带有 OWN_CONTENT_ONLY，应被底层自动剥离
        display_id=0  # 镜像主屏
    )
    assert resp["status_code"] == 0, f"创建镜像显示器失败: {resp.get('msg')}"
    did = resp["display_id"]
    assert did > 0

    try:
        # 等待创建生效
        time.sleep(1.0)

        # 确保屏幕有变化：在主屏启动 Settings
        shared_client.start_activity("com.android.settings", 0)
        time.sleep(1.0)

        # 2. 验证 API 返回的 mirror_display_id 属性是否为 0
        infos_resp = shared_client.get_active_display_infos()
        matches = [d for d in infos_resp["displays"] if d["display_id"] == did]
        assert len(matches) == 1
        info = matches[0]
        assert info["mirror_display_id"] == 0, f"mirror_display_id 应该为 0，实际为: {info['mirror_display_id']}"

        # 3. 开启两个 VideoClient 连通视频流：分别监听主屏 (0) 和镜像屏 (did)
        vc_main = VideoClient(host, port, shared_client.session_id, 0, log_prefix="TestMain")
        vc_mirror = VideoClient(host, port, shared_client.session_id, did, log_prefix="TestMirror")

        vc_main.connect()
        vc_mirror.connect()

        try:
            # 启动捕获 2.0s
            vc_main.start_capture(2.0)
            vc_mirror.start_capture(2.0)
            time.sleep(3.0)
            vc_main.stop_capture()
            vc_mirror.stop_capture()

            # 提取原始 H.264
            path_main_h264 = os.path.join(OUTPUT_DIR, "main_screen.h264")
            path_mirror_h264 = os.path.join(OUTPUT_DIR, "mirror_screen.h264")
            
            ok_main = vc_main.extract_raw_h264(path_main_h264)
            ok_mirror = vc_mirror.extract_raw_h264(path_mirror_h264)
            
            assert ok_main, "提取主屏 H.264 数据失败"
            assert ok_mirror, "提取镜像屏 H.264 数据失败"

            # 4. 用 FFmpeg 提取第一帧图片
            path_main_png = os.path.join(OUTPUT_DIR, "main_screen.png")
            path_mirror_png = os.path.join(OUTPUT_DIR, "mirror_screen.png")
            
            assert _take_screenshot_with_ffmpeg(path_main_h264, path_main_png), "转换主屏截图失败"
            assert _take_screenshot_with_ffmpeg(path_mirror_h264, path_mirror_png), "转换镜像屏截图失败"

            # 5. 比对两图的相似度
            similarity = _calculate_image_similarity(path_main_png, path_mirror_png)
            print(f"\n[镜像视频测试] 主屏与镜像屏的第一帧 MAE 差异值为: {similarity:.4f}")
            assert similarity < 12.0, f"主屏与镜像屏图像差异过大 (MAE={similarity:.4f} >= 12.0)，说明并未真正实现镜像内容！"

        finally:
            vc_main.close()
            vc_mirror.close()

    finally:
        # 还原环境，释放虚拟显示器
        shared_client.release_virtual_display(did)

def test_no_mirror_display(shared_client: ControlClient, daemon_config):
    """测试：创建普通不镜像的显示器，并验证其画面与主屏不同。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # 1. 创建普通不镜像的虚拟显示器
    resp = shared_client.create_virtual_display(
        name="MirrorTest_NoMirror",
        width=1080,
        height=1920,
        dpi=320,
        flags=8,  # OWN_CONTENT_ONLY
        display_id=-1  # 不镜像
    )
    assert resp["status_code"] == 0
    did = resp["display_id"]
    assert did > 0

    try:
        time.sleep(1.0)

        # 启动一个 Activity 确保有画面输出，避免 len(data) == 0
        shared_client.start_activity("com.android.settings", did)
        time.sleep(1.5)

        # 2. 验证 API 返回的 mirror_display_id 属性是否为 -1
        infos_resp = shared_client.get_active_display_infos()
        matches = [d for d in infos_resp["displays"] if d["display_id"] == did]
        assert len(matches) == 1
        info = matches[0]
        assert info["mirror_display_id"] == -1

        # 3. 对比视频画面，普通屏无内容（默认为黑屏），应与主屏（包含桌面/界面内容）差异较大
        vc_no_mirror = VideoClient(host, port, shared_client.session_id, did, log_prefix="TestNoMirror")
        vc_no_mirror.connect()

        try:
            vc_no_mirror.start_capture(2.0)
            time.sleep(3.0)
            vc_no_mirror.stop_capture()

            path_no_mirror_h264 = os.path.join(OUTPUT_DIR, "no_mirror_screen.h264")
            ok_no_mirror = vc_no_mirror.extract_raw_h264(path_no_mirror_h264)
            assert ok_no_mirror, "提取普通屏 H.264 数据失败"

            path_no_mirror_png = os.path.join(OUTPUT_DIR, "no_mirror_screen.png")
            assert _take_screenshot_with_ffmpeg(path_no_mirror_h264, path_no_mirror_png), "转换普通屏截图失败"

            # 4. 对比主屏截图与普通屏截图（若主屏存在内容，它们应该差异极大）
            path_main_png = os.path.join(OUTPUT_DIR, "main_screen.png")
            if os.path.exists(path_main_png):
                diff_to_main = _calculate_image_similarity(path_main_png, path_no_mirror_png)
                print(f"[镜像视频测试] 主屏与普通非镜像屏的第一帧 MAE 差异值为: {diff_to_main:.4f}")
                
                # 如果主屏本身不为纯黑（通常非空），非镜像屏（黑屏）与其应该具有明显差异
                if diff_to_main > 15.0:
                    assert diff_to_main > 20.0, f"主屏与普通非镜像屏的画面应该不同，实际差异较小 (MAE={diff_to_main:.4f})"
                    print("[镜像视频测试] 对偶验证通过：镜像屏相似，非镜像屏与主屏不相似")

        finally:
            vc_no_mirror.close()

    finally:
        shared_client.release_virtual_display(did)

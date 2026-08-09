#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
紧时序视频流验证：复刻 App 端 DaemonTransport.connect() 的真实握手顺序。

App 的连接流程（DaemonTransport.connect）：
  1. 控制 socket 连接 → ROLE_CONTROL → 读 sessionId + 64B deviceMeta
  2. 视频 socket 连接 → ROLE_VIDEO + sessionId   （与 1 背靠背，零间隔）
  3. 立即调用 startVideoStream(209)              （无 sleep）

而 tests/test_video_stream_edge.py 在 video_client.connect() 与 start_video_stream
之间插入了 time.sleep(0.5)，这给服务端 session.run() 充足时间完成 videoController
的创建，从而掩盖了“视频 socket 早于 videoController 创建到达”的竞态。

本脚本去掉一切 sleep，背靠背执行 1→2→3，并在多轮中重复，以确定性复现该竞态。
修复前：start_video_stream 会因 ensureVideoFdReady 10s 超时而失败。
修复后：start_video_stream 立即返回 status_code=0，并能捕获到视频帧。
"""

import os
import sys
import time
import struct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client.control_client import ControlClient
from client.video_client import VideoClient

HOST = os.environ.get("DAEMON_HOST", "127.0.0.1")
PORT = int(os.environ.get("DAEMON_PORT", "27183"))
DISPLAY_ID = 0  # 物理主屏，始终有内容
ROUNDS = 5


def run_round(round_idx: int) -> bool:
    client = ControlClient(host=HOST, port=PORT)

    # 1. 控制 socket 握手
    client.connect()

    # 2. 立即（零间隔）建立视频 socket —— 复刻 App 背靠背时序
    video_client = VideoClient(HOST, PORT, client.session_id, DISPLAY_ID,
                               log_prefix=f"TightR{round_idx}")
    video_client.connect()
    # 注意：这里刻意不 sleep

    try:
        # 3. 立即发起 start_video_stream
        t0 = time.time()
        resp = client.start_video_stream(DISPLAY_ID)
        elapsed = time.time() - t0

        if resp["status_code"] != 0:
            print(f"  [R{round_idx}] FAIL  status={resp['status_code']} msg={resp.get('msg')} "
                  f"({elapsed:.2f}s)")
            return False

        # 捕获少量视频帧确认数据通道真的可用
        video_client.start_capture(2.0)
        time.sleep(2.5)
        video_client.stop_capture()

        out = f"/tmp/scrcpy_test/tight_r{round_idx}.h264"
        ok = video_client.extract_raw_h264(out)
        size = os.path.getsize(out) if ok and os.path.exists(out) else 0

        stop_resp = client.stop_video_stream()
        print(f"  [R{round_idx}] OK   start={elapsed:.2f}s  frames={size}B  "
              f"stop={stop_resp['status_code']}")
        return size > 0
    finally:
        video_client.close()
        client.close()


def main():
    os.makedirs("/tmp/scrcpy_test", exist_ok=True)
    print(f"紧时序视频流验证: {HOST}:{PORT} display={DISPLAY_ID} rounds={ROUNDS}")
    print("（复刻 App 背靠背握手，无任何 sleep）")
    results = []
    for i in range(1, ROUNDS + 1):
        try:
            results.append(run_round(i))
        except Exception as e:
            print(f"  [R{i}] ERROR {e}")
            results.append(False)
        time.sleep(0.3)

    passed = sum(results)
    print(f"\n结果: {passed}/{ROUNDS} 轮成功")
    if passed == ROUNDS:
        print("结论: 竞态已修复 ✓")
        sys.exit(0)
    else:
        print("结论: 仍存在超时/失败 ✗")
        sys.exit(1)


if __name__ == "__main__":
    main()

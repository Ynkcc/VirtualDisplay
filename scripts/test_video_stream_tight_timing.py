#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
紧时序视频流验证：复刻 App 端背靠背握手时序，且适配 b7aef962 之后的
「ROLE_VIDEO socket 绑定即自动推流」协议。

握手时序（零间隔）：
  1. 协商 socket → ROLE_NEGOTIATION → 读 sessionId → 读 64B deviceMeta
     → 发送 CONFIGURE_SESSION(216) 并等待响应 (会话进入 CONFIGURED 阶段)
  2. 视频 socket → ROLE_VIDEO + sessionId + displayId → 读 4B displayId ack
     （服务端在 bindVideoSocket 时自动启动编码器并开始推流）
  3. 立即捕获视频帧（无 sleep）

关键点：CONFIGURE_SESSION 的响应在 phase=CONFIGURED 之后才发出，因此 connect()
返回后会话必然已 CONFIGURED，后续 ROLE_VIDEO socket 不会被阶段门控拒绝。
本脚本去掉一切 sleep，背靠背执行 1→2→3，并在多轮中重复，验证零间隔下推流稳定。
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client.control_client import ControlClient
from client.video_client import VideoClient

HOST = os.environ.get("DAEMON_HOST", "127.0.0.1")
PORT = int(os.environ.get("DAEMON_PORT", "27183"))
DISPLAY_ID = 0  # 物理主屏，始终有内容
ROUNDS = 5


def run_round(round_idx: int) -> bool:
    client = ControlClient(host=HOST, port=PORT)

    # 1. 协商握手（内部自动发送 CONFIGURE_SESSION 并等待响应）
    t0 = time.time()
    client.connect()
    # 2. 立即（零间隔）建立视频 socket —— 复刻 App 背靠背时序
    video_client = VideoClient(HOST, PORT, client.session_id, DISPLAY_ID,
                               log_prefix=f"TightR{round_idx}")
    # connect() 绑定 socket 时服务端自动启动推流
    video_client.connect()
    elapsed = time.time() - t0
    # 注意：这里刻意不 sleep

    try:
        # 3. 立即捕获少量视频帧确认数据通道真的可用
        video_client.start_capture(2.0)
        time.sleep(2.5)
        video_client.stop_capture()

        out = f"/tmp/scrcpy_test/tight_r{round_idx}.h264"
        ok = video_client.extract_raw_h264(out)
        size = os.path.getsize(out) if ok and os.path.exists(out) else 0

        print(f"  [R{round_idx}] OK   handshake={elapsed:.2f}s  frames={size}B")
        return size > 0
    finally:
        video_client.close()  # 关闭 socket 即自动停止推流
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
            print(f"  [R{i}] ERROR {type(e).__name__}: {e}")
            results.append(False)
        time.sleep(0.3)

    passed = sum(results)
    print(f"\n结果: {passed}/{ROUNDS} 轮成功")
    if passed == ROUNDS:
        print("结论: 零间隔握手 + 自动推流稳定 ✓")
        sys.exit(0)
    else:
        print("结论: 仍存在超时/失败 ✗")
        sys.exit(1)


if __name__ == "__main__":
    main()

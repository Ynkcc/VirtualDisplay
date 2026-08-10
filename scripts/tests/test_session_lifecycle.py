# -*- coding: utf-8 -*-
"""
会话生命周期与 CONFIGURE_SESSION 声明路径（验证 b7aef962 阶段门控）。

b7aef962 引入的 CONFIGURE_SESSION(216) 阶段门控：
  - 默认 (rolesMask=0 + 空 entries) 允许任意 (role, displayId) —— ControlClient.connect()
    已自动发送此默认配置。
  - 显式声明 roles_entries 后，服务端切换为精确 (role, displayId) 匹配模式：
    未声明的 displayId 的 role socket 会被拒绝（socket 关闭，不写 ack）。

本模块验证：
  1. 显式声明 (ROLE_VIDEO, did) 后，为该 did 打开 video socket 成功并捕获到帧；
  2. 仅声明 did_a 时，为未声明的 did_b 打开 video socket 被拒绝 (ConnectionError)。
"""

import os
import time

import pytest

from client.control_client import ROLE_VIDEO
from client.video_client import VideoClient

OUTPUT_DIR = "/tmp/scrcpy_test"


def test_configure_explicit_entries_allows_declared(new_client_factory, daemon_config):
    """显式声明 (ROLE_VIDEO, did) 后，该 did 的 video socket 被接受。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    client = new_client_factory()
    resp = client.create_virtual_display("CfgDeclared", 720, 1280, 240, 0)
    assert resp["status_code"] == 0
    did = resp["display_id"]
    client.start_activity("com.android.settings", did)
    time.sleep(0.8)

    # 重新配置：显式声明 (ROLE_VIDEO, did)
    r = client.configure_session(roles_entries=[{"role": ROLE_VIDEO, "display_id": did}])
    assert r["status_code"] == 0, f"显式 CONFIGURE_SESSION 失败: {r.get('msg')}"

    vc = VideoClient(host, port, client.session_id, did, log_prefix="CfgDeclared")
    vc.connect()  # 声明命中 → 接受
    try:
        vc.start_capture(2.5)
        time.sleep(3.0)
        vc.stop_capture()
        path = os.path.join(OUTPUT_DIR, f"cfg_declared_{did}.h264")
        ok = vc.extract_raw_h264(path)
        size = os.path.getsize(path) if ok and os.path.exists(path) else 0
        assert size > 0, "声明命中的 displayId 视频流为空"
        print(f"[CfgDeclared] did={did} 声明后视频流正常 ({size}B)")
    finally:
        vc.close()
        client.release_virtual_display(did)


def test_configure_undeclared_display_rejected(new_client_factory, daemon_config):
    """仅声明 did_a 时，为未声明的 did_b 打开 video socket 被拒绝。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]

    client = new_client_factory()
    resp_a = client.create_virtual_display("CfgA", 720, 1280, 240, 0)
    assert resp_a["status_code"] == 0
    did_a = resp_a["display_id"]
    resp_b = client.create_virtual_display("CfgB", 720, 1280, 240, 0)
    assert resp_b["status_code"] == 0
    did_b = resp_b["display_id"]
    assert did_a != did_b

    # 只声明 did_a，不声明 did_b
    r = client.configure_session(roles_entries=[{"role": ROLE_VIDEO, "display_id": did_a}])
    assert r["status_code"] == 0, f"显式 CONFIGURE_SESSION 失败: {r.get('msg')}"

    # 为未声明的 did_b 打开 video socket → 服务端 acceptRoleSocket 返回 false，
    # 关闭 socket 且不写 ack；客户端 _recv_exact(4) 读到 EOF → ConnectionError。
    vc_b = VideoClient(host, port, client.session_id, did_b, log_prefix="CfgReject")
    with pytest.raises((ConnectionError, OSError)):
        vc_b.connect()
    print(f"[CfgReject] 未声明的 displayId={did_b} video socket 被拒绝 ✓")

    # 会话仍存活：声明命中的 did_a 仍可正常打开 video socket
    client.start_activity("com.android.settings", did_a)
    time.sleep(0.8)
    vc_a = VideoClient(host, port, client.session_id, did_a, log_prefix="CfgAccept")
    vc_a.connect()
    try:
        vc_a.start_capture(3.0)
        time.sleep(3.5)
        vc_a.stop_capture()
        path = os.path.join(OUTPUT_DIR, f"cfg_accept_{did_a}.h264")
        ok = vc_a.extract_raw_h264(path)
        size = os.path.getsize(path) if ok and os.path.exists(path) else 0
        assert size > 0, "声明命中的 did_a 在拒绝 did_b 后仍应可推流"
        print(f"[CfgReject] 拒绝 did_b 后 did_a 视频流仍正常 ({size}B) —— 会话未受影响")
    finally:
        vc_a.close()
        client.release_virtual_display(did_a)
        client.release_virtual_display(did_b)

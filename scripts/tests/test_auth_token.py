# -*- coding: utf-8 -*-
"""
daemon_secret_token 认证测试 (正向)。

当服务端启动时配置了 daemon_secret_token，所有客户端在握手阶段
(发送 ROLE_NEGOTIATION 并收到 sessionId 之后) 必须发送
  4 字节 Big-Endian 长度 + UTF-8 token
进行认证，否则服务端关闭连接并将该 IP 加入进程级黑名单 (ConcurrentHashMap，
daemon 重启后清空)。

本模块仅做正向验证 (正确 token 能通过认证并正常操作)。
负向测试 (错误/缺失 token → 连接被拒 + IP 黑名单) 因会污染 127.0.0.1
导致同一 pytest 会话中所有后续用例失败，故不在此执行；如需验证，可单独
启动一个带 token 的 daemon 实例 (独立端口) 手动测试。

无 --token 时，整个模块跳过 (pytest.skip)。
"""

import pytest

from client.control_client import ControlClient


def _token_or_skip(daemon_config):
    token = daemon_config.get("token")
    if not token:
        pytest.skip("未配置 --token，跳过认证测试 (daemon 未启用 secret_token 认证)")
    return token


def test_correct_token_authenticates(daemon_config):
    """配置 token 时，使用正确 token 的客户端能完成握手并执行命令。"""
    token = _token_or_skip(daemon_config)
    host = daemon_config["bind"]
    port = daemon_config["port"]

    client = ControlClient(host=host, port=port, secret_token=token)
    try:
        client.connect()
        # 握手成功 + CONFIGURE_SESSION 成功 → 说明 token 认证已通过
        assert client.session_id > 0, "正确 token 应能获得有效 sessionId"
        assert client._configured, "正确 token 后 CONFIGURE_SESSION 应已发送"
        # 进一步验证能正常执行命令
        resp = client.get_active_display_ids()
        assert "display_ids" in resp, "认证后应能正常执行命令"
        print(f"[Auth] 正确 token 认证通过，session_id={client.session_id} ✓")
    finally:
        client.close()


def test_shared_client_already_authenticated(shared_client, daemon_config):
    """shared_client (conftest 中用 token 创建) 已隐式验证认证，这里显式断言。"""
    token = _token_or_skip(daemon_config)
    # shared_client 如果能执行 get_active_display_ids，说明认证已通过
    resp = shared_client.get_active_display_ids()
    assert resp["type"] in (100, 101, 102) or "display_ids" in resp, \
        "shared_client 应在认证后能正常通信"
    assert shared_client.secret_token == token, "shared_client 应使用配置的 token"
    print(f"[Auth] shared_client 认证态确认 ✓")


def test_token_via_env_or_factory(new_client_factory, daemon_config):
    """new_client_factory 创建的客户端也使用了 token，能正常操作。"""
    _token_or_skip(daemon_config)
    client = new_client_factory()
    # 能创建并释放显示器即证明认证链路完整
    resp = client.create_virtual_display("AuthTokenDisp", 720, 1280, 240, 0)
    assert resp["status_code"] == 0, f"认证后创建失败: {resp.get('msg')}"
    did = resp["display_id"]
    try:
        r = client.release_virtual_display(did)
        assert r["status_code"] == 0
        print(f"[Auth] factory 客户端 token 认证 + 操作链路完整 ✓")
    finally:
        try:
            client.release_virtual_display(did)
        except Exception:
            pass

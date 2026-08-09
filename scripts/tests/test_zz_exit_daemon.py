#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
单独的 Daemon 退出测试。保证它在所有其他测试（API 测试和多客户端测试）完成之后才执行。
"""

import time
import socket
import pytest
from client.control_client import ControlClient


def test_exit_daemon_safely(new_client_factory):
    """测试 Daemon 服务端正常退出。"""
    # 建立一条独立的连接
    client = new_client_factory()
    
    # 发送退出指令
    resp = client.exit_daemon()
    assert resp["status_code"] == 0, f"退出 Daemon 失败: {resp.get('msg')}"
    
    # 延迟一会儿让远端进程完成退出
    time.sleep(1.0)
    
    # 连接应该失效，后续操作应该抛出网络连接异常
    with pytest.raises((ConnectionError, socket.error)):
        client.get_active_display_ids()
    
    print("\n[退出测试] Daemon 服务端已安全退出。")

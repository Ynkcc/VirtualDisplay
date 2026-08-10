#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Daemon 基础控制 API 的测试用例集。

顺序验证创建、查询、调整大小、启动 Activity、释放以及退出 Daemon。
注：switch_display(207) / inject_input_event_with_display_id(206) 已在
b7aef962 中从服务端移除（多显示器架构下冗余），故不再覆盖。
"""

import time
import pytest
from client.control_client import ControlClient


class TestDaemonApi:
    # 用于在不同测试用例方法间共享临时状态
    initial_count = 0
    did_1 = None
    did_2 = None

    def test_01_get_active_display_ids_initial(self, shared_client: ControlClient):
        """测试 1: 获取初始活跃显示器列表。"""
        resp = shared_client.get_active_display_ids()
        assert resp["type"] == 101, "应该返回活跃显示器响应 (TYPE 101)"
        assert "display_ids" in resp
        
        TestDaemonApi.initial_count = resp.get("count", 0)
        print(f"\n[API测试] 初始显示器数量: {TestDaemonApi.initial_count}, 列表: {resp.get('display_ids')}")

    def test_02_create_virtual_display(self, shared_client: ControlClient):
        """测试 2: 创建虚拟显示器。"""
        # 创建第一个虚拟显示器：1080p
        resp_1 = shared_client.create_virtual_display(
            name="TestDisplay_1080p", width=1080, height=1920, dpi=320,
            flags=0x0001 | 0x0100
        )
        assert resp_1["status_code"] == 0, f"创建 1080p 虚拟显示器失败: {resp_1.get('msg')}"
        assert resp_1["display_id"] > 0, "虚拟显示器 display_id 应该大于 0"
        TestDaemonApi.did_1 = resp_1["display_id"]

        # 创建第二个虚拟显示器：720p
        resp_2 = shared_client.create_virtual_display(
            name="TestDisplay_720p", width=720, height=1280, dpi=240,
            flags=0x0001 | 0x0100
        )
        assert resp_2["status_code"] == 0, f"创建 720p 虚拟显示器失败: {resp_2.get('msg')}"
        assert resp_2["display_id"] > 0
        TestDaemonApi.did_2 = resp_2["display_id"]
        
        print(f"\n[API测试] 已成功创建两个虚拟显示器: {TestDaemonApi.did_1}, {TestDaemonApi.did_2}")

    def test_03_get_active_display_ids_after_creation(self, shared_client: ControlClient):
        """测试 3: 创建后验证活跃显示器列表是否更新。"""
        resp = shared_client.get_active_display_ids()
        assert resp["type"] == 101
        
        expected_count = TestDaemonApi.initial_count + 2
        assert resp["count"] == expected_count, f"显示器数量应该为 {expected_count}，实际为 {resp['count']}"
        assert TestDaemonApi.did_1 in resp["display_ids"], "创建的 did_1 应在活跃列表中"
        assert TestDaemonApi.did_2 in resp["display_ids"], "创建的 did_2 应在活跃列表中"

    def test_04_resize_virtual_display(self, shared_client: ControlClient):
        """测试 4: 调整虚拟显示器的大小。"""
        assert TestDaemonApi.did_1 is not None, "未找到已创建的 did_1"

        # 正常调整 did_1 的大小为 720x1280 dpi=240
        resp_resize1 = shared_client.resize_virtual_display(
            TestDaemonApi.did_1, width=720, height=1280, dpi=240
        )
        assert resp_resize1["status_code"] == 0, f"调整大小失败: {resp_resize1.get('msg')}"

        # 调整 did_1 为另一个分辨率 1280x720 dpi=213
        resp_resize2 = shared_client.resize_virtual_display(
            TestDaemonApi.did_1, width=1280, height=720, dpi=213
        )
        assert resp_resize2["status_code"] == 0, f"第二次调整大小失败: {resp_resize2.get('msg')}"

        # 测试异常情况：调整不存在的显示器 9999
        resp_fail = shared_client.resize_virtual_display(
            9999, width=800, height=600, dpi=160
        )
        assert resp_fail["status_code"] != 0, "调整不存在的显示器应该返回失败"

    def test_05_start_activity(self, shared_client: ControlClient):
        """测试 5: 在不同的显示器上启动 Activity。"""
        assert TestDaemonApi.did_1 is not None

        # 1. 在虚拟显示器 did_1 上启动 Settings
        resp_1 = shared_client.start_activity("com.android.settings", TestDaemonApi.did_1)
        assert resp_1["status_code"] == 0, f"在 did_1 上启动 Settings 失败: {resp_1.get('msg')}"

        # 2. 在主显示器 0 上启动 Settings
        resp_0 = shared_client.start_activity("com.android.settings", 0)
        assert resp_0["status_code"] == 0, f"在主显示器 0 上启动 Settings 失败: {resp_0.get('msg')}"

        # 3. 异常测试：启动不存在的 App，应报错
        resp_fail = shared_client.start_activity("com.nonexistent.app.xyz", 0)
        assert resp_fail["status_code"] != 0, "启动不存在的 APP 应该报错"
        
        # 稍等片刻让 Activity 页面初始化
        time.sleep(0.5)

    def test_06_release_virtual_display(self, shared_client: ControlClient):
        """测试 6: 释放虚拟显示器。

        服务端 release 是引用计数 + 幂等设计：
          - 最后一个 user 释放 → 真正销毁，msg="DESTROYED"
          - 仍有其他 user 引用 → 仅减少引用，msg="RELEASED"
          - 不存在的 displayId → best-effort orphan release，仍返回 status_code=0
            （幂等语义，与 HTTP DELETE 的 204 类似，避免客户端重试造成歧义）
        """
        assert TestDaemonApi.did_1 is not None
        assert TestDaemonApi.did_2 is not None

        # 释放 did_1 和 did_2 —— 本会话是唯一 user，应真正销毁
        for did in [TestDaemonApi.did_1, TestDaemonApi.did_2]:
            resp = shared_client.release_virtual_display(did)
            assert resp["status_code"] == 0, f"释放 display_id={did} 失败: {resp.get('msg')}"
            assert resp["msg"] == "DESTROYED", (
                f"唯一 user 释放后应返回 DESTROYED，实际: {resp.get('msg')}"
            )

        # 校验活跃显示器列表是否恢复
        resp_list = shared_client.get_active_display_ids()
        assert resp_list["count"] == TestDaemonApi.initial_count, "释放后显示器数量应该恢复到初始值"

        # 幂等性测试：释放已销毁 / 不存在的 displayId 应返回成功（best-effort orphan release）
        resp_idem = shared_client.release_virtual_display(TestDaemonApi.did_1)
        assert resp_idem["status_code"] == 0, (
            f"重复释放已销毁的 displayId 应幂等返回成功，实际: {resp_idem}"
        )
        resp_orphan = shared_client.release_virtual_display(9999)
        assert resp_orphan["status_code"] == 0, (
            f"释放不存在的 displayId 应幂等返回成功 (best-effort orphan release)，实际: {resp_orphan}"
        )
        assert resp_orphan["msg"] == "RELEASED", (
            f"orphan release 的 msg 应为 RELEASED，实际: {resp_orphan.get('msg')}"
        )



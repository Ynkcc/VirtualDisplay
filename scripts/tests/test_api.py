#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Daemon 基础控制 API 的测试用例集。

顺序验证创建、查询、调整大小、启动 Activity、切换显示器、事件注入、释放以及退出 Daemon。
"""

import time
import pytest
from client.control_client import ControlClient


class TestDaemonApi:
    # 用于在不同测试用例方法间共享临时状态
    initial_count = 0
    did_1 = None
    did_2 = None

    # MotionEvent 的 Parcel 字节流 (在 Android 16 API 36 上生成的样本)
    motion_event_parcel = bytes.fromhex(
        "01000000010000000100000051e71dce0000000002100000000000002000000000000000000000000"
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        "00000000000000000000000000000000000000000000000000000000000000000000000000803f"
        "00000000000000000000000000000000803f000000000000803f0000803f0000c07f0000c07f0000"
        "803f0000000000000000000000000000803f0000000000bab63306a50000000000000100000000bab6"
        "3306a5000000000000000000f00000c842000048430000803f0000803f00000000"
    )

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

    def test_06_switch_display(self, shared_client: ControlClient):
        """测试 6: 切换显示器。"""
        assert TestDaemonApi.did_1 is not None

        # 切换到虚拟显示器 did_1
        resp_switch_vd = shared_client.switch_display(TestDaemonApi.did_1)
        assert resp_switch_vd["status_code"] == 0, f"切换至 did_1 失败: {resp_switch_vd.get('msg')}"

        # 切换回主显示器 0
        resp_switch_main = shared_client.switch_display(0)
        assert resp_switch_main["status_code"] == 0, f"切换回主显示器 0 失败: {resp_switch_main.get('msg')}"

    def test_07_inject_input_event(self, shared_client: ControlClient):
        """测试 7: 向指定显示器注入 Input 事件。"""
        assert TestDaemonApi.did_1 is not None

        # 1. 向虚拟显示器 did_1 注入 MotionEvent
        resp_vd = shared_client.inject_input_event_with_display_id(
            display_id=TestDaemonApi.did_1, is_key_event=False, parcel_bytes=self.motion_event_parcel
        )
        assert resp_vd["status_code"] == 0, f"向 did_1 注入事件失败: {resp_vd.get('msg')}"

        # 2. 向主显示器 0 注入 MotionEvent
        resp_main = shared_client.inject_input_event_with_display_id(
            display_id=0, is_key_event=False, parcel_bytes=self.motion_event_parcel
        )
        assert resp_main["status_code"] == 0, f"向主显示器 0 注入事件失败: {resp_main.get('msg')}"

    def test_08_release_virtual_display(self, shared_client: ControlClient):
        """测试 8: 释放虚拟显示器。"""
        assert TestDaemonApi.did_1 is not None
        assert TestDaemonApi.did_2 is not None

        # 释放 did_1 和 did_2
        for did in [TestDaemonApi.did_1, TestDaemonApi.did_2]:
            resp = shared_client.release_virtual_display(did)
            assert resp["status_code"] == 0, f"释放 display_id={did} 失败: {resp.get('msg')}"

        # 校验活跃显示器列表是否恢复
        resp_list = shared_client.get_active_display_ids()
        assert resp_list["count"] == TestDaemonApi.initial_count, "释放后显示器数量应该恢复到初始值"

        # 异常测试：释放不存在的显示器 9999
        resp_fail = shared_client.release_virtual_display(9999)
        assert resp_fail["status_code"] != 0, "释放不存在的显示器应该报错"



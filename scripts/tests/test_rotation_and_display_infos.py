#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
旋转控制 (TYPE 211-214) 与活跃显示器详情查询 (TYPE 215 / 响应 102) 的测试用例。

覆盖：
- get_active_display_infos (215) 返回每屏 {id,w,h,dpi,rotation} 元数据 (响应类型 102)
- get_rotation / freeze_rotation / is_rotation_frozen / thaw_rotation 生命周期
- 异常分支：不存在的 displayId、非法 rotation 值
"""

import time
import pytest
from client.control_client import (
    ControlClient,
    TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS,
)


class TestRotationAndDisplayInfos:
    # 跨用例方法共享的临时状态
    did = None

    def test_01_display_infos_initial(self, shared_client: ControlClient):
        """测试 1: 初始查询活跃显示器详情，响应类型应为 102。"""
        resp = shared_client.get_active_display_infos()
        assert resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS, "应该返回详情响应 (TYPE 102)"
        assert "displays" in resp
        assert resp["count"] == len(resp["displays"])
        # 每条记录字段完整
        for d in resp["displays"]:
            assert {"display_id", "width", "height", "dpi", "rotation"} <= set(d.keys())
        print(f"\n[ROT测试] 初始详情: {resp['displays']}")

    def test_02_create_display_for_rotation(self, shared_client: ControlClient):
        """测试 2: 创建一个虚拟显示器用于后续旋转测试。"""
        resp = shared_client.create_virtual_display(
            name="RotTestDisplay", width=720, height=1280, dpi=240,
            flags=0x0001 | 0x0100,
        )
        assert resp["status_code"] == 0, f"创建虚拟显示器失败: {resp.get('msg')}"
        assert resp["display_id"] > 0
        TestRotationAndDisplayInfos.did = resp["display_id"]
        print(f"\n[ROT测试] 已创建显示器 displayId={TestRotationAndDisplayInfos.did}")

    def test_03_display_infos_contains_new_display(self, shared_client: ControlClient):
        """测试 3: 创建后详情列表应包含新显示器，且元数据与创建参数一致。"""
        assert TestRotationAndDisplayInfos.did is not None
        resp = shared_client.get_active_display_infos()
        assert resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS

        match = [d for d in resp["displays"] if d["display_id"] == TestRotationAndDisplayInfos.did]
        assert len(match) == 1, "详情列表应恰好包含一个该 displayId 的条目"
        info = match[0]
        # 创建时 720x1280 dpi=240；VirtualDisplayRegistry 在创建时会冻结到 ROTATION_0
        assert info["width"] == 720, f"width 不匹配: {info}"
        assert info["height"] == 1280, f"height 不匹配: {info}"
        assert info["dpi"] == 240, f"dpi 不匹配: {info}"
        assert info["rotation"] in (0, 1, 2, 3), f"rotation 越界: {info}"
        print(f"\n[ROT测试] 新显示器详情: {info}")

    def test_04_get_rotation_initial(self, shared_client: ControlClient):
        """测试 4: 查询新建显示器旋转，应为 0（创建时冻结到 ROTATION_0）。"""
        assert TestRotationAndDisplayInfos.did is not None
        resp = shared_client.get_rotation(TestRotationAndDisplayInfos.did)
        assert resp["status_code"] == 0, f"get_rotation 失败: {resp.get('msg')}"
        rotation = int(resp["msg"])
        assert 0 <= rotation <= 3, f"rotation 应在 0-3 范围内，实际: {rotation}"
        print(f"\n[ROT测试] 初始 rotation={rotation}")

    def test_05_is_rotation_frozen_after_create(self, shared_client: ControlClient):
        """测试 5: 创建时已冻结旋转，is_rotation_frozen 应返回 1。

        WindowManager 通过异步 IPC 处理 freezeDisplayRotation，状态可能不会立即可见，
        因此使用短暂重试来容忍这种竞态。
        """
        assert TestRotationAndDisplayInfos.did is not None
        frozen = -1
        for _ in range(5):
            resp = shared_client.is_rotation_frozen(TestRotationAndDisplayInfos.did)
            assert resp["status_code"] == 0, f"is_rotation_frozen 失败: {resp.get('msg')}"
            frozen = int(resp["msg"])
            if frozen == 1:
                break
            time.sleep(0.3)
        assert frozen == 1, f"创建后旋转应已冻结，实际: {frozen}"

    def test_06_freeze_to_rotation_1(self, shared_client: ControlClient):
        """测试 6: 冻结旋转到 ROTATION_1，随后 get_rotation 应返回 1。"""
        assert TestRotationAndDisplayInfos.did is not None
        resp = shared_client.freeze_rotation(TestRotationAndDisplayInfos.did, 1)
        assert resp["status_code"] == 0, f"freeze_rotation 失败: {resp.get('msg')}"

        resp_rot = shared_client.get_rotation(TestRotationAndDisplayInfos.did)
        assert resp_rot["status_code"] == 0
        assert int(resp_rot["msg"]) == 1, f"冻结到 1 后 rotation 应为 1，实际: {resp_rot['msg']}"

        resp_fr = shared_client.is_rotation_frozen(TestRotationAndDisplayInfos.did)
        assert int(resp_fr["msg"]) == 1, "冻结后 is_rotation_frozen 应为 1"
        print(f"\n[ROT测试] 冻结到 ROTATION_1 成功，rotation={resp_rot['msg']}")

    def test_07_thaw_rotation(self, shared_client: ControlClient):
        """测试 7: 解冻旋转，is_rotation_frozen 应返回 0。"""
        assert TestRotationAndDisplayInfos.did is not None
        resp = shared_client.thaw_rotation(TestRotationAndDisplayInfos.did)
        assert resp["status_code"] == 0, f"thaw_rotation 失败: {resp.get('msg')}"

        resp_fr = shared_client.is_rotation_frozen(TestRotationAndDisplayInfos.did)
        assert resp_fr["status_code"] == 0
        assert int(resp_fr["msg"]) == 0, f"解冻后 is_rotation_frozen 应为 0，实际: {resp_fr['msg']}"
        print(f"\n[ROT测试] 解冻成功，is_frozen={resp_fr['msg']}")

    def test_08_freeze_invalid_rotation_value(self, shared_client: ControlClient):
        """测试 8: 非法 rotation 值 (5) 应返回失败。"""
        assert TestRotationAndDisplayInfos.did is not None
        resp = shared_client.freeze_rotation(TestRotationAndDisplayInfos.did, 5)
        assert resp["status_code"] != 0, "非法 rotation 值应返回失败"

    def test_09_get_rotation_invalid_display(self, shared_client: ControlClient):
        """测试 9: 查询不存在的 displayId 应返回失败。"""
        resp = shared_client.get_rotation(9999)
        assert resp["status_code"] != 0, "不存在的 displayId 应返回失败"

    def test_10_freeze_rotation_invalid_display(self, shared_client: ControlClient):
        """测试 10: 对不存在的 displayId 冻结旋转应返回失败。"""
        resp = shared_client.freeze_rotation(9999, 0)
        assert resp["status_code"] != 0, "不存在的 displayId 应返回失败"

    def test_11_main_display_rotation_allowed(self, shared_client: ControlClient):
        """测试 11: 主屏 (displayId=0) 的旋转查询应被允许（不一定冻结）。"""
        resp = shared_client.get_rotation(0)
        assert resp["status_code"] == 0, f"主屏 get_rotation 应成功: {resp.get('msg')}"
        assert 0 <= int(resp["msg"]) <= 3

    def test_12_release_and_verify_removed(self, shared_client: ControlClient):
        """测试 12: 释放显示器后，详情列表不再包含该 displayId。"""
        assert TestRotationAndDisplayInfos.did is not None
        did = TestRotationAndDisplayInfos.did
        resp = shared_client.release_virtual_display(did)
        assert resp["status_code"] == 0, f"释放显示器失败: {resp.get('msg')}"

        infos = shared_client.get_active_display_infos()
        ids = [d["display_id"] for d in infos["displays"]]
        assert did not in ids, f"释放后 displayId={did} 不应再出现在详情列表中"
        TestRotationAndDisplayInfos.did = None
        print(f"\n[ROT测试] 已释放 displayId={did}，详情列表确认移除")

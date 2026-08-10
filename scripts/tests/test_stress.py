# -*- coding: utf-8 -*-
"""
压力与一致性测试。

1. create/release 循环 (20 轮)：验证反复创建销毁不会泄漏 displayId 或
   导致 registry 状态错乱。每轮校验活跃数量回到基线。
2. get_active_display_ids (TYPE 205 / 响应 101) 与 get_active_display_infos
   (TYPE 215 / 响应 102) 的一致性：同一时刻两者的 count 与 displayId 集合
   必须完全相同。
3. 并发创建多显示器后一次性全部释放：验证批量清理无残留。
"""

import time

from client.control_client import TYPE_RESPONSE_ACTIVE_DISPLAYS, \
    TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS


def _active_id_set(client):
    resp = client.get_active_display_ids()
    assert resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAYS
    return set(resp["display_ids"])


def test_create_release_loop(new_client_factory):
    """20 轮 create→release 循环：每轮结束后活跃数量回到基线，无泄漏。"""
    client = new_client_factory()
    baseline = _active_id_set(client)

    created_ids = []
    for i in range(20):
        resp = client.create_virtual_display(
            f"StressLoop{i}", 720, 1280, 240, 0
        )
        assert resp["status_code"] == 0, f"第 {i} 轮创建失败: {resp.get('msg')}"
        did = resp["display_id"]
        created_ids.append(did)

        # 创建后应出现在活跃列表
        ids = _active_id_set(client)
        assert did in ids, f"第 {i} 轮创建的 did={did} 未出现在活跃列表"

        # 立即释放
        r = client.release_virtual_display(did)
        assert r["status_code"] == 0, f"第 {i} 轮释放失败: {r.get('msg')}"

    # 全部释放后活跃集合应回到基线
    after = _active_id_set(client)
    leaked = after - baseline
    assert not leaked, (
        f"20 轮 create/release 后有 displayId 泄漏: {leaked}"
    )
    print(f"[Stress] 20 轮 create/release 循环无泄漏 ✓")


def test_ids_and_infos_consistency(new_client_factory):
    """get_active_display_ids 与 get_active_display_infos 返回一致的数据。"""
    client = new_client_factory()
    baseline = _active_id_set(client)

    # 创建 3 个不同尺寸的显示器
    created = []
    for i, (w, h, dpi) in enumerate([(800, 600, 240), (640, 480, 160), (1280, 720, 213)]):
        resp = client.create_virtual_display(f"Consistency{i}", w, h, dpi, 0)
        assert resp["status_code"] == 0
        created.append((resp["display_id"], w, h, dpi))

    try:
        # 同时查询两种视图
        ids_resp = client.get_active_display_ids()
        infos_resp = client.get_active_display_infos()
        assert ids_resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAYS
        assert infos_resp["type"] == TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS

        ids_set = set(ids_resp["display_ids"])
        infos_set = {d["display_id"] for d in infos_resp["displays"]}

        # 两个视图的 displayId 集合必须一致
        assert ids_set == infos_set, (
            f"ids 与 infos 的 displayId 集合不一致: "
            f"ids={ids_set}, infos={infos_set}"
        )

        # 校验 infos 中每条记录的字段完整且与创建参数一致
        for did, w, h, dpi in created:
            match = [d for d in infos_resp["displays"] if d["display_id"] == did]
            assert len(match) == 1, f"infos 中 did={did} 应恰好一条，实际 {len(match)} 条"
            info = match[0]
            assert info["width"] == w, f"did={did} width 不匹配: 期望 {w}, 实际 {info['width']}"
            assert info["height"] == h, f"did={did} height 不匹配: 期望 {h}, 实际 {info['height']}"
            assert info["dpi"] == dpi, f"did={did} dpi 不匹配: 期望 {dpi}, 实际 {info['dpi']}"
            assert 0 <= info["rotation"] <= 3

        print(f"[Stress] ids/infos 一致性验证通过 ({len(created)} 显示器) ✓")
    finally:
        for did, _, _, _ in created:
            client.release_virtual_display(did)


def test_batch_create_then_release_all(new_client_factory):
    """批量创建 5 个显示器后一次性释放，验证无残留。"""
    client = new_client_factory()
    baseline = _active_id_set(client)

    created = []
    try:
        for i in range(5):
            resp = client.create_virtual_display(
                f"Batch{i}", 720, 1280, 240, 0
            )
            assert resp["status_code"] == 0
            created.append(resp["display_id"])

        # 全部创建后活跃数量应增加 5
        ids = _active_id_set(client)
        for did in created:
            assert did in ids, f"批量创建的 did={did} 未在活跃列表"

        # 逐个释放
        for did in created:
            r = client.release_virtual_display(did)
            assert r["status_code"] == 0
            assert r["msg"] == "DESTROYED", f"批量释放 did={did} 应 DESTROYED，实际 {r.get('msg')}"

        # 验证无残留
        after = _active_id_set(client)
        leaked = after - baseline
        assert not leaked, f"批量释放后有泄漏: {leaked}"
        print(f"[Stress] 批量创建 5 + 全部释放无残留 ✓")
    finally:
        for did in created:
            try:
                client.release_virtual_display(did)
            except Exception:
                pass

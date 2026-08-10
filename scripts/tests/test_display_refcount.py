# -*- coding: utf-8 -*-
"""
虚拟显示器引用计数 (refcount) 语义测试。

验证 b7aef962 引入的多 user 引用计数释放机制：
  - createVirtualDisplay 时创建者 session 自动 acquire 一个 user 引用
  - 其他 session 绑定 (ROLE_VIDEO, displayId) socket 时再 acquire 一个 user 引用
  - release 只减少一个 user 引用；仍有 user 时返回 msg="RELEASED"，不销毁
  - 最后一个 user 释放 (关闭 video socket / release) 才真正销毁

这是「跨 session 全局虚拟显示可见性」的底层保证：A 创建的显示器，B 在推流时
A release 不会把显示器从 B 脚下抽走。
"""

import time

from client.video_client import VideoClient


def _wait_display_gone(client, did, timeout=3.0):
    """轮询 get_active_display_ids，确认 did 已被销毁。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        resp = client.get_active_display_ids()
        if did not in resp.get("display_ids", []):
            return True
        time.sleep(0.2)
    return False


def test_release_with_active_streamer_keeps_display(
    new_client_factory, daemon_config
):
    """A 创建 + B 推流：A release 后显示器仍存活 (msg=RELEASED)，B 关闭后才销毁。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]

    owner = new_client_factory()
    streamer = new_client_factory()

    # 1. owner 创建虚拟显示器 → owner 成为第一个 user
    resp = owner.create_virtual_display("RefcountDisp", 720, 1280, 240, 0)
    assert resp["status_code"] == 0, f"创建显示器失败: {resp.get('msg')}"
    did = resp["display_id"]
    owner.start_activity("com.android.settings", did)
    time.sleep(0.8)

    # 2. streamer 绑定 video socket → acquire 第二个 user 引用 (自动推流)
    vc = VideoClient(host, port, streamer.session_id, did, log_prefix="Refcount")
    vc.connect()
    time.sleep(0.5)  # 让 acquire 生效

    try:
        # 3. owner release —— streamer 仍在推流，显示器不应被销毁
        r = owner.release_virtual_display(did)
        assert r["status_code"] == 0, f"owner release 失败: {r.get('msg')}"
        assert r["msg"] == "RELEASED", (
            f"仍有 streamer 引用时应返回 RELEASED 而非 DESTROYED，实际: {r.get('msg')}"
        )

        # 4. 验证显示器仍存活 (streamer 持有引用)
        ids = owner.get_active_display_ids()
        assert did in ids["display_ids"], (
            f"streamer 仍在推流，displayId={did} 不应被销毁，但已从活跃列表消失"
        )

        # 5. streamer 关闭 video socket → 释放最后一个 user 引用 → 销毁
        vc.close()
        assert _wait_display_gone(owner, did), (
            f"最后一个 user (streamer) 关闭后 displayId={did} 应被销毁，"
            "但 get_active_display_ids 仍包含它"
        )
        print(f"[Refcount] owner release=RELEASED (streamer 持有) → streamer 关闭后销毁 ✓")
    finally:
        # 兜底清理：若断言失败导致 vc 未关闭，确保释放
        try:
            vc.close()
        except Exception:
            pass
        # 尝试释放（若仍存在）；release 是幂等的，不会报错
        owner.release_virtual_display(did)


def test_double_release_same_session(new_client_factory):
    """同一 session 连续 release 两次：第一次销毁 (DESTROYED)，第二次幂等 (RELEASED)。"""
    client = new_client_factory()

    resp = client.create_virtual_display("DoubleRelease", 720, 1280, 240, 0)
    assert resp["status_code"] == 0
    did = resp["display_id"]

    # 第一次 release：唯一 user → 销毁
    r1 = client.release_virtual_display(did)
    assert r1["status_code"] == 0
    assert r1["msg"] == "DESTROYED", f"唯一 user 释放应 DESTROYED，实际: {r1.get('msg')}"

    # 第二次 release：已销毁 → 幂等 orphan release
    r2 = client.release_virtual_display(did)
    assert r2["status_code"] == 0, "幂等 release 应返回成功"
    assert r2["msg"] == "RELEASED", f"orphan release 应返回 RELEASED，实际: {r2.get('msg')}"
    print(f"[Refcount] 双重 release: 第一次=DESTROYED，第二次=RELEASED (幂等) ✓")


def test_streamer_release_does_not_affect_owner(new_client_factory, daemon_config):
    """B 推流期间关闭 video socket：不应影响 owner 持有的显示器 (owner 仍可 resize)。"""
    host = daemon_config["bind"]
    port = daemon_config["port"]

    owner = new_client_factory()
    streamer = new_client_factory()

    resp = owner.create_virtual_display("OwnerProtect", 800, 600, 240, 0)
    assert resp["status_code"] == 0
    did = resp["display_id"]
    owner.start_activity("com.android.settings", did)
    time.sleep(0.8)

    vc = VideoClient(host, port, streamer.session_id, did, log_prefix="OwnerProtect")
    vc.connect()
    time.sleep(0.5)

    try:
        # streamer 关闭 video socket —— 释放其引用，但 owner 仍持有
        vc.close()
        time.sleep(0.3)

        # owner 仍能操作该显示器 (resize 成功说明未被销毁)
        r = owner.resize_virtual_display(did, 640, 480, 160)
        assert r["status_code"] == 0, (
            f"streamer 关闭后 owner 应仍能操作显示器，resize 失败: {r.get('msg')}"
        )
        ids = owner.get_active_display_ids()
        assert did in ids["display_ids"], "owner 持有的显示器不应被销毁"
        print(f"[Refcount] streamer 关闭后 owner 仍可 resize ✓")
    finally:
        owner.release_virtual_display(did)

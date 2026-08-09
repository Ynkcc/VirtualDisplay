#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pytest 全局配置及夹具定义。
"""

import os
import sys
import time
import subprocess
import pytest

# 将 scripts 目录添加到 sys.path 中，使得可以用 client.control_client 导入
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from client.control_client import ControlClient


def pytest_addoption(parser):
    """注册自定义命令行选项。"""
    parser.addoption(
        "--port", action="store", default="27183", help="daemon 监听的 TCP 端口"
    )
    parser.addoption(
        "--bind", action="store", default="127.0.0.1", help="daemon 绑定的 IP 地址"
    )
    parser.addoption(
        "--skip-build", action="store_true", default=False, help="跳过 gradle 编译服务端的步骤"
    )


@pytest.fixture(scope="session")
def daemon_config(request):
    """返回服务端配置参数。"""
    return {
        "port": int(request.config.getoption("--port")),
        "bind": request.config.getoption("--bind"),
        "skip_build": request.config.getoption("--skip-build"),
    }


@pytest.fixture(scope="session")
def device_env(daemon_config):
    """
    自动化环境部署。
    1. 确认 adb 连接
    2. (可选) 编译并打包 server APK
    3. 清理残留 server 进程
    4. 推送 APK，启动守护进程，并进行 adb forward
    5. 验证是否监听成功
    """
    port = daemon_config["port"]
    bind_address = daemon_config["bind"]
    skip_build = daemon_config["skip_build"]

    # 1. 确认 adb 环境
    res = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
    if res.returncode != 0:
        pytest.exit("[ERROR] 没有检测到已连接的 Android 设备，请插入设备并启用 ADB")

    project_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    server_dir = os.path.join(project_root, "scrcpy", "server")
    apk_path = os.path.join(server_dir, "build", "outputs", "apk", "release", "server-release-unsigned.apk")
    remote_apk = "/data/local/tmp/scrcpy-server.apk"

    # 2. 编译 APK
    if not skip_build:
        print("\n[conftest] 正在通过 Gradle 构建 scrcpy server...")
        env = os.environ.copy()
        # 寻找合适的 JAVA_HOME
        if not env.get("JAVA_HOME"):
            if os.path.exists("/usr/lib/jvm/java-21-openjdk"):
                env["JAVA_HOME"] = "/usr/lib/jvm/java-21-openjdk"
                env["PATH"] = f"{env['JAVA_HOME']}/bin:{env['PATH']}"
        
        # 显式使用项目根目录下的 gradlew
        gradlew = os.path.join(project_root, "gradlew")
        res_build = subprocess.run(
            [gradlew, "-p", server_dir, "assembleRelease"],
            cwd=project_root,
            env=env,
            capture_output=True,
            text=True
        )
        if res_build.returncode != 0:
            print(res_build.stdout)
            print(res_build.stderr)
            pytest.exit(f"[ERROR] Gradle 编译失败: 状态码 {res_build.returncode}")
        print("[conftest] Gradle 编译完成。")

    if not os.path.exists(apk_path):
        pytest.exit(f"[ERROR] 未找到编译好的 APK 文件，路径: {apk_path}")

    # 3. 杀掉已有 server 进程
    print("[conftest] 正在清理已有的 scrcpy server 进程...")
    subprocess.run(["adb", "shell", "su -c 'pkill -f com.genymobile.scrcpy.Server'"], capture_output=True)
    time.sleep(1)

    # 4. 推送并运行 server
    print(f"[conftest] 正在推送 APK 到 {remote_apk}...")
    res_push = subprocess.run(["adb", "push", apk_path, remote_apk], capture_output=True, text=True)
    if res_push.returncode != 0:
        pytest.exit(f"[ERROR] adb push APK 失败: {res_push.stderr}")

    print(f"[conftest] 正在启动 scrcpy server 守护进程 (端口: {port})...")
    # nohup 启动
    cmd = (
        f"su -c 'export CLASSPATH={remote_apk}; "
        f"nohup app_process / com.genymobile.scrcpy.Server 4.1 "
        f"tunnel_forward=true audio=false send_device_meta=false send_dummy_byte=false "
        f"send_stream_meta=false send_frame_meta=true cleanup=false "
        f"--daemon --port={port} --bind_address={bind_address} "
        f">/data/local/tmp/scrcpy-server.log 2>&1 &'"
    )
    subprocess.run(["adb", "shell", cmd], capture_output=True)
    
    # 睡眠 2 秒等待启动
    time.sleep(2)

    # 建立端口转发
    subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], capture_output=True)

    # 5. 校验连通性 (检查端口是否已经被监听)
    check_cmd = f"su -c 'netstat -tlnp 2>/dev/null | grep {port} || ss -tlnp | grep {port}'"
    res_check = subprocess.run(["adb", "shell", check_cmd], capture_output=True, text=True)
    if str(port) not in res_check.stdout:
        print("[WARN] 从服务端未检测到端口监听，可查看日志：adb shell su -c 'cat /data/local/tmp/scrcpy-server.log'")
    
    yield
    
    # 在 Session 结束时，清理端口转发 (不主动杀死 daemon 服务端，除非退出用例显式退出了，或者可以在这里执行清理)
    # 为了防止之后其他测试出问题，可在结束后关闭转发
    subprocess.run(["adb", "forward", "--remove", f"tcp:{port}"], capture_output=True)


@pytest.fixture(scope="session")
def shared_client(device_env, daemon_config):
    """
    提供全局共享的 ControlClient 实例。
    用于顺序 API 校验，在一个 TCP 连接生命周期内执行所有操作，避免 Looper 重启失败。
    """
    client = ControlClient(host=daemon_config["bind"], port=daemon_config["port"])
    client.connect()
    yield client
    client.close()


@pytest.fixture
def new_client_factory(device_env, daemon_config):
    """
    工厂 fixture。用来创建全新的、隔离的 ControlClient 连接。
    适合测试多客户端并发、独立操作、或者退出进程等测试用例。
    """
    clients = []

    def _create() -> ControlClient:
        client = ControlClient(host=daemon_config["bind"], port=daemon_config["port"])
        client.connect()
        clients.append(client)
        return client

    yield _create

    # 清理所有在此测试期间创建的客户端连接
    for client in clients:
        try:
            client.close()
        except Exception:
            pass

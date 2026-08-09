#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动化测试一键启动入口。
"""

import sys
import os
import argparse

# 确保能导入 pytest
try:
    import pytest
except ImportError:
    print("[ERROR] 未检测到 pytest。请先安装依赖：pip install -r scripts/requirements.txt")
    sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="scrcpy Daemon 统一自动化测试启动器")
    parser.add_argument("--port", default="27183", help="daemon 监听的 TCP 端口 (默认: 27183)")
    parser.add_argument("--bind", default="127.0.0.1", help="daemon 绑定的 IP 地址 (默认: 127.0.0.1)")
    parser.add_argument("--skip-build", action="store_true", help="跳过 gradle 编译服务端的步骤")
    parser.add_argument("--html-report", action="store_true", help="生成 HTML 格式测试报告 (需要 pytest-html 插件)")
    
    args, unknown = parser.parse_known_args()

    # 切换至 scripts 目录下，保证测试用例内部寻址一致
    scripts_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(scripts_dir)

    # 构造 pytest 参数
    pytest_args = [
        "tests/",
        "-v",
        "-s",
        "--port", args.port,
        "--bind", args.bind
    ]
    
    if args.skip_build:
        pytest_args.append("--skip-build")

    # 尝试判断是否安装了 pytest-html，以生成 HTML 报告
    if args.html_report:
        try:
            import pytest_html
            report_path = os.path.join(scripts_dir, "report.html")
            pytest_args.extend([f"--html={report_path}", "--self-contained-html"])
            print(f"[INFO] 启用了 HTML 报告生成，报告将保存至: {report_path}")
        except ImportError:
            print("[WARN] 未检测到 pytest-html 插件，忽略 --html-report 参数。可使用 pip install pytest-html 安装")

    # 如果有其他未识别参数，透传给 pytest (例如指定特定用例 -k "xxx")
    if unknown:
        pytest_args.extend(unknown)

    print(f"[INFO] 启动 pytest，参数: {pytest_args}")
    
    # 启动 pytest
    ret_code = pytest.main(pytest_args)
    sys.exit(ret_code)


if __name__ == "__main__":
    main()

# 屏幕分身（多虚拟显示控制）

> **[已废弃]** 本分支（`deprecated/daemon-architecture`）是一条已被放弃的演进方向，仅作存档保留，不再维护。主线已回退到 v1.0.37 基线（`main`）重新演进。

## 废弃原因

架构过于臃肿，难以维护。

## 本分支架构说明

该分支采用客户端-守护进程分离架构，主要组成：

- **`app/`（Android 客户端，Kotlin + Compose）**
  - `data/`：分层配置（`local`/`remote`/`process`/`repository`）、按职责拆分的设置单元；
  - `protocol/` + `net/` + `rpc/`：与守护进程通信的自定义 TCP 协议、连接管理与重连逻辑；
  - `process/`：守护进程的拉起（Shizuku/Root）、权限预检与生命周期管理；
  - `decoder/` + `video/`：视频流解码与渲染（含旋转/缩放处理）；
  - `ui/`：Compose 界面（虚拟屏幕管理、应用选择、远程节点编辑与操控预览）。
- **`daemon/`（子模块，VirtualDisplayDaemon）**：基于 scrcpy 补丁序列构建的 native 守护进程，负责虚拟显示器的创建/销毁、输入注入与视频流转发，常驻后台并监听端口，支持远程节点接入。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
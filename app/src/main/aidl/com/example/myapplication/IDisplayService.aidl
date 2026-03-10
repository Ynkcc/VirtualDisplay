// IDisplayService.aidl
package com.example.myapplication;

import android.view.Surface;
import android.view.InputEvent;
import android.content.Intent;
import android.os.Bundle;

interface IDisplayService {
    /**
     * 为已有的虚拟显示器设置 Surface。
     * @param displayId 目标显示器 ID
     * @param surface 新的 Surface（可为 null 以清除）
     */
    void setVirtualDisplaySurface(int displayId, in Surface surface);

    /**
     * 在特权进程中通过反射 DisplayManager 构造函数创建 VirtualDisplay。
     * @param name    显示器名称
     * @param width   宽度（像素）
     * @param height  高度（像素）
     * @param dpi     DPI
     * @param surface 输出 Surface（跨进程传递）
     * @param flags   DisplayManager flags
     * @return 创建的 displayId，失败返回 -1
     */
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface, int flags);

    /**
     * 释放指定 displayId 的虚拟显示器。
     */
    void releaseVirtualDisplay(int displayId);

    /**
     * 注入输入事件。
     */
    boolean injectInputEvent(in InputEvent event, int mode);

    /**
     * 在特权进程中启动 Activity。
     */
    int startActivity(in Intent intent, in Bundle options);

    /**
     * 销毁服务自身（可选，用于清理）。
     */
    void destroy();

    /**
     * 获取当前 UserService 实例管理的所有 displayId 列表。
     * 用于区分"孤儿显示器"（系统中存在但本 service 实例不持有句柄）。
     */
    int[] getActiveDisplayIds();
}

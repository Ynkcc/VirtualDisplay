// IDisplayService.aidl
package com.ynk.virtualdisplay;

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
     * 在特权进程内部构造并注入 KeyEvent，并指定目标 displayId。
     * 复用 scrcpy Device.injectKeyEvent 逻辑：setDisplayId 在特权进程中调用，保证生效。
     * @param action      KeyEvent.ACTION_DOWN / ACTION_UP
     * @param keyCode     目标键码
     * @param repeat      重复次数
     * @param metaState   Meta 键状态
     * @param displayId   目标屏幕 ID
     * @param mode        注入模式（0=ASYNC）
     */
    boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int mode);

    /**
     * 在特权进程内对任意 InputEvent（含 MotionEvent）设置 displayId 后注入。
     * 复用 scrcpy Device.injectEvent 逻辑：setDisplayId 在特权侧调用，避免 app 进程
     * 反射失败导致触摸事件路由到默认屏幕。
     * @param event     已构造好坐标/source 的 InputEvent（displayId 留给特权侧设置）
     * @param displayId 目标屏幕 ID
     * @param mode      注入模式（0=ASYNC）
     */
    boolean injectInputEventWithDisplayId(in InputEvent event, int displayId, int mode);

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



    /**
     * 重新调整指定虚拟显示器的物理尺寸和 DPI。
     */
    void resizeVirtualDisplay(int displayId, int width, int height, int dpi);
}

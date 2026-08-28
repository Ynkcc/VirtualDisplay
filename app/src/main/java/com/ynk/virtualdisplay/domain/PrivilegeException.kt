package com.ynk.virtualdisplay.domain

/**
 * 特权未就绪/未授权异常。
 *
 * 由 [DisplayInteractor.startDaemon] 等在启动前做权限预检时抛出，
 * [message] 为面向用户的中文提示，经 Result.failure 传播后由
 * ViewModel 直接展示。
 */
class PrivilegeException(message: String) : Exception(message)

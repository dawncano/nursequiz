package com.google.android.accessibility.selecttospeak

import com.quizhelper.dumptool.DumpAccessibilityService

/**
 * 服务改名（伪装）外壳——学习自竞品「小助手」的手法。
 *
 * 原理：
 *  - 目标 App 检测"有没有挂自动答题外挂"，最省事的做法是读系统的
 *    `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，里面是一串已启用无障碍服务的
 *    **组件名**（形如 `包名/服务类全名`），然后按关键字（autojs、dump、helper、刷题…）匹配。
 *  - 所以只要把"真正注册到系统里的那个服务类"放进一个看起来人畜无害、像系统自带的
 *    包名 + 类名里，组件名就不再含敏感词，按名检测就扫不到。
 *  - 这里照搬竞品选的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`
 *    ——这正是 Android/TalkBack 套件里"选择朗读(Select to Speak)"那个真实存在的系统服务类名，
 *    伪装度最高（检测方即便人工看列表，也会以为是 Google 自带的朗读服务）。
 *
 * 实现：本类**不写任何逻辑**，只是 [DumpAccessibilityService] 的空壳子类。所有答题/截图/
 * 节点逻辑仍留在父类原处（`com.quizhelper.dumptool` 包），改动面最小、零风险。
 * 系统真正实例化、回调的是本类；父类的生命周期方法全部被继承调用，行为完全不变。
 *
 * AndroidManifest 里 `<service android:name>` 指向本类，于是系统/`dumpsys`/目标 App 读到的
 * 组件名变成 `com.quizhelper.dumptool/com.google.android.accessibility.selecttospeak.SelectToSpeakService`。
 *
 * 留意（诚实说明局限）：组件名里的**前半段 `com.quizhelper.dumptool`（applicationId）依旧暴露**。
 * 检测方若连包名一起匹配，"quizhelper" 仍会露馅。要彻底隐身得连 applicationId 也改成无意义串
 * （像竞品的 `com.xx.xzsgf`）——那是改整个 App 包名的活，不在本次"服务改名"范围内。
 */
class SelectToSpeakService : DumpAccessibilityService()

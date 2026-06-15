# 护理刷题 · 屏幕抓取工具（第一步）

这是整个项目的**第一步**：一个最小化的无障碍工具，用来导出"护理助手"答题界面的控件结构。
它**只读屏，不自动点击**，目的是先确认目标 App 能不能被无障碍读取，并拿到界面结构来写后续的自动答题逻辑。

## 编译 / 安装

1. 用 **Android Studio** 打开本目录（`File → Open`，选到含 `settings.gradle.kts` 的这层）。
2. 等待 Gradle 同步。第一次会自动下载 Gradle 8.7 和依赖；如果提示缺 `gradle-wrapper.jar`，
   让 Studio 同步即可自动补上（或命令行执行 `gradle wrapper`）。
3. 手机用数据线连电脑，开启 **开发者选项 → USB 调试**。
4. 点 Android Studio 的 **Run ▶**，把 App 装到手机上。

环境要求：Android Studio 自带的 JDK 17、Android SDK Platform 34。NDK / Qt 都用不上。

## 使用

1. 打开本 App，点 **"去开启无障碍服务"**。
2. 在系统设置里找到 **"护理刷题-抓屏工具"** 并打开（系统会提示授权，确认即可）。
3. 回到 App，状态应显示 ✅ 已开启，屏幕上出现一个 **"抓屏"** 悬浮按钮（可拖动）。
4. 打开护理助手，进入答题界面，点 **"抓屏"**。
5. 当前屏幕的控件结构会：
   - **复制到剪贴板** —— 直接粘贴发给开发者最方便；
   - 同时存到文件：`Android/data/com.quizhelper.dumptool/files/dumps/dump_*.txt`。

### 需要分别抓 4 个界面
- 正常答题中（4~5 个选项都在，还没选）
- 答错后（出现"正确答案"提示 + "下一题"按钮）— ⭐ 最关键
- 一组做完（出现"开始答题"按钮）
- 带 E 选项的题

## 如果抓不到内容
点"抓屏"后若提示"读不到屏幕内容"，说明护理助手可能屏蔽了无障碍读取（或题目是图片）。
把这个结果告诉开发者，我们再换方案（截图 + OCR + 坐标点击）。

## 工程结构
```
app/src/main/
├── AndroidManifest.xml
├── java/com/quizhelper/dumptool/
│   ├── MainActivity.kt              引导开启服务的界面
│   └── DumpAccessibilityService.kt  无障碍服务 + 悬浮按钮 + 抓屏
└── res/
    ├── layout/activity_main.xml
    ├── xml/accessibility_service_config.xml
    ├── values/{strings,themes}.xml
    └── drawable/ic_launcher.xml
```

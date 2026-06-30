package com.quizhelper.dumptool

/**
 * 屏幕模型：答题主循环（act）消费的统一数据结构，由 [NodeParser.toModel] 从无障碍节点树产出。
 * （历史上还有一条截图+OCR 的 ScreenParser 路线，2026-06-29 已随 OCR 代码一并删除——
 *  控件树直读零识别错误，OCR 整摊不再需要。）
 */

data class XY(val x: Int, val y: Int)

enum class ScreenKind { QUESTION, FEEDBACK, TASK_DETAIL, SUBMIT_DIALOG, UNKNOWN }

data class ScreenModel(
    val kind: ScreenKind,
    val questionText: String = "",
    val isMulti: Boolean = false,
    val options: List<XY> = emptyList(),     // 按 A,B,C,D,E 顺序的可点坐标
    val confirm: XY? = null,                 // "确定"
    val nextBtn: XY? = null,                 // 反馈区"下一题"或"提交"按钮
    val isSubmit: Boolean = false,           // 反馈区动作按钮是"提交"(本轮最后一题)而非"下一题"
    val startBtn: XY? = null,                // "开始答题"
    val dialogConfirm: XY? = null,           // 提交弹窗"确定"
    val dismissBtn: XY? = null,              // UNKNOWN 画面里疑似可关闭的按钮(确定/关闭/我知道了)，用于自救
    val correctIdx: List<Int> = emptyList(), // 正确答案的选项下标(0=A)
    val yourIdx: List<Int> = emptyList(),    // FEEDBACK页"你的答案"实际选项下标(0=A)
    val title: String = "",
    val project: String = "",
    val optionTexts: List<String> = emptyList() // 选项文字(与 options 下标对应)，存/点按文字用
)

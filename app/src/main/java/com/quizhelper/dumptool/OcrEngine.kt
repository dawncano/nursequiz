package com.quizhelper.dumptool

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 离线中文 OCR 封装。识别结果里每行/每块都带屏幕坐标框，可直接拿来定位点击。 */
object OcrEngine {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    fun recognize(bitmap: Bitmap, onResult: (Text?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }
}

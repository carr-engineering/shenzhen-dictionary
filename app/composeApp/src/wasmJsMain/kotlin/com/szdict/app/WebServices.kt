package com.szdict.app

import com.szdict.app.feature.handwriting.HandwritingPlatform
import com.szdict.app.feature.handwriting.WebHandwritingPlatform
import com.szdict.app.feature.ocr.OcrPlatform
import com.szdict.app.feature.ocr.WebOcrPlatform
import kotlinx.coroutines.CoroutineScope

/** Web services handed to feature code through [AppEnv.services] (recognisers and the OCR image inputs). */
internal fun webServices(scope: CoroutineScope): Map<String, Any> = mapOf(
    OcrPlatform.KEY to WebOcrPlatform(scope),
    HandwritingPlatform.KEY to WebHandwritingPlatform(scope),
)

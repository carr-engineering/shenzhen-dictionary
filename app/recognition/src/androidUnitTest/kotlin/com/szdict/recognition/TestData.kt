package com.szdict.recognition

import java.io.File
import java.util.zip.GZIPInputStream

internal object TestData {
    val golden: File get() = File(System.getProperty("recognition.goldenDir"))
    val testdata: File get() = File(golden, "../../../androidInstrumentedTest/assets/testdata")

    fun gunzip(name: String): ByteArray = GZIPInputStream(File(golden, name).inputStream()).use { it.readBytes() }

    fun inkGolden(): InkGolden = InkGolden.parse(File(testdata, "ink-fixtures.txt").readText())
}

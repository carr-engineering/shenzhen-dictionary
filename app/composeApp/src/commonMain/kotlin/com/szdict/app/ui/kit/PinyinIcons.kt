package com.szdict.app.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Pinyin shown (readings on): the pinyin glyph. 24x24, filled, tinted by the Icon. */
val PinyinShownIcon: ImageVector by lazy {
    pinyinIcon(
        "PinyinShown",
        "M14,12h2V9H14v3ZM4.5,20L4,18H5.5q0.2,0 0.35-0.15T6,17.5V14.38q-0.42,0.17-0.82,0.34T4.4,15L4,13.05q0.48-0.1 0.99-0.28T6,12.4V9H4.5V7H6V4H8V7H9.5V9H8v2.4q0.38-0.22 0.72-0.45Q9.08,10.73 9.4,10.5v2q-0.3,0.25-0.65,0.49Q8.4,13.23 8,13.43V18.5q0,0.57-0.46,1.04T6.5,20h-2Zm5.55,0l-1.1-1.65q1.05-0.7 1.81-1.88T11.83,14H10V12h2V9H10.5V7h9V9H18v3h2v2H18v6H16V14H13.85q-0.35,1.78-1.36,3.41T10.05,20Zm7.2-12.25L15.53,7q0.4-0.68 0.89-1.48T17.2,4l1.85,0.67Q18.68,5.4 18.18,6.22T17.25,7.75Zm-4.72-0.2Q12.1,6.93 11.55,6.18T10.5,4.85L12.3,4q0.45,0.57 0.96,1.3q0.51,0.72 0.94,1.32L12.53,7.55Z",
    )
}

/** Pinyin hidden (readings off): the pinyin glyph struck through. */
val PinyinHiddenIcon: ImageVector by lazy {
    pinyinIcon(
        "PinyinHidden",
        "M4.5 20 4 18H5.5Q5.7 18 5.85 17.85Q6 17.7 6 17.5V14.38Q5.58 14.55 5.18 14.72Q4.78 14.89 4.4 15L4 13.05Q4.48 12.95 4.99 12.77Q5.5 12.59 6 12.4V9H4.5V7.31L1.4 4.2L2.8 2.8L21.2 21.2L19.75 22.6L17.16 20H16V18.84L13.21 16.04Q12.91 16.74 12.49 17.41Q11.48 19.04 10.05 20L8.95 18.35Q10 17.65 10.76 16.47Q11.37 15.53 11.69 14.51L11.17 14H10V12.82L9.4 12.22V12.5Q9.1 12.75 8.75 12.99Q8.4 13.23 8 13.43V18.5Q8 19.07 7.54 19.54Q7.08 20.01 6.5 20ZM18 15.15 14.85 12H16V9H14V11.15L12 9.15V9H11.85L10.5 7.65V7H12.14Q11.87 6.61 11.55 6.18Q11 5.43 10.5 4.85L12.3 4Q12.75 4.57 13.26 5.3Q13.77 6.02 14.2 6.62L13.52 7H15.53Q15.93 6.32 16.42 5.52Q16.91 4.72 17.2 4L19.05 4.67Q18.68 5.4 18.18 6.22Q17.93 6.62 17.71 7H19.5V9H18V12H20V14H18ZM8 5.15 6.85 4H8Z",
    )
}

private fun pinyinIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

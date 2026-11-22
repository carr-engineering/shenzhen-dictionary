package com.szdict.app.feature.settings

import com.szdict.app.AppPlatform
import com.szdict.app.i18n.AppStrings
import com.szdict.core.api.UiLang
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FeedbackEmailTest {
    @Test
    fun draftIncludesPlatformAndVersionAndEscapesQueryValues() {
        assertEquals(
            "mailto:marcus@carr.eng.br?subject=Shenzhen%20Dictionary%20feedback" +
                "&body=Hi%20Marcus%21%0D%0AI%27m%20using%20Shenzhen%20Dictionary%20%28Android%20app%2C%20version%201%2B2%26x%29" +
                "%2C%20and%20I%27d%20like%20to%20share%20something%20with%20you.",
            feedbackEmailUrl(AppStrings(UiLang.EN), AppPlatform.ANDROID, "1+2&x"),
        )
    }

    @Test
    fun chineseDraftsUseLocalizedAppNamesAndGreetings() {
        val simplified = feedbackEmailUrl(AppStrings(UiLang.ZH_HANS), AppPlatform.WEB, "1")
        assertContains(simplified, "subject=%E6%B7%B1%E5%9C%B3%E8%AF%8D%E5%85%B8")
        assertContains(simplified, "&body=%E9%A9%AC%E4%B8%80%E5%B7%9D")
        val traditional = feedbackEmailUrl(AppStrings(UiLang.ZH_HANT), AppPlatform.IOS, "1")
        assertContains(traditional, "subject=%E6%B7%B1%E5%9C%B3%E8%A9%9E%E5%85%B8")
        assertContains(traditional, "&body=%E9%A6%AC%E4%B8%80%E5%B7%9D")
    }
}

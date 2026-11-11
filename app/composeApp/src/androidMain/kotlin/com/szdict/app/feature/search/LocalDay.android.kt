package com.szdict.app.feature.search

import java.time.LocalDate

internal actual fun localEpochDay(): Long = LocalDate.now().toEpochDay()

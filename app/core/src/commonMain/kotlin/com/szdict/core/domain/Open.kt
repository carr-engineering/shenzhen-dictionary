package com.szdict.core.domain

import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary

internal suspend fun openDictionary(datasetDir: String, mode: DictMode): Dictionary =
    throw DictException(DictError.DatasetUnavailable("$datasetDir/${mode.datasetName}.db", "engine not built yet"))

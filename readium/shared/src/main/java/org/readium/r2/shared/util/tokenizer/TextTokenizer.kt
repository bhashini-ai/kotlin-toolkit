/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.util.tokenizer

import android.icu.text.BreakIterator
import android.os.Build
import androidx.annotation.RequiresApi
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.*

/** A tokenizer splitting a String into range tokens (e.g. words, sentences, etc.). */
@ExperimentalReadiumApi
typealias TextTokenizer = Tokenizer<String, IntRange>

/** A text token unit which can be used with a [TextTokenizer]. */
@ExperimentalReadiumApi
enum class TextUnit {
    Word, Sentence, Paragraph
}

/**
 * A default cluster [TextTokenizer] taking advantage of the best capabilities of each Android
 * version.
 */
@ExperimentalReadiumApi
class DefaultTextContentTokenizer private constructor(
    private val tokenizer: TextTokenizer
) : TextTokenizer by tokenizer {
    constructor(unit: TextUnit, locale: Locale?) : this(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            IcuTokenizer(locale = locale, unit = unit)
        else
            NaiveTokenizer(unit = unit)
    )
}

/**
 * Implementation of a [TextTokenizer] using ICU components to perform the actual
 * tokenization while taking into account languages specificities.
 */
@ExperimentalReadiumApi
@RequiresApi(Build.VERSION_CODES.N)
class IcuTokenizer(locale: Locale?, unit: TextUnit) : TextTokenizer {

    private val iterator: BreakIterator

    init {
        val loc = locale ?: Locale.ROOT
        iterator = when (unit) {
            TextUnit.Word -> BreakIterator.getWordInstance(loc)
            TextUnit.Sentence -> BreakIterator.getSentenceInstance(loc)
            TextUnit.Paragraph -> throw IllegalArgumentException("IcuUnitTextContentTokenizer does not handle TextContentUnit.Paragraph")
        }
    }

    override fun tokenize(data: String): List<IntRange> {
        iterator.setText(data)
        var start: Int = iterator.first()
        var end: Int = iterator.next()
        return buildList {
            while (end != BreakIterator.DONE) {
                add(start until end)
                start = end
                end = iterator.next()
            }
        }
    }
}

/**
 * A naive [Tokenizer] relying on java.text.BreakIterator to split the content.
 * Use [IcuTokenizer] for better results.
 */
@ExperimentalReadiumApi
class NaiveTokenizer(unit: TextUnit) : TextTokenizer {
    private val iterator: java.text.BreakIterator = when (unit) {
        TextUnit.Word -> java.text.BreakIterator.getWordInstance()
        TextUnit.Sentence -> java.text.BreakIterator.getSentenceInstance()
        TextUnit.Paragraph -> throw IllegalArgumentException("NaiveUnitTextContentTokenizer does not handle TextContentUnit.Paragraph")
    }

    override fun tokenize(data: String): List<IntRange> {
        iterator.setText(data)
        var start: Int = iterator.first()
        var end: Int = iterator.next()
        return buildList {
            while (end != java.text.BreakIterator.DONE) {
                add(start until end)
                start = end
                end = iterator.next()
            }
        }
    }
}

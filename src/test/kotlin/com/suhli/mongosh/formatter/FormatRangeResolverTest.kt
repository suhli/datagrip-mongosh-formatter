package com.suhli.mongosh.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatRangeResolverTest {
    @Test
    fun `editor selection wins over a whole-file platform range`() {
        val range = FormatRangeResolver.resolve(
            sourceLength = 80,
            formattingRanges = listOf(0 to 80),
            selectionStart = 20,
            selectionEnd = 45,
        )
        assertEquals(20 to 45, range)
    }

    @Test
    fun `caret without selection formats the whole document`() {
        val range = FormatRangeResolver.resolve(
            sourceLength = 80,
            formattingRanges = listOf(0 to 80),
            selectionStart = 12,
            selectionEnd = 12,
        )
        assertNull(range)
    }

    @Test
    fun `platform fragment is used when the editor has no selection`() {
        val range = FormatRangeResolver.resolve(
            sourceLength = 80,
            formattingRanges = listOf(10 to 30),
            selectionStart = 4,
            selectionEnd = 4,
        )
        assertEquals(10 to 30, range)
    }

    @Test
    fun `whole-file ranges in the platform list are ignored`() {
        val range = FormatRangeResolver.resolve(
            sourceLength = 50,
            formattingRanges = listOf(0 to 50, 12 to 20),
        )
        assertEquals(12 to 20, range)
    }

    @Test
    fun `empty selection and empty ranges format the whole document`() {
        assertNull(FormatRangeResolver.resolve(sourceLength = 40, formattingRanges = emptyList()))
    }
}

package com.termuxagent.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termuxagent.ui.theme.MonoTextStyle

/**
 * Minimal, dependency-free Markdown renderer. Handles the subset an LLM agent
 * actually emits in its final answers: paragraphs, headings, fenced code
 * blocks, inline code, bold, italics, bullet lists, and horizontal rules.
 *
 * Intentionally not a full CommonMark parser — keeping it tight means zero
 * dependency risk and predictable rendering.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = parseBlocks(markdown)
    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = renderInline(block.text),
                        style = style.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = renderInline(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MdBlock.Code -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Text(
                                text = block.code,
                                style = MonoTextStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
                is MdBlock.ListItem -> {
                    Text(
                        text = renderInline("• ${block.text}"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                is MdBlock.NumberedItem -> {
                    Text(
                        text = renderInline("${block.n}. ${block.text}"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                is MdBlock.Quote -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = renderInline(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                is MdBlock.HRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) { /* hairline */ }
                }
            }
        }
    }
}

// ── Parser ───────────────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class ListItem(val text: String) : MdBlock()
    data class NumberedItem(val n: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object HRule : MdBlock()
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    val paragraphBuf = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuf.isNotBlank()) {
            out.add(MdBlock.Paragraph(paragraphBuf.toString().trim()))
            paragraphBuf.setLength(0)
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val lang = line.trimStart().removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.appendLine(lines[i])
                i++
            }
            i++ // skip closing ```
            out.add(MdBlock.Code(language = lang, code = code.toString().trimEnd()))
            continue
        }
        // Horizontal rule
        if (line.matches(Regex("^\\s*([-*_])\\1{2,}\\s*$"))) {
            flushParagraph()
            out.add(MdBlock.HRule)
            i++
            continue
        }
        // Heading
        val headingMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        if (headingMatch != null) {
            flushParagraph()
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            out.add(MdBlock.Heading(level, text))
            i++
            continue
        }
        // Block quote
        val quoteMatch = Regex("^>\\s?(.*)$").matchEntire(line)
        if (quoteMatch != null) {
            flushParagraph()
            out.add(MdBlock.Quote(quoteMatch.groupValues[1]))
            i++
            continue
        }
        // Numbered list
        val numMatch = Regex("^\\s*(\\d+)\\.\\s+(.+)$").matchEntire(line)
        if (numMatch != null) {
            flushParagraph()
            val n = numMatch.groupValues[1].toIntOrNull() ?: 1
            out.add(MdBlock.NumberedItem(n, numMatch.groupValues[2]))
            i++
            continue
        }
        // Bullet list
        val bulletMatch = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(line)
        if (bulletMatch != null) {
            flushParagraph()
            out.add(MdBlock.ListItem(bulletMatch.groupValues[1]))
            i++
            continue
        }
        // Blank line: paragraph break
        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }
        // Default: append to paragraph buffer
        if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
        paragraphBuf.append(line.trim())
        i++
    }
    flushParagraph()
    return out
}

// ── Inline rendering (bold, italic, code, links) ─────────────────────────────

private fun renderInline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = src
    while (i < s.length) {
        // Inline code `...`
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = androidx.compose.ui.graphics.Color(0x22808080))) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // Bold **...**
        if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            val end = s.indexOf("**", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(s.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // Italic *...*
        if (s[i] == '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i && end > i + 0 && s[i + 1] != '*' && (end - i) > 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // Link [text](url)
        if (s[i] == '[') {
            val closeText = s.indexOf(']', i + 1)
            if (closeText > i && closeText + 1 < s.length && s[closeText + 1] == '(') {
                val closeUrl = s.indexOf(')', closeText + 2)
                if (closeUrl > closeText) {
                    val text = s.substring(i + 1, closeText)
                    append(text)
                    i = closeUrl + 1
                    continue
                }
            }
        }
        append(s[i])
        i++
    }
}

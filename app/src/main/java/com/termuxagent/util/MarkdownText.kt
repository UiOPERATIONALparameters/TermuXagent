package com.termuxagent.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termuxagent.ui.theme.MonoTextStyle
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.Extension

/**
 * Markdown renderer using commonmark-java (industry-standard parser) with
 * GFM table support. Replaces the broken custom renderer.
 *
 * Renders: headings, bold, italic, code, code blocks, links, lists (bullet +
 * ordered), blockquotes, thematic breaks, tables, and inline formatting.
 *
 * Wrapped in SelectionContainer so the user can select/copy AI messages.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val extensions: List<Extension> = listOf(TablesExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val document = parser.parse(markdown)

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            MarkdownBlocks(document)
        }
    }
}

@Composable
private fun MarkdownBlocks(document: org.commonmark.node.Node) {
    val visitor = CollectingVisitor()
    document.accept(visitor)
    val blocks = visitor.blocks

    for (block in blocks) {
        when (block) {
            is MdBlock.Heading -> {
                val style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    3 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.labelLarge
                }
                Text(
                    text = block.annotatedString,
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            is MdBlock.Paragraph -> {
                Text(
                    text = block.annotatedString,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
            is MdBlock.CodeBlock -> {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        Text(
                            text = block.code,
                            style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
            }
            is MdBlock.BulletItem -> {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.annotatedString,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            is MdBlock.NumberedItem -> {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("${block.n}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.annotatedString,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            is MdBlock.Quote -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = block.annotatedString,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            is MdBlock.Table -> {
                // Simple table rendering
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    // Header
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        block.headers.forEachIndexed { i, header ->
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
                    // Rows
                    for (row in block.rows) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            row.forEachIndexed { i, cell ->
                                Text(
                                    text = cell,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            is MdBlock.HRule -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
            }
        }
    }
}

// ── Block collection ─────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class Heading(val level: Int, val annotatedString: AnnotatedString) : MdBlock()
    data class Paragraph(val annotatedString: AnnotatedString) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class BulletItem(val annotatedString: AnnotatedString) : MdBlock()
    data class NumberedItem(val n: Int, val annotatedString: AnnotatedString) : MdBlock()
    data class Quote(val annotatedString: AnnotatedString) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    object HRule : MdBlock()
}

private class CollectingVisitor : AbstractVisitor() {
    val blocks = mutableListOf<MdBlock>()
    private val inlineBuilder = InlineBuilder()
    private var orderedCounter = 0

    override fun visit(heading: Heading) {
        inlineBuilder.reset()
        visitChildren(heading)
        blocks.add(MdBlock.Heading(heading.level, inlineBuilder.build()))
    }

    override fun visit(paragraph: Paragraph) {
        inlineBuilder.reset()
        visitChildren(paragraph)
        blocks.add(MdBlock.Paragraph(inlineBuilder.build()))
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        blocks.add(MdBlock.CodeBlock(fencedCodeBlock.info.trim(), fencedCodeBlock.literal.trimEnd()))
    }

    override fun visit(bulletList: BulletList) {
        visitChildren(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        orderedCounter = orderedList.startNumber
        visitChildren(orderedList)
    }

    override fun visit(listItem: ListItem) {
        inlineBuilder.reset()
        visitChildren(listItem)
        val parent = listItem.parent
        if (parent is BulletList) {
            blocks.add(MdBlock.BulletItem(inlineBuilder.build()))
        } else if (parent is OrderedList) {
            blocks.add(MdBlock.NumberedItem(orderedCounter++, inlineBuilder.build()))
        }
    }

    override fun visit(thematicBreak: ThematicBreak) {
        blocks.add(MdBlock.HRule)
    }

    // GFM tables: AbstractVisitor doesn't have a visit(TableBlock) override.
    // We intercept at the Document level and walk children manually for table nodes.
    override fun visit(document: Document) {
        var child = document.firstChild
        while (child != null) {
            if (child is org.commonmark.ext.gfm.tables.TableBlock) {
                visitTableBlock(child)
            } else {
                child.accept(this)
            }
            child = child.next
        }
    }

    // Also handle tables nested in other blocks (e.g., blockquotes)
    override fun visit(blockQuote: BlockQuote) {
        // Check if any direct child is a table
        var child = blockQuote.firstChild
        val hasTable = child?.let { hasTableInTree(it) } ?: false
        if (hasTable) {
            // Process children manually
            var c = blockQuote.firstChild
            while (c != null) {
                if (c is org.commonmark.ext.gfm.tables.TableBlock) {
                    visitTableBlock(c)
                } else {
                    c.accept(this)
                }
                c = c.next
            }
        } else {
            inlineBuilder.reset()
            visitChildren(blockQuote)
            blocks.add(MdBlock.Quote(inlineBuilder.build()))
        }
    }

    private fun hasTableInTree(node: org.commonmark.node.Node): Boolean {
        var n: org.commonmark.node.Node? = node
        while (n != null) {
            if (n is org.commonmark.ext.gfm.tables.TableBlock) return true
            if (n.firstChild != null && hasTableInTree(n.firstChild!!)) return true
            n = n.next
        }
        return false
    }

    private fun visitTableBlock(node: org.commonmark.ext.gfm.tables.TableBlock) {
        val headers = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is org.commonmark.ext.gfm.tables.TableHead -> {
                    val headChild = child.firstChild
                    if (headChild is org.commonmark.ext.gfm.tables.TableRow) {
                        var cell = headChild.firstChild
                        while (cell != null) {
                            if (cell is org.commonmark.ext.gfm.tables.TableCell) {
                                headers.add(cellText(cell))
                            }
                            cell = cell.next
                        }
                    }
                }
                is org.commonmark.ext.gfm.tables.TableBody -> {
                    var bodyChild = child.firstChild
                    while (bodyChild != null) {
                        if (bodyChild is org.commonmark.ext.gfm.tables.TableRow) {
                            val row = mutableListOf<String>()
                            var cell = bodyChild.firstChild
                            while (cell != null) {
                                if (cell is org.commonmark.ext.gfm.tables.TableCell) {
                                    row.add(cellText(cell))
                                }
                                cell = cell.next
                            }
                            rows.add(row)
                        }
                        bodyChild = bodyChild.next
                    }
                }
            }
            child = child.next
        }
        blocks.add(MdBlock.Table(headers, rows))
    }

    private fun cellText(cell: org.commonmark.ext.gfm.tables.TableCell): String {
        val sb = StringBuilder()
        var child = cell.firstChild
        while (child != null) {
            if (child is Text) sb.append(child.literal)
            child = child.next
        }
        return sb.toString().trim()
    }

    // Inline elements
    override fun visit(text: Text) {
        inlineBuilder.append(text.literal)
    }

    override fun visit(emphasis: Emphasis) {
        inlineBuilder.startStyle(SpanStyle(fontStyle = FontStyle.Italic))
        visitChildren(emphasis)
        inlineBuilder.endStyle()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        inlineBuilder.startStyle(SpanStyle(fontWeight = FontWeight.Bold))
        visitChildren(strongEmphasis)
        inlineBuilder.endStyle()
    }

    override fun visit(code: Code) {
        inlineBuilder.append(code.literal, SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = androidx.compose.ui.graphics.Color(0x33808080)))
    }

    override fun visit(link: Link) {
        inlineBuilder.startStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        visitChildren(link)
        inlineBuilder.endStyle()
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        inlineBuilder.append(" ")
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        inlineBuilder.append("\n")
    }
}

private class InlineBuilder {
    private val builder = AnnotatedString.Builder()
    private val styleStack = mutableListOf<SpanStyle>()

    fun reset() {
        builder.clear()
        styleStack.clear()
    }

    fun append(text: String) {
        builder.append(text)
    }

    fun append(text: String, style: SpanStyle) {
        builder.pushStyle(style)
        builder.append(text)
        builder.pop()
    }

    fun startStyle(style: SpanStyle) {
        styleStack.add(style)
        builder.pushStyle(style)
    }

    fun endStyle() {
        if (styleStack.isNotEmpty()) {
            styleStack.removeAt(styleStack.lastIndex)
            builder.pop()
        }
    }

    fun build(): AnnotatedString = builder.toAnnotatedString()
}

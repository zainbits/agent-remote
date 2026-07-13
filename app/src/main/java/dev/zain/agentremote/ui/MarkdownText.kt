package dev.zain.agentremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for assistant replies.
 * Supports: headings, bold/italic, inline code, fenced code, lists,
 * blockquotes, hr, simple pipe tables, links (shown as text).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val quoteBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                val isLast = index == blocks.lastIndex
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = inlineMarkdown(block.text),
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            color = bodyColor,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }

                    is MdBlock.Paragraph -> {
                        val annotated = remember(block.text, streaming, isLast) {
                            buildAnnotatedString {
                                append(inlineMarkdown(block.text))
                                if (streaming && isLast) append("▍")
                            }
                        }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyLarge,
                            color = bodyColor,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }

                    is MdBlock.Bullet -> {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "•  ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = muted,
                            )
                            Text(
                                text = inlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = bodyColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MdBlock.Numbered -> {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "${block.n}.  ",
                                style = MaterialTheme.typography.bodyLarge,
                                color = muted,
                            )
                            Text(
                                text = inlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = bodyColor,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MdBlock.Quote -> {
                        Text(
                            text = inlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = muted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(quoteBorder.copy(alpha = 0.12f))
                                .padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                        )
                    }

                    is MdBlock.Code -> {
                        CodeBlock(
                            code = block.code,
                            language = block.lang,
                            streaming = streaming && isLast,
                            background = codeBg,
                            color = bodyColor,
                        )
                    }

                    is MdBlock.Hr -> {
                        Text(
                            text = "────────",
                            color = muted.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    is MdBlock.Table -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(codeBg)
                                .padding(8.dp),
                        ) {
                            block.rows.forEachIndexed { rowIndex, row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    row.forEach { cell ->
                                        Text(
                                            text = inlineMarkdown(cell.trim()),
                                            style = if (rowIndex == 0) {
                                                MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            } else {
                                                MaterialTheme.typography.bodySmall
                                            },
                                            color = bodyColor,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (streaming && blocks.isEmpty()) {
                Text("▍", color = muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun CodeBlock(
    code: String,
    language: String?,
    streaming: Boolean,
    background: Color,
    color: Color,
) {
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var wrapLines by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.uppercase() ?: "Code",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                enabled = code.isNotEmpty(),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code block",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { wrapLines = !wrapLines },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (wrapLines) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            ) {
                Icon(
                    Icons.Default.WrapText,
                    contentDescription = if (wrapLines) {
                        "Disable code line wrapping"
                    } else {
                        "Wrap code lines"
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = code + if (streaming) "▍" else "",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            ),
            color = color,
            softWrap = wrapLines,
            modifier = (if (wrapLines) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.horizontalScroll(scroll)
            }).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val n: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val code: String, val lang: String?) : MdBlock()
    data object Hr : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
}

private fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out += MdBlock.Paragraph(t)
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim().ifBlank { null }
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            out += MdBlock.Code(code.toString(), lang)
            i++
            continue
        }

        if (trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
            flushPara()
            out += MdBlock.Hr
            i++
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (heading != null) {
            flushPara()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }

        if (trimmed.contains('|') && i + 1 < lines.size &&
            lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$"))
        ) {
            flushPara()
            val rows = mutableListOf<List<String>>()
            fun splitRow(r: String): List<String> =
                r.trim().removePrefix("|").removeSuffix("|").split('|')

            rows += splitRow(trimmed)
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].trim().isNotEmpty()) {
                rows += splitRow(lines[i])
                i++
            }
            out += MdBlock.Table(rows)
            continue
        }

        val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        if (bullet != null) {
            flushPara()
            out += MdBlock.Bullet(bullet.groupValues[1])
            i++
            continue
        }

        val numbered = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)
        if (numbered != null) {
            flushPara()
            out += MdBlock.Numbered(numbered.groupValues[1].toInt(), numbered.groupValues[2])
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushPara()
            val q = StringBuilder(trimmed.removePrefix(">").trim())
            i++
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                q.append(' ').append(lines[i].trim().removePrefix(">").trim())
                i++
            }
            out += MdBlock.Quote(q.toString())
            continue
        }

        if (trimmed.isEmpty()) {
            flushPara()
            i++
            continue
        }

        if (para.isNotEmpty()) para.append(' ')
        para.append(trimmed)
        i++
    }
    flushPara()
    return out
}

/** Bold, italic, inline code, ~~strike~~, simple [links](url). */
private fun inlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    val s = text
    while (i < s.length) {
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x33888888),
                        fontSize = 13.sp,
                    ),
                ) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (s.startsWith("**", i) || s.startsWith("__", i)) {
            val marker = s.substring(i, i + 2)
            val end = s.indexOf(marker, i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(s.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (s[i] == '*' || s[i] == '_') {
            val marker = s[i]
            val end = s.indexOf(marker, i + 1)
            if (end > i + 1 && (i == 0 || s[i - 1] != marker)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (s.startsWith("~~", i)) {
            val end = s.indexOf("~~", i + 2)
            if (end > i) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(s.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (s[i] == '[') {
            val close = s.indexOf(']', i + 1)
            if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                val urlEnd = s.indexOf(')', close + 2)
                if (urlEnd > close) {
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF5B8DEF),
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(s.substring(i + 1, close))
                    }
                    i = urlEnd + 1
                    continue
                }
            }
        }
        append(s[i])
        i++
    }
}

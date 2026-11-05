package app.tapdrop.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.GeistMonoFamily
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType

// A small Markdown reader for release notes: ### headings, "- " bullets (nested by indent, or "1."), paragraphs,
// **bold**, *italic*, `code`, [links](https://…) and bare https:// links. Anything else shows as plain text.

/** One block of release notes. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val depth: Int, val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Splits [md] into blocks. Pure, so it can be tested without a screen. */
fun parseMarkdown(md: String): List<MdBlock> {
    val text = md.replace("\r\n", "\n").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i].trimEnd())
                    i++
                }
                if (code.isNotEmpty()) out += MdBlock.Code(code.toString())
            }
            trimmed.isEmpty() -> flush()
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.matchEntire(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
            }
            RULE.matches(trimmed) -> {
                flush()
                out += MdBlock.Rule
            }
            BULLET.matches(trimmed) -> {
                flush()
                val m = BULLET.matchEntire(trimmed)!!
                out += MdBlock.Bullet((indent / 2).coerceAtMost(3), "•", m.groupValues[1])
            }
            NUMBERED.matches(trimmed) -> {
                flush()
                val m = NUMBERED.matchEntire(trimmed)!!
                out += MdBlock.Bullet((indent / 2).coerceAtMost(3), m.groupValues[1] + ".", m.groupValues[2])
            }
            else -> {
                val body = trimmed.removePrefix(">").trim()
                val last = out.lastOrNull()
                // A wrapped bullet line continues its bullet.
                if (para.isEmpty() && indent >= 2 && last is MdBlock.Bullet && i > 0 && lines[i - 1].isNotBlank()) {
                    out[out.lastIndex] = last.copy(text = last.text + " " + body)
                } else {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(body)
                }
            }
        }
        i++
    }
    flush()
    return out
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")

/** Inline Markdown → styled text. Links open in the browser when tapped. */
fun inlineMarkdown(text: String, link: Color, codeBackground: Color, strong: Color? = null): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var i = 0
    val s = text
    fun plain(c: Char) = append(c)
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                plain(s[i + 1]); i += 2
            }
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = GeistMonoFamily, background = codeBackground)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    plain(c); i++
                }
            }
            (s.startsWith("**", i) || s.startsWith("__", i)) -> {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong ?: Color.Unspecified)) {
                        append(inlineMarkdown(s.substring(i + 2, end), link, codeBackground, strong))
                    }
                    i = end + 2
                } else {
                    append(mark); i += 2
                }
            }
            c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(s.substring(i + 1, end), link, codeBackground, strong)) }
                    i = end + 1
                } else {
                    plain(c); i++
                }
            }
            c == '!' && s.startsWith("![", i) -> {
                // An image: its description stands in for it.
                val m = LINK.find(s, i + 1)?.takeIf { it.range.first == i + 1 }
                if (m != null) {
                    append(m.groupValues[1]); i = m.range.last + 1
                } else {
                    plain(c); i++
                }
            }
            c == '[' -> {
                val m = LINK.find(s, i)?.takeIf { it.range.first == i }
                val url = m?.groupValues?.get(2)?.trim()
                if (m != null && url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    withLink(LinkAnnotation.Url(url, linkStyles)) { append(inlineMarkdown(m.groupValues[1], link, codeBackground, strong)) }
                    i = m.range.last + 1
                } else {
                    plain(c); i++
                }
            }
            c == 'h' && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                var end = i
                while (end < s.length && !s[end].isWhitespace() && s[end] != ')' && s[end] != '<') end++
                while (end > i && s[end - 1] in ".,;:!?") end--
                val url = s.substring(i, end)
                withLink(LinkAnnotation.Url(url, linkStyles)) { append(url.substringAfter("://").removePrefix("www.")) }
                i = end
            }
            c == '<' && s.startsWith("<br", i, ignoreCase = true) -> {
                val end = s.indexOf('>', i)
                if (end > i) {
                    append('\n'); i = end + 1
                } else {
                    plain(c); i++
                }
            }
            else -> {
                plain(c); i++
            }
        }
    }
}

private val LINK = Regex("""\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private const val ESCAPABLE = "\\`*_{}[]()#+-.!>"

/**
 * Release notes on screen, in the app's type: headings in the title weight, bullets and paragraphs in the
 * secondary text color with **bold** brought forward, `code` in Geist Mono, links in the accent color.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, dropHeading: String? = null) {
    val c = Td.colors
    val blocks = remember(markdown, dropHeading) {
        parseMarkdown(markdown).let { b ->
            // The sheet already says "What's new"; don't say it twice.
            if (dropHeading != null && (b.firstOrNull() as? MdBlock.Heading)?.text?.equals(dropHeading, ignoreCase = true) == true) b.drop(1) else b
        }
    }
    val codeBg = c.text.copy(alpha = if (c.isDark) 0.10f else 0.06f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(b.text, c.accentText, codeBg),
                    style = if (b.level <= 2) TdType.subtitle else TdType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp).semantics { heading() },
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(start = (b.depth * 16).dp)) {
                    Text(
                        b.marker,
                        style = TdType.bodySmall,
                        color = if (b.marker == "•") c.accentText else c.textSecondary,
                        modifier = Modifier.width(if (b.marker == "•") 16.dp else 22.dp),
                    )
                    Text(inlineMarkdown(b.text, c.accentText, codeBg, strong = c.text), style = TdType.bodySmall, color = c.textSecondary)
                }
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(b.text, c.accentText, codeBg, strong = c.text),
                    style = TdType.bodySmall,
                    color = c.textSecondary,
                )
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(b.text, style = TdType.mono.copy(fontSize = TdType.caption.fontSize, lineHeight = TdType.caption.lineHeight), color = c.text)
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.hairline))
            }
        }
    }
}

/** The first line of the notes as plain text (for the Home card), or null. */
fun notesSummary(markdown: String): String? = parseMarkdown(markdown)
    .firstOrNull { it is MdBlock.Bullet || it is MdBlock.Paragraph }
    ?.let { b -> if (b is MdBlock.Bullet) b.text else (b as MdBlock.Paragraph).text }
    ?.let { inlineMarkdown(it, Color.Unspecified, Color.Unspecified).text.trim() }
    ?.takeIf { it.isNotEmpty() }

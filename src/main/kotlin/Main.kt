import java.nio.file.Files
import kotlin.io.path.Path

data class Attribute(val name: String, val type: String) {
    companion object {
        val matcher = "var (.+): (.+)".toRegex()
    }
}

data class HtmlTag(val name: String, val attributes: List<Attribute>, val parents: List<String>) {
    companion object {
        val tagMatcher = "(class|interface) (\\S+)".toRegex()
        val parentMatcher = "([A-Za-z0-9<>]+)".toRegex()
        val isHtmlElementToken = "HTMLElement"
        val tokenTriggers = setOf(
            "public external abstract class",
            "public external interface"
        )
    }
}

tailrec fun parseAttributes(
    lines: List<String>,
    skip: Int = 0,
    accu: List<Attribute> = emptyList()
): Pair<List<Attribute>, Int> =
    if (lines.first().startsWith("}")) accu to skip
    else {
        val result = Attribute.matcher.find(lines.first())
        if (result == null) parseAttributes(lines.drop(1), skip + 1, accu)
        else {
            val (name, type) = result.destructured
            parseAttributes(lines.drop(1), skip + 1, accu + Attribute(if (name == "htmlFor") "`for`" else name, type))
        }
    }

fun parseParents(line: String): List<String> =
    if (line.contains(':')) HtmlTag.parentMatcher.findAll(line.split(':').last()).map { it.value }.toList()
    else emptyList()

tailrec fun parseTypes(lines: List<String>, accu: List<HtmlTag> = emptyList()): List<HtmlTag> =
    if (lines.isEmpty()) accu
    else if (HtmlTag.tokenTriggers.any { lines.first().startsWith(it) }) {
        val (attributes, skip) = parseAttributes(lines.drop(1))
        val parents = parseParents(lines.first())
        val (_, tagName) = HtmlTag.tagMatcher.find(lines.first())!!.destructured
        parseTypes(lines.drop(skip), accu + HtmlTag(tagName, attributes, parents))
    } else parseTypes(lines.drop(1), accu)

fun cleanUnknownParents(tag: HtmlTag, elements: Map<String, HtmlTag>): HtmlTag =
    tag.copy(parents = tag.parents.filter { elements.contains(it) })

/**
 * elements map has to contain *all* elements from parents list!
 * So ensure you have cleaned elements via [cleanUnknownParents] before!
 */
tailrec fun isHtmlElement(nodes: List<String>, elements: Map<String, HtmlTag>): Boolean {
    if (nodes.isEmpty()) return false
    val tag = elements[nodes.first()]!!
    return if (tag.name == HtmlTag.isHtmlElementToken) true
    else isHtmlElement(tag.parents + nodes.drop(1), elements)
}

tailrec fun enrichMissingAttributes(
    nodes: List<String>,
    elements: Map<String, HtmlTag>,
    accu: List<Attribute> = emptyList()
): List<Attribute> {
    val noneHtmlTags = nodes.filter { !isHtmlElement(listOf(it), elements) }
    if (noneHtmlTags.isEmpty()) return accu
    val tag = elements[noneHtmlTags.first()]!!
    return enrichMissingAttributes(
        tag.parents + noneHtmlTags.drop(1),
        elements,
        if (tag.attributes.isNotEmpty()) accu + listOf(
            Attribute(
                "// inherited attributes from supertype ${tag.name}",
                "Comment"
            )
        ) + tag.attributes else accu
    )
}

fun parse(lines: List<String>): List<HtmlTag> {
    val elements = parseTypes(lines).map { it.name to it }.toMap()
    val cleanedElements = elements.values.map { cleanUnknownParents(it, elements) }.map { it.name to it }.toMap()
    return cleanedElements.values.filter { isHtmlElement(listOf(it.name), cleanedElements) }
        .map { it.copy(attributes = it.attributes + enrichMissingAttributes(it.parents, cleanedElements)) }
}

fun main(args: Array<String>) {
    val htmlTags = parse(Files.readAllLines(Path(args[0])))

    println(buildString {
        htmlTags.filter { it.attributes.isNotEmpty() }
            .forEach { tag ->
                appendLine()
                appendLine("// ${tag.name} attributes")
                tag.attributes.forEach { (name, type) ->
                    when (type) {
                        "Boolean" -> {
                            appendLine("""fun Tag<${tag.name}>.$name(value: $type, trueValue: String = "") = attr("$name", value, trueValue)""")
                            appendLine("""fun Tag<${tag.name}>.$name(value: Flow<$type>, trueValue: String = "") = attr("$name", value, trueValue)""")
                        }
                        "Comment" -> {
                            appendLine(name)
                        }
                        else -> {
                            appendLine("""fun Tag<${tag.name}>.$name(value: $type) = attr("$name", value)""")
                            appendLine("""fun Tag<${tag.name}>.$name(value: Flow<$type>) = attr("$name", value)""")
                        }
                    }
                }
            }
    })
}

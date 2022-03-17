import java.nio.file.Files
import java.util.*
import kotlin.io.path.Path

data class Attribute(val name: String, val typeKt: String, val typeHtml: String) {
    companion object {
        val matcher = "var (.+): ([A-Za-z0-9?]+)".toRegex()
    }

    val valueAsString = if (typeKt != "String") "value.toString()" else "value"
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
            parseAttributes(
                lines.drop(1),
                skip + 1,
                accu + Attribute(
                    when (name) {
                        "htmlFor" -> "`for`"
                        "_object" -> "`object`"
                        else -> name
                    },
                    if (type == "dynamic") "String" else type,
                    when (name) {
                        "htmlFor" -> "for"
                        "`as`" -> "as"
                        "_object" -> "object"
                        else -> name
                    }.lowercase()
                )
            )
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
        parseTypes(
            lines.drop(skip), accu + HtmlTag(
                tagName,
                attributes.filter { it.name != "className" },
                parents
            )
        )
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
                "Comment",
                ""
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

interface FunGenerator {
    fun generateAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute)
    fun generateBooleanAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute)
}

class SimpleAttrGenerator : FunGenerator {
    override fun generateAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        builder.apply {
            appendLine(
                """
                fun Tag<${tag.name}>.${attr.name}(value: ${attr.typeKt}) = attr("${attr.typeHtml}", value)
                fun Tag<${tag.name}>.${attr.name}(value: Flow<${attr.typeKt}>) = attr("${attr.typeHtml}", value)
                    
                """.trimIndent()
            )
        }
    }

    override fun generateBooleanAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        builder.apply {
            appendLine(
                """ 
                fun Tag<${tag.name}>.${attr.name}(value: ${attr.typeKt}, trueValue: String = "") = attr("${attr.typeHtml}", value, trueValue)
                fun Tag<${tag.name}>.${attr.name}(value: Flow<${attr.typeKt}>, trueValue: String = "") = attr("${attr.typeHtml}", value, trueValue)
                
                """.trimIndent()
            )
        }
    }
}

/**
 * Generates some DOM API specific edge case setting of attributes like the following:
 *
 * ```kotlin
 * // for none boolean attributes
 * fun Tag<SomeElement>.value(value: String) {
 *     domNode.value = value
 *     domNode.defaultValue = value
 *     domNode.setAttribute("value", value)
 * }
 *
 * fun Tag<SomeElement>.value(value: Flow<String>) {
 *     mountSimple(job, value) { v -> value(v) }
 * }
 *
 * // for boolean attributes
 * fun Tag<SomeElement>.checked(value: Boolean, trueValue: String = "") {
 *     domNode.checked = value
 *     domNode.defaultChecked = value
 *     if (value) domNode.setAttribute("checked", trueValue)
 *     else domNode.removeAttribute("checked")
 * }
 *
 * fun Tag<SomeElement>.checked(value: Flow<Boolean>, trueValue: String = "") {
 *     mountSimple(job, value) { v -> checked(v, trueValue) }
 * }
 * ```
 */
class SpecialDomApiAttrGenerator : FunGenerator {
    override fun generateAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        builder.apply {
            appendLine(
                """
                fun Tag<${tag.name}>.${attr.name}(value: ${attr.typeKt}) {
                    domNode.${attr.name} = value
                    domNode.default${attr.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} = value
                    domNode.setAttribute("${attr.typeHtml}", ${attr.valueAsString})
                }
                
                fun Tag<${tag.name}>.${attr.name}(value: Flow<${attr.typeKt}>) {
                    mountSimple(job, value) { v -> ${attr.name}(v) }
                }

                """.trimIndent()
            )
        }
    }

    override fun generateBooleanAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        builder.apply {
            appendLine(
                """
                fun Tag<${tag.name}>.${attr.name}(value: ${attr.typeKt}, trueValue: String = "") {
                    domNode.${attr.name} = value
                    domNode.default${attr.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} = value
                    if (value) domNode.setAttribute("${attr.typeHtml}", trueValue)
                    else domNode.removeAttribute("${attr.typeHtml}")
                }
                
                fun Tag<${tag.name}>.${attr.name}(value: Flow<${attr.typeKt}>, trueValue: String = "") {
                    mountSimple(job, value) { v -> ${attr.name}(v, trueValue) }
                }

                """.trimIndent()
            )
        }
    }
}

class DelegatingFunGenerator(
    private val simpleGenerator: FunGenerator,
    private val specialGenerator: FunGenerator
) : FunGenerator {

    private val specialDomApiAttributes = setOf(
        ("HTMLInputElement" to "checked"),
        ("HTMLInputElement" to "value"),
        ("HTMLMediaElement" to "playbackRate"),
        ("HTMLMediaElement" to "muted"),
        ("HTMLOptionElement" to "selected"),
        ("HTMLOutputElement" to "value"),
        ("HTMLTextAreaElement" to "value"),
    )

    override fun generateAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        if (specialDomApiAttributes.contains(tag.name to attr.name)) {
            specialGenerator.generateAttribute(builder, tag, attr)
        } else {
            simpleGenerator.generateAttribute(builder, tag, attr)
        }
    }

    override fun generateBooleanAttribute(builder: StringBuilder, tag: HtmlTag, attr: Attribute) {
        if (specialDomApiAttributes.contains(tag.name to attr.name)) {
            specialGenerator.generateBooleanAttribute(builder, tag, attr)
        } else {
            simpleGenerator.generateBooleanAttribute(builder, tag, attr)
        }
    }
}

fun main(args: Array<String>) {
    val generator = DelegatingFunGenerator(SimpleAttrGenerator(), SpecialDomApiAttrGenerator())
    val htmlTags = parse(Files.readAllLines(Path(args[0])))

    println(buildString {
        appendLine("/*")
        appendLine(" * Generated by https://github.com/chausknecht/HtmlTagAttributesExtractor")
        appendLine(" * Pay attention to local modifications before pasting an updated output here!")
        appendLine(" * Add manual extensions above this section (like the SVG attributes).")
        appendLine(" */")
        htmlTags.filter { it.attributes.isNotEmpty() }
            .forEach { tag ->
                appendLine()
                appendLine(
                    """
                    |/*
                    | * ${tag.name} attributes
                    | */
                """.trimMargin()
                )
                tag.attributes.forEach { attr ->
                    when (attr.typeKt) {
                        "Boolean" -> generator.generateBooleanAttribute(this, tag, attr)
                        "Comment" -> appendLine(attr.name)
                        else -> generator.generateAttribute(this, tag, attr)
                    }
                }
            }
    })
}

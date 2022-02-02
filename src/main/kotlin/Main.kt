import java.nio.file.Files
import kotlin.io.path.Path

data class Attribute(val name: String, val type: String) {
    companion object {
        val matcher = "var (.+): (.+)".toRegex()
    }
}

data class HtmlTag(val name: String, val attributes: List<Attribute>) {
    companion object {
        val matcher = "(class|interface) (\\S+) ".toRegex()
        val tokenTriggers = setOf(
            "public external abstract class HTML",
            "public external interface HTML"
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
            parseAttributes(lines.drop(1), skip + 1, accu + Attribute(name, type))
        }
    }

tailrec fun parse(lines: List<String>, accu: List<HtmlTag> = emptyList()): List<HtmlTag> =
    if (lines.isEmpty()) accu
    else if (HtmlTag.tokenTriggers.any { lines.first().startsWith(it) }) {
        val (attributes, skip) = parseAttributes(lines.drop(1))
        val (_, tagName) = HtmlTag.matcher.find(lines.first())!!.destructured
        parse(lines.drop(skip), accu + HtmlTag(tagName, attributes))
    } else parse(lines.drop(1), accu)


fun main(args: Array<String>) {
    println(buildString {
        parse(Files.readAllLines(Path(args[0]))).filter { it.attributes.isNotEmpty() }.forEach { tag ->
            appendLine()
            appendLine("// ${tag.name} attributes")
            tag.attributes.forEach { (name, type) ->
                if (type == "Boolean") {
                    appendLine("""fun Tag<${tag.name}>.$name(value: $type, trueValue: String = "") = attr("$name", value, trueValue)""")
                    appendLine("""fun Tag<${tag.name}>.$name(value: Flow<$type>, trueValue: String = "") = attr("$name", value, trueValue)""")
                } else {
                    appendLine("""fun Tag<${tag.name}>.$name(value: $type) = attr("$name", value)""")
                    appendLine("""fun Tag<${tag.name}>.$name(value: Flow<$type>) = attr("$name", value)""")
                }
            }
        }
    })
}

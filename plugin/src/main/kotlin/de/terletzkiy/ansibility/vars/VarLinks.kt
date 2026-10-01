package de.terletzkiy.ansibility.vars

import de.terletzkiy.ansibility.api.SourceLocation
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Links inside variable cards, resolved by [VarDocumentationLinkHandler]. They use the `psi_element://` scheme, so
 * the documentation popup never hands an unresolved one to the browser.
 * - `…/option/<name>/<sub>`: a (nested) option of the card's variable, from the Options table;
 * - `…/var/<name>/<sub>`: another variable of the same root (`O(name)` markup in descriptions);
 * - `…/def/<offset>/<file url>`: the definition written at a position ("Set in", "Declared by", runtime default).
 */
internal object VarLinks {
    const val PREFIX: String = "psi_element://ansibility-var/"
    private const val OPTION = "option/"
    private const val VARIABLE = "var/"
    private const val DEFINITION = "def/"

    sealed interface Link {
        data class Option(val names: List<String>) : Link
        data class Variable(val name: String, val path: List<String>) : Link
        data class Definition(val fileUrl: String, val offset: Int) : Link
    }

    fun option(names: List<String>): String = PREFIX + OPTION + names.joinToString("/", transform = ::encode)

    fun variable(path: List<String>): String = PREFIX + VARIABLE + path.joinToString("/", transform = ::encode)

    fun definition(location: SourceLocation): String = PREFIX + DEFINITION + location.offset + "/" + encode(location.file.url)

    fun parse(url: String): Link? {
        if (!url.startsWith(PREFIX)) return null
        val rest = url.removePrefix(PREFIX)
        return when {
            rest.startsWith(OPTION) -> segments(rest.removePrefix(OPTION)).takeIf { it.isNotEmpty() }?.let(Link::Option)
            rest.startsWith(VARIABLE) -> segments(rest.removePrefix(VARIABLE)).takeIf { it.isNotEmpty() }?.let { Link.Variable(it.first(), it.drop(1)) }
            rest.startsWith(DEFINITION) -> {
                val body = rest.removePrefix(DEFINITION)
                val offset = body.substringBefore('/').toIntOrNull() ?: return null
                val file = body.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
                Link.Definition(decode(file), offset)
            }
            else -> null
        }
    }

    private fun segments(text: String): List<String> = text.split('/').filter { it.isNotEmpty() }.map(::decode)

    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    private fun decode(text: String): String = URLDecoder.decode(text, StandardCharsets.UTF_8)
}

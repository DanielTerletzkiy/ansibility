package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.navigation.TemplateNameValues
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import de.terletzkiy.ansibility.vars.SpecOptions
import de.terletzkiy.ansibility.vars.VarRanking

/**
 * Value completion for spec'd variables in vars-like places (plan X19, value part): at the value of a key whose
 * argument-spec option (top-level or nested) has `choices`, the choices in spec order; for `bool` options, `true` and
 * `false`. In a sequence item of a list variable, the element's choices (`choices` apply per element for lists) or
 * booleans for `elements: bool`.
 *
 * A string choice whose plain spelling YAML would load as another type (`"yes"`, `"no"`, `"1.10"`, `"null"`) or that
 * cannot be written plain is inserted double-quoted; inside quotes it is inserted as written, with the quote escaped.
 * When several roles declare the variable, their choices are merged in ranking order ([VarRanking]). "(default)" marks
 * the value equal to the declaring role's role default (the value Ansible uses, [RoleDefaults]; compared like ANS-S003),
 * never the spec's documented `default:`.
 *
 * A key whose values a rendering task turns into template names (`loki_nginx_sites[].floating.template` through
 * `src: "templates/nginx/{{ item.floating.template }}"`, the classifier's [AnsibleSite.TemplateRef]) still gets its
 * spec choices here: a choice that names an existing template of the root shows that template's file icon and the
 * type `template`, and Ctrl+B on the written value opens the same template (role navigation). Without choices, the
 * role-navigation source offers the template files. Jinja belongs to the Jinja sources: for other sites this source
 * adds nothing. Needs smart mode (the variable index).
 */
class VarsValueCompletionSource : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (site != null && site !is AnsibleSite.VarKey && site !is AnsibleSite.TemplateRef) return
        val request = VarsCompletionRequest.of(parameters) ?: return
        val position = request.valuePosition ?: return
        val values = valuesFor(request, position)
        if (values.isEmpty()) return
        val templates = TemplateNameValues.templatesFor(request.yaml, request.context, position.varPath)
        val results = result.withPrefixMatcher(position.prefix)
        val doc = { text: String -> VarLookupDoc(request.root.dir, position.varPath.first(), position.varPath.drop(1), request.file, request.offset, text) }
        values.forEachIndexed { rank, value ->
            val text = value.text
            val insert = when (val quote = position.quote) {
                null -> if (value.mustQuote) doubleQuoted(text) else text
                '\'' -> text.replace("'", "''")
                else -> text.replace("\\", "\\\\").replace("\"", "\\\"")
            }
            val presentable = if (position.quote == null) insert else text
            val template = templates[text]
            val element = if (template != null) {
                val icon = template.fileType.icon ?: AllIcons.FileTypes.Any_type
                val typeText = AnsibilityKeyCompletionBundle.message("value.type.template")
                KeyLookups.value(text, insert, presentable, typeText, value.isDefault, rank, doc(text), icon)
            } else {
                KeyLookups.value(text, insert, presentable, value.typeText, value.isDefault, rank, doc(text))
            }
            results.addElement(element)
        }
    }

    /** One value to offer. */
    private class Candidate(val text: String, val mustQuote: Boolean, val typeText: String?, val isDefault: Boolean)

    private fun valuesFor(request: VarsCompletionRequest, position: ValuePosition): List<Candidate> {
        val name = position.varPath.first()
        val rest = position.varPath.drop(1)
        val symbol = VarService.getInstance(request.project).symbol(request.root, name)
        if (symbol.specBindings.isEmpty()) return emptyList()
        val ranking = VarRanking(
            request.project, request.root, request.context.roleName, request.file, request.context.kind, symbol,
            MoleculeView.of(request.project, request.file),
        )
        val result = LinkedHashMap<String, Candidate>()
        val version = TargetVersionDetector.getInstance(request.project).targetVersion(request.root).version ?: CoreVersion.PINNED
        val comparator = SpecDefaults(CoreSemantics(version))
        for (binding in ranking.bindings) {
            ProgressManager.checkCanceled()
            val option = SpecOptions.resolve(binding.option, rest)?.option ?: continue
            // "(default)" marks the role default Ansible uses, never the spec's documented one (plan amendment R23, D174).
            val roleDefault = if (position.element) null else roleDefault(request, binding.role.dir, name, rest)
            val isDefault = { choice: YValue -> roleDefault != null && comparator.compare(option.copy(default = choice, required = false), roleDefault) == SpecDefaults.Verdict.Equal }
            for (candidate in candidates(option, position.element, position.quote != null, isDefault)) result.putIfAbsent(candidate.text, candidate)
        }
        return result.values.toList()
    }

    /** The value of the role default of [name] at [path] in the role at [roleDir]; null for secrets, templates and missing keys. */
    private fun roleDefault(request: VarsCompletionRequest, roleDir: VirtualFile, name: String, path: List<String>): YValue? {
        val default = RoleDefaults.of(request.project, roleDir, name)?.takeUnless { it.isSecret || it.merged } ?: return null
        var value: YValue = default.value
        for (segment in path) value = (value as? YMap)?.entries?.lastOrNull { it.key.text == segment }?.value ?: return null
        return value.takeUnless { it is YScalar && JinjaBearing.hasTemplateMarkers(it.text) }
    }

    /** The values [option] accepts at the position: its own value, or one element of it when [element]; [isDefault] marks the role default. */
    private fun candidates(option: OptionSpec, element: Boolean, quoted: Boolean, isDefault: (YValue) -> Boolean): List<Candidate> {
        val valueType = if (element) {
            if (option.type != OptionType.List) return emptyList()
            option.elements
        } else {
            if (option.type == OptionType.List || option.type == OptionType.Dict) return emptyList()
            option.type
        }
        val typeText = valueType?.name
        val choices = option.choices?.values
        if (choices != null) {
            return choices.mapNotNull { choice ->
                val scalar = choice as? YScalar ?: return@mapNotNull null
                val isString = scalar.resolved is Resolved.Str
                // Inside quotes only strings can be written; non-string choices keep their YAML spelling.
                if (quoted && !isString) return@mapNotNull null
                Candidate(scalar.text, isString && !isPlainSafe(scalar.text), typeText, !element && isDefault(scalar))
            }
        }
        if (valueType == OptionType.Bool && !quoted) {
            return listOf(true, false).map { Candidate(it.toString(), false, typeText, !element && isDefault(YScalar(it.toString(), ScalarStyle.PLAIN))) }
        }
        return emptyList()
    }

    companion object {
        private const val PLAIN_UNSAFE_START = "-?:,[]{}#&*!|>'\"%@`"

        /**
         * True when [text] can be written as a plain scalar and YAML 1.1 still loads it as that string: no leading
         * indicator, no `: ` or ` #`, no surrounding blanks, and no implicit bool, number, null or timestamp spelling.
         */
        fun isPlainSafe(text: String): Boolean {
            if (text.isEmpty() || text.first().isWhitespace() || text.last().isWhitespace()) return false
            if (text.first() in PLAIN_UNSAFE_START || text.any { it == '\n' || it == '\r' || it == '\t' }) return false
            if (text.contains(": ") || text.contains(" #") || text.endsWith(":")) return false
            return Yaml11Resolver.resolvePlain(text) is Resolved.Str
        }

        fun doubleQuoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

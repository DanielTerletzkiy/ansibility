package de.terletzkiy.ansibility.inspections.types

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.TypeFinding
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import de.terletzkiy.ansibility.types.SpecPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The quick fixes of one finding (🟣 CLAUDE X79, X80), from the semantics layer's hints and the bindings:
 * - `quote` → "Quote value"; `unquote` → "Unquote value"; `replace=<literal>` → "Replace with '…'";
 * - `nearest-choice=<v>` → "Replace with nearest choice '…'" (written plain when that loads as the documented type);
 * - `remove-key` → "Remove unsupported key"; ANS-T002 also offers "Add sub-option … to <role> argument_specs";
 * - ANS-T010 on a mapping item where the spec documents `elements: str` → "Update <role> spec from usage".
 *
 * Value fixes need a scalar without anchor or tag (replacing it must not drop them); spec fixes need a spec file of the
 * same root that the project can write.
 */
internal object TypeFixes {
    private const val QUOTE = "quote"
    private const val UNQUOTE = "unquote"
    private const val REPLACE = "replace="
    private const val NEAREST_CHOICE = "nearest-choice="
    private const val REMOVE_KEY = "remove-key"

    fun of(finding: TypeFinding, element: PsiElement, root: AnsibleRoot): Array<LocalQuickFix> {
        val fixes = ArrayList<LocalQuickFix>()
        val scalar = (element as? YAMLScalar)?.takeIf(::isBare)
        for (hint in finding.fixHints) {
            when {
                hint == QUOTE && scalar != null && scalar !is YAMLQuotedText -> fixes += QuoteValueFix()
                hint == UNQUOTE && scalar is YAMLQuotedText -> fixes += UnquoteValueFix()
                hint.startsWith(REPLACE) && scalar != null -> {
                    val literal = hint.removePrefix(REPLACE)
                    fixes += ReplaceValueFix(literal, literal, nearestChoice = false, keepQuotes = false)
                }
                hint.startsWith(NEAREST_CHOICE) && scalar != null -> {
                    val choice = hint.removePrefix(NEAREST_CHOICE)
                    val type = documentedType(finding)
                    val plain = if (type == OptionType.Raw) writtenPlainInSpec(finding, choice) else writesAsDocumented(choice, type)
                    fixes += ReplaceValueFix(choice, if (plain) choice else SpecEdits.quoted(choice), nearestChoice = true, keepQuotes = type != OptionType.Raw)
                }
                hint == REMOVE_KEY && element is YAMLKeyValue -> fixes += RemoveKeyFix(element.keyText)
            }
        }
        if (finding.code == DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION) {
            val key = finding.path.last()
            val optionPath = SpecPaths.optionNames(finding.path).dropLast(1)
            for (binding in finding.bindings.filter { isEditable(it, root) }) {
                fixes += AddSubOptionFix(binding.role.name, binding.location.file, binding.entryPoint, optionPath, key, SpecEdits.fieldsFor(finding.value))
            }
        }
        if (finding.code == DiagnosticCode.T010_SHAPE_CONTRADICTION && finding.value is YMap && finding.path.size == 2) {
            for (binding in finding.bindings.filter { isEditable(it, root) && documentsStringElements(it) }) {
                fixes += UpdateSpecFromUsageFix(binding.role.name, root.dir, binding.location.file, binding.entryPoint, finding.path.first(), finding.usageAttributes)
            }
        }
        return fixes.toTypedArray()
    }

    /** No anchor or tag before the content: the scalar can be replaced as a whole. */
    private fun isBare(scalar: YAMLScalar): Boolean = YamlPsi.contentStart(scalar) == scalar.firstChild

    private fun documentedType(finding: TypeFinding): OptionType? =
        finding.bindings.firstNotNullOfOrNull { SpecPaths.documentedTypeAt(it.option, finding.path) }

    /** True when [text] written plain loads as a value of the documented [type] (else it is written double-quoted). */
    private fun writesAsDocumented(text: String, type: OptionType?): Boolean {
        val resolved = Yaml11Resolver.resolvePlain(text)
        return when (type) {
            OptionType.Int -> resolved is Resolved.Int
            OptionType.Float -> resolved is Resolved.Float || resolved is Resolved.Int
            OptionType.Bool -> resolved is Resolved.Bool
            else -> SpecEdits.isSafePlain(text)
        }
    }

    /**
     * For a `raw` option, whose value is compared with the choices without coercion: true when the spec writes [choice]
     * as a plain scalar (an int choice stays an int), false when it quotes it.
     */
    private fun writtenPlainInSpec(finding: TypeFinding, choice: String): Boolean {
        val choices = finding.bindings.firstNotNullOfOrNull { SpecPaths.optionAt(it.option, finding.path)?.choices }?.values.orEmpty()
        val written = choices.filterIsInstance<YScalar>().firstOrNull { it.text == choice } ?: return SpecEdits.isSafePlain(choice)
        return written.style == ScalarStyle.PLAIN && written.tag == null
    }

    private fun documentsStringElements(binding: SpecBinding): Boolean {
        val option = binding.option
        return option.type == OptionType.List && option.options == null && (option.elements == OptionType.Str || option.elements == OptionType.Path)
    }

    /** A spec file of [root] (never another root's copy) that the project can write. */
    private fun isEditable(binding: SpecBinding, root: AnsibleRoot): Boolean {
        val file = binding.location.file
        return file.isValid && file.isWritable && VfsUtilCore.isAncestor(root.dir, file, false)
    }
}

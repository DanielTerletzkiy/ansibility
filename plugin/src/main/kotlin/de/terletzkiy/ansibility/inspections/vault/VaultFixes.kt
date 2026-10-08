package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The password-free quick fixes of ANS-V102, ANS-V103, ANS-V107 and ANS-V114 (plan A.13: `localInspection` +
 * ModCommand, native preview, nothing secret). They edit the text only, through the document of the ModCommand copy,
 * so the layout around the value stays exactly as written; no envelope is decrypted or re-encrypted.
 */
object VaultFixes {
    private val HEX = Regex("[0-9A-Fa-f]+")

    /** The fix of [finding], or null when it has none. */
    fun of(finding: VaultFinding): LocalQuickFix? = when (finding.fix) {
        null -> null
        VaultFixKind.FOLDED_TO_LITERAL -> ConvertToLiteralBlockFix(flattened = false)
        VaultFixKind.FLATTENED_TO_LITERAL -> ConvertToLiteralBlockFix(flattened = true)
        VaultFixKind.STRIP_TRAILING_WHITESPACE -> StripTrailingWhitespaceFix()
        VaultFixKind.CONVERT_TO_WHOLE_FILE_VAULT -> finding.conversion?.let(::ConvertToWholeFileVaultFix)
        VaultFixKind.ADD_VAULT_TAG -> AddVaultTagFix()
    }

    /**
     * The header and payload of a flattened value (YAML joined its lines with spaces): the whitespace-separated words of
     * [value], or null unless the first is a `$ANSIBLE_VAULT` header and every other one is hex.
     */
    fun flattenedTokens(value: String): Pair<String, String>? {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || !words[0].startsWith(VaultEnvelope.MAGIC)) return null
        val payload = words.drop(1)
        if (!payload.all(HEX::matches)) return null
        return words[0] to payload.joinToString("")
    }

    /** The column of the key (or the sequence dash) that owns [scalar], or 0 for a top-level value. */
    internal fun ownerColumn(scalar: YAMLScalar): Int = when (val parent = scalar.parent) {
        is YAMLKeyValue -> YamlPsi.columnOf(parent)
        is YAMLSequenceItem -> YamlPsi.columnOf(parent)
        else -> 0
    }

    /** Replaces [from, to) of [document] and commits it, so later PSI reads of the copy see the change. */
    internal fun replace(project: Project, document: Document, from: Int, to: Int, text: String) {
        document.replaceString(from, to, text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/**
 * ANS-V102 "Convert to literal block": a folded `!vault >` block becomes `!vault |` (the payload lines are already
 * written one per line, and the chomping indicator stays); a plain or quoted value that holds the envelope on one line
 * ([flattened]) is rewritten as a literal block: the header and the payload, wrapped at 80 columns, at the block indent
 * the file already uses (`VaultLayout.bodyIndentFor`, +2 by default).
 */
class ConvertToLiteralBlockFix(private val flattened: Boolean) : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v102.literal")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLScalar ?: return
        val document = updater.document
        val start = scalar.textRange.startOffset
        if (!flattened) {
            // The block indicator follows the tag (and any anchor) after a blank, on the scalar's first line.
            val firstLine = scalar.text.substringBefore('\n')
            val indicator = FOLDED_INDICATOR.find(firstLine)?.range?.last ?: return
            VaultFixes.replace(project, document, start + indicator, start + indicator + 1, "|")
            return
        }
        val (header, payload) = VaultFixes.flattenedTokens(scalar.textValue) ?: return
        val content = YamlPsi.contentStart(scalar) ?: return
        val prefix = document.charsSequence.subSequence(start, content.textRange.startOffset).toString().trimEnd()
        val indent = " ".repeat(VaultLayout.bodyIndentFor(document.charsSequence, VaultFixes.ownerColumn(scalar)))
        val lines = listOf(header) + payload.chunked(VaultEnvelope.LINE_WIDTH)
        val head = if (prefix.isEmpty()) "|" else "$prefix |"
        val block = lines.joinToString("\n", prefix = "$head\n") { indent + it }
        VaultFixes.replace(project, document, start, scalar.textRange.endOffset, block)
    }

    private companion object {
        val FOLDED_INDICATOR = Regex("(?:^|\\s)>")
    }
}

/**
 * ANS-V103 "Strip trailing whitespace": removes trailing spaces and TABs from every line of the value (after the block
 * indicator line of an inline value; every line of a whole-file vault). Ansible's reader concatenates the payload
 * lines without stripping them, so these blanks are what makes it fail with "Odd-length string".
 */
class StripTrailingWhitespaceFix : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v103.strip")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val document = updater.document
        val range = element.textRange
        val first = document.getLineNumber(range.startOffset) + if (element is PsiFile) 0 else 1
        val last = document.getLineNumber(minOf(range.endOffset, document.textLength))
        if (first > last) return
        val chars = document.charsSequence
        val edits = (first..last).mapNotNull { line ->
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            var trimmed = lineEnd
            while (trimmed > lineStart && (chars[trimmed - 1] == ' ' || chars[trimmed - 1] == '\t')) trimmed--
            if (trimmed < lineEnd) trimmed to lineEnd else null
        }
        if (edits.isEmpty()) return
        for ((from, to) in edits.asReversed()) document.deleteString(from, to)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/**
 * ANS-V107 "Convert to whole-file vault" (plan amendment R21, D159): the file becomes the whole-file vault it holds:
 * the `!vault |` or `key: !vault |` line, comments and blank lines before the envelope, its indentation and trailing
 * blanks go; the header and the payload digits stay as written (`VaultFileShape.unwrapped`). The document keeps the
 * file's line separator when it is saved. Offered only when the result is a well-formed envelope; [applyFix] classifies
 * the copy again and writes only when it still holds exactly the envelope the inspection saw. One undoable step, no
 * password, nothing decrypted.
 */
class ConvertToWholeFileVaultFix(private val conversion: WholeFileConversion) : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v107.convert")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val document = updater.document
        val shape = VaultFileShape.classify(document.charsSequence, conversion.context) ?: return
        if (!shape.kind.isWrapped || shape.kind == VaultShapeKind.BYTE_ORDER_MARK) return
        val envelope = (shape.inner as? EnvelopeParse.Ok)?.envelope ?: return
        val unwrapped = shape.unwrapped ?: return
        if (envelope != conversion.envelope) return
        VaultFixes.replace(project, document, 0, document.textLength, unwrapped)
    }
}

/**
 * ANS-V114 "Add !vault tag": `key: |` with an envelope becomes `key: !vault |`, so Ansible decrypts the value instead
 * of passing the envelope text on. The value itself is not touched (ANS-V101–V103 judge it once it is tagged).
 */
class AddVaultTagFix : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v114.tag")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLScalar ?: return
        if (YamlPsi.tagOf(scalar) != null) return
        val start = scalar.textRange.startOffset
        VaultFixes.replace(project, updater.document, start, start, "$TAG ")
    }

    private companion object {
        const val TAG = "!vault"
    }
}

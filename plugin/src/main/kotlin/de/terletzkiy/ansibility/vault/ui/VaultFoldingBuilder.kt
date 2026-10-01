package de.terletzkiy.ansibility.vault.ui

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalarList

/**
 * 🟣 X16: folds the hex body of every literal `!vault |` block to `🔒 vault 1.1 · default · 6 lines`, collapsed by
 * default.
 *
 * Spike S-V2 (no collision with `YAMLFoldingBuilder`, see docs/research/vault-ide.md "S-V2 result"): YAML folds the
 * whole scalar, from its `!vault` tag to the end of the block. This region starts at the line break after the `|`
 * indicator and ends at the block's last character, so it nests strictly inside YAML's region (the composite
 * builder drops only regions with identical ranges). It is anchored on that line-break token, not on the scalar,
 * so the two builders never share a node, and its placeholder and collapse state live in the descriptor itself.
 *
 * The placeholder is static: header facts as written (version, label or `default`) and the line count. It never
 * shows plaintext, never decrypts, never reads a secret or a lock state, and reads no configuration (the file's own
 * text only), so it is DumbAware.
 */
class VaultFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is YAMLFile || !StringUtil.contains(document.charsSequence, VAULT_TAG)) return FoldingDescriptor.EMPTY_ARRAY
        val text = document.charsSequence
        val descriptors = ArrayList<FoldingDescriptor>()
        for (scalar in PsiTreeUtil.findChildrenOfType(root, YAMLScalarList::class.java)) {
            ProgressManager.checkCanceled()
            if (!VaultValuePsi.isVault(scalar)) continue
            val body = VaultValuePsi.literalBody(scalar, text) ?: continue
            val range = body.foldRange
            if (range.isEmpty || range.endOffset > text.length) continue
            val anchor = generateSequence(scalar.firstChild) { it.nextSibling }.firstOrNull { it.elementType == YAMLTokenTypes.SCALAR_EOL } ?: continue
            descriptors += FoldingDescriptor(anchor.node, range, null, placeholder(VaultValuePsi.header(scalar), body.lines), true, emptySet())
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = placeholder(null, 0)

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    companion object {
        private const val VAULT_TAG = "!vault"

        /** `🔒 vault 1.1 · default · 6 lines`: the header as written and the body's line count. */
        fun placeholder(header: VaultHeaderInfo?, lines: Int): String = AnsibilityVaultUiBundle.message(
            "folding.placeholder", header?.version ?: "?", header?.labelOrDefault() ?: VaultHeaderInfo.DEFAULT_IDENTITY, lines,
        )
    }
}

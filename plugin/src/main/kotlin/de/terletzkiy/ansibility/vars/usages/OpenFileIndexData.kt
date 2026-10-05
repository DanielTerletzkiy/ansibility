package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.index.AnsibleIndexInputFilter
import de.terletzkiy.ansibility.index.DefEntry
import de.terletzkiy.ansibility.index.IndexInput
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.index.VarDefIndexer
import de.terletzkiy.ansibility.index.VarUseIndexer
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The `ansible.var.use` and `ansible.var.def` entries of one file, computed by the indexes' own indexers from the
 * file's current PSI and cached on it until the file changes (F1.10, the caret highlighting's data).
 *
 * The caret highlighting runs on every caret move and after every keystroke. Asking `FileBasedIndex` for the file's
 * data there would re-index the edited document inside the index machinery on the first caret event after each
 * keystroke (several times the indexers' own cost on a 900-line vars file); this keeps the highlighting off the
 * indexes altogether. The input is the one the indexes build ([AnsibleIndexInputFilter], [IndexInput.of]): the same
 * files, the same text and the same YAML view, so the entries equal the indexed ones (a test compares them on the
 * fixture). Call in a read action.
 */
internal object OpenFileIndexData {
    /** The entries of one file, by variable name, each list in offset order. */
    class Data(val uses: Map<String, List<UseEntry>>, val definitions: Map<String, List<DefEntry>>)

    private val EMPTY = Data(emptyMap(), emptyMap())
    private val KEY = Key.create<CachedValue<Data>>("ansibility.usages.openFileIndexData")
    private val FILTER = AnsibleIndexInputFilter(templates = true)

    fun of(project: Project, file: VirtualFile): Data {
        if (!file.isValid || file.fileType.isBinary || !FILTER.acceptInput(file)) return EMPTY
        val psi = PsiManager.getInstance(project).findFile(file) ?: return EMPTY
        return CachedValuesManager.getCachedValue(psi, KEY) {
            val text = psi.viewProvider.contents
            // As IndexInput.of(FileContent): the file's own YAML PSI, else a YAML parse of the text when the name says YAML.
            val input = IndexInput(PathFacts.of(file), text) { psi as? YAMLFile ?: IndexInput.of(file.path, text, project).yaml }
            CachedValueProvider.Result.create(Data(VarUseIndexer.index(input), VarDefIndexer.index(input)), psi)
        }
    }
}

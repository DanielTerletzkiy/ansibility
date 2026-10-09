package de.terletzkiy.ansibility.golden

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.vfs.VirtualFile

/** What a golden action works on when invoked from the Ansibility tool window (plan amendment R24). */
object GoldenDataKeys {
    /** The role directory of the selected role copy. */
    val ROLE_COPY: DataKey<VirtualFile> = DataKey.create("ansibility.golden.roleCopy")

    /** The selected file's path inside the role copy ("tasks/main.yml"), also for a file that exists only in the golden copy. */
    val ROLE_PATH: DataKey<String> = DataKey.create("ansibility.golden.rolePath")
}

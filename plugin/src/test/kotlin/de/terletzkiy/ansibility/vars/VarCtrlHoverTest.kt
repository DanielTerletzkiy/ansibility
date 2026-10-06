package de.terletzkiy.ansibility.vars

import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Plan A4: the Ctrl-hover hint. Ctrl-hover asks Go to Declaration for targets and, for a single target, shows the
 * hint of that element's PSI documentation target, so [VarPsiDocumentationTargetProvider] answers for our targets
 * and for variable keys. With several targets the platform shows no documentation hint (a platform limitation).
 */
@RequiresInfraFixture
class VarCtrlHoverTest : VarsTestCase() {
    private val provider = VarPsiDocumentationTargetProvider()

    private fun keyValue(path: String, line: Int, name: String): YAMLKeyValue = runReadActionBlocking {
        val offset = offsetAt(path, line, name)
        PsiTreeUtil.getParentOfType(psi(path).findElementAt(offset), YAMLKeyValue::class.java, false)!!
    }

    private fun hintOf(element: PsiElement): String? = inBackgroundReadAction {
        provider.documentationTarget(element, null)?.computeDocumentationHint()?.let(::plain)
    }

    fun testIsRegistered() {
        val point = ExtensionPointName<PsiDocumentationTargetProvider>("com.intellij.platform.backend.documentation.psiTargetProvider")
        assertTrue(point.extensionList.any { it is VarPsiDocumentationTargetProvider })
    }

    fun testHintsForSpecOptionsAndVarsKeys() {
        copyInfra("repos/falcon")
        val spec = "repos/falcon/ansible/roles/postfix/meta/argument_specs.yml"
        assertEquals("postfix_relayhost: str = \"\" · postfix (optional)", hintOf(keyValue(spec, 6, "postfix_relayhost")))
        val defaults = "repos/falcon/ansible/roles/postfix/defaults/main.yml"
        assertEquals("postfix_relayhost: str = \"\" · postfix (optional)", hintOf(keyValue(defaults, 2, "postfix_relayhost")))
        val key = keyValue(defaults, 2, "postfix_relayhost")
        assertEquals("the key element itself", hintOf(key), hintOf(runReadActionBlocking { key.key!! }))

        val tasks = "repos/falcon/ansible/roles/postfix/tasks/main.yml"
        val structural = runReadActionBlocking { PsiTreeUtil.findChildrenOfType(psi(tasks), YAMLKeyValue::class.java).first { it.keyText == "name" } }
        assertNull("a task keyword is not a variable", hintOf(structural))
    }

    fun testHintsForNavigationTargets() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        val targets = gotoTargets(template, offsetAt(template, 9, "postfix_relayhost", 2))
        assertEquals(2, targets.size)
        for (target in targets) assertEquals("postfix_relayhost: str = \"\" · postfix (optional)", hintOf(target))
    }

    fun testPlatformCtrlHoverShowsTheHintForASingleTarget() {
        copyInfra("golden/roles/chronod")
        val nginx = "golden/roles/chronod/tasks/nginx.yml"
        myFixture.configureFromTempProjectFile(nginx)
        val editor = myFixture.editor
        val file = myFixture.file
        val offset = offsetAt(nginx, 18, "_chronod_nginx_cert", 2)
        // The Ctrl-hover computation of the platform (internal class; used here only to verify the whole path).
        val data = inBackgroundReadAction { GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, file, offset) }
        assertNotNull(data)
        assertTrue(data!!.isNavigatable)
        val hint = plain(data.hintText ?: "")
        assertTrue(hint, hint.startsWith("_chronod_nginx_cert"))
    }

    fun testSeveralTargetsShowNoVariableHint() {
        copyInfra("repos/falcon")
        val template = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
        myFixture.configureFromTempProjectFile(template)
        val editor = myFixture.editor
        val file = myFixture.file
        val offset = offsetAt(template, 9, "postfix_relayhost", 2)
        val data = inBackgroundReadAction { GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, file, offset) }
        assertNotNull(data)
        assertFalse("the platform shows no documentation hint for several targets", plain(data!!.hintText ?: "").startsWith("postfix_relayhost:"))
    }
}

package de.terletzkiy.ansibility.vars

import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2

/**
 * From the R31 review (commit fc2bf84), regression of plan A4 ([VarCtrlHoverTest]): the platform's Ctrl-hover shows a
 * documentation hint only when Go to Declaration has a single target. A required spec option without a default, which
 * an environment sets, had one target (the spec) before R31; R31 adds the override to Ctrl+B, and Ctrl-hover must keep
 * the spec hint (R31 fixes, decision 8: [CtrlHover]). The review's test, unchanged.
 */
class VarCtrlHoverRegressionTest : VarsTestCase() {
    fun testCtrlHoverOnASpecOnlyVariableThatAnEnvironmentSetsShowsTheSpecHint() {
        myFixture.tempDirFixture.createFile("h/ansible.cfg", "[defaults]\nroles_path = roles\n")
        myFixture.tempDirFixture.createFile("h/environments/test/hosts.yml", "---\nall:\n  children:\n    web:\n      hosts:\n        test-web1:\n")
        myFixture.tempDirFixture.createFile("h/environments/test/group_vars/web.yml", "---\nr_domain: test.example.org\n")
        myFixture.tempDirFixture.createFile("h/site.yml", "---\n- name: Web\n  hosts: web\n  roles:\n    - r\n")
        myFixture.tempDirFixture.createFile(
            "h/roles/r/meta/argument_specs.yml",
            "---\nargument_specs:\n  main:\n    options:\n      r_domain:\n        type: str\n        required: true\n        description: The public domain.\n",
        )
        val tasks = "h/roles/r/tasks/main.yml"
        myFixture.tempDirFixture.createFile(tasks, "---\n- name: Domain\n  ansible.builtin.debug:\n    msg: \"{{ r_domain }}\"\n")
        refreshRoots()
        myFixture.configureFromExistingVirtualFile(vf(tasks))
        val offset = offsetAt(tasks, 4, "r_domain", 2)
        val targets = gotoTargets(tasks, offset).map(::describe)
        val data = inBackgroundReadAction { GotoDeclarationOrUsageHandler2.getCtrlMouseData(myFixture.editor, myFixture.file, offset) }
        val hint = plain(data?.hintText ?: "")
        assertTrue("Ctrl-hover hint '$hint' (targets $targets)", hint.startsWith("r_domain"))
    }
}

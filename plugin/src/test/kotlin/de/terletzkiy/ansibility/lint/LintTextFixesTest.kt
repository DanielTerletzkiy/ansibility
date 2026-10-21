package de.terletzkiy.ansibility.lint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text edits of [LintFixes]: `# noqa` and `skip_list`. */
class LintTextFixesTest {
    @Test
    fun noqaIsAppendedOrMerged() {
        assertEquals("- shell: echo hi  # noqa: command-instead-of-shell", LintFixes.withNoqa("- shell: echo hi", "command-instead-of-shell"))
        assertEquals("- shell: echo hi  # noqa: no-changed-when name[missing]", LintFixes.withNoqa("- shell: echo hi  # noqa: no-changed-when", "name[missing]"))
        assertEquals("- x: 1 # noqa: a b # why", LintFixes.withNoqa("- x: 1 # noqa: a # why", "b"))
        assertNull(LintFixes.withNoqa("- x: 1  # noqa: a b", "b"))
    }

    @Test
    fun skipListGetsTheRuleInItsStyle() {
        val config = "---\nprofile: production\nskip_list:\n  - role-name\n  - yaml[line-length] # long lines\nvar_naming_pattern: x\n"
        assertEquals(
            "---\nprofile: production\nskip_list:\n  - role-name\n  - yaml[line-length] # long lines\n  - no-changed-when\nvar_naming_pattern: x\n",
            LintFixes.withSkipped(config, "no-changed-when"),
        )
        assertNull(LintFixes.withSkipped(config, "role-name"))
        assertEquals("---\nprofile: min\nskip_list:\n  - fqcn\n", LintFixes.withSkipped("---\nprofile: min\n", "fqcn"))
        assertEquals("skip_list: [a, fqcn]", LintFixes.withSkipped("skip_list: [a]", "fqcn"))
        assertEquals("skip_list: [fqcn]", LintFixes.withSkipped("skip_list: []", "fqcn"))
    }

    @Test
    fun failureExcerptsShowAnsibleLintNotCompose() {
        val stderr = " Container wren-ansible-lint-run-857a Creating \n Container wren-ansible-lint-run-857a Created\n"
        val stdout = "load-failure[yaml]: Failed to load YAML file (warning)\ngroup_vars/all/vault.yml:143:2 did not find expected key\n"
        assertEquals("load-failure[yaml]: Failed to load YAML file (warning)\ngroup_vars/all/vault.yml:143:2 did not find expected key", LintRunner.excerpt(stdout, stderr))
        assertEquals("ERROR boom", LintRunner.excerpt("", " Container x Created\nERROR boom\n"))
    }

    @Test
    fun severityModes() {
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.WARNING, LintAnnotator.severityOf(LintLevel.ERROR, LintSeverity.YELLOW))
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING, LintAnnotator.severityOf(LintLevel.WARNING, LintSeverity.YELLOW))
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, LintAnnotator.severityOf(LintLevel.ERROR, LintSeverity.MIRROR))
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING, LintAnnotator.severityOf(LintLevel.ERROR, LintSeverity.WEAK))
    }
}

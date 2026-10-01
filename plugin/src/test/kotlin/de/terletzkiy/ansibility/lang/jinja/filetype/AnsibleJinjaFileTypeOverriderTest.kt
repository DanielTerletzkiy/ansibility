package de.terletzkiy.ansibility.lang.jinja.filetype

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase
import org.jetbrains.yaml.YAMLFileType

/**
 * File type ownership (plan D8, A.9, F2.1, M5 acceptance 1): `.j2` inside Ansible roots, Jinja-bearing files under
 * `roles/<role>/templates`, the user's `*.j2 → YAML` mapping, "Keep YAML for .j2" and the refresh on changes.
 */
class AnsibleJinjaFileTypeOverriderTest : AnsibleJinjaTemplateTestCase() {
    fun testJ2InsideProjectRootsOnly() {
        createFile("repos/a/ansible/ansible.cfg", "[defaults]\n")
        createFile("repos/a/ansible/files/grafana/alert.yml.j2", "x: {{ y }}\n")
        createFile("repos/a/ansible/roles/web/templates/site.conf.j2", "{{ x }}\n")
        createFile("other/project/config.j2", "{{ x }}\n")
        createFile("other/project/roles/notarole/stuff/x.j2", "{{ x }}\n")
        assertSame(AnsibleJinjaFileType, vf("repos/a/ansible/files/grafana/alert.yml.j2").fileType)
        assertSame(AnsibleJinjaFileType, vf("repos/a/ansible/roles/web/templates/site.conf.j2").fileType)
        assertNotSame(AnsibleJinjaFileType, vf("other/project/config.j2").fileType)
        assertNotSame("roles/<dir> without role markers is no role", AnsibleJinjaFileType, vf("other/project/roles/notarole/stuff/x.j2").fileType)
        assertInstanceOf(viewProvider("repos/a/ansible/files/grafana/alert.yml.j2"), AnsibleJinjaFileViewProvider::class.java)
    }

    fun testRoleLibraryWithoutAnsibleCfg() {
        copyInfra("golden/roles/haproxy")
        createFile("golden/playbooks/templates/motd.j2", "{{ x }}\n")
        for (path in listOf(
            "golden/roles/haproxy/templates/haproxy.cfg.j2",
            "golden/roles/haproxy/templates/observability/config.alloy.j2",
            "golden/roles/haproxy/molecule/default/Dockerfile.j2",
            "golden/playbooks/templates/motd.j2",
        )) {
            assertSame(path, AnsibleJinjaFileType, vf(path).fileType)
        }
        assertEquals("golden", AnsibleTemplatePaths.ansibleBase(vf("golden/playbooks/templates/motd.j2"))?.name)
        assertEquals("golden", AnsibleTemplatePaths.ansibleBase(vf("golden/roles/haproxy/molecule/default/Dockerfile.j2"))?.name)
    }

    /** Acceptance 1 and D8: the overrider beats an `ext=j2 → YAML` mapping inside roots and leaves `.j2` elsewhere alone. */
    fun testOverriderBeatsTheUserJ2MappingInsideRootsOnly() {
        copyInfra("golden/roles/haproxy")
        createFile("elsewhere/config.yml.j2", "a: 1\n")
        withJ2As(YAMLFileType.YML) {
            assertSame(AnsibleJinjaFileType, vf("golden/roles/haproxy/templates/haproxy.cfg.j2").fileType)
            assertSame(YAMLFileType.YML, vf("elsewhere/config.yml.j2").fileType)
        }
    }

    fun testKeepYamlForJ2Reverts() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/templates/haproxy.cfg.j2"
        withJ2As(YAMLFileType.YML) {
            assertSame(AnsibleJinjaFileType, vf(path).fileType)
            withJinjaSettings({ copy(keepYamlForJ2 = true) }) {
                assertSame(YAMLFileType.YML, vf(path).fileType)
                assertFalse(viewProvider(path) is AnsibleJinjaFileViewProvider)
            }
            assertSame(AnsibleJinjaFileType, vf(path).fileType)
            assertInstanceOf(viewProvider(path), AnsibleJinjaFileViewProvider::class.java)
            withJinjaSettings({ copy(claimJ2InsideRoots = false) }) {
                assertSame(YAMLFileType.YML, vf(path).fileType)
            }
        }
    }

    fun testFilesWithoutJ2UnderTemplatesNeedJinja() {
        copyInfra("golden/roles/deployment-target", "golden/roles/percona", "repos/thrush/ansible/roles/app-thrush-mono")
        assertSame(AnsibleJinjaFileType, vf("golden/roles/deployment-target/templates/sudoers").fileType)
        assertSame(AnsibleJinjaFileType, vf("golden/roles/percona/templates/binary-backup.sh").fileType)
        assertSame(AnsibleJinjaFileType, vf("golden/roles/percona/templates/logging/logrotate").fileType)
        assertNotSame(AnsibleJinjaFileType, vf("golden/roles/percona/templates/binlog-backup.sh").fileType)
        assertNotSame(AnsibleJinjaFileType, vf("repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/nginx.conf").fileType)
        createFile("golden/roles/percona/templates/page.jinja2", "{{ never }}\n")
        createFile("golden/roles/percona/templates/page.jinja", "{{ never }}\n")
        assertNotSame(AnsibleJinjaFileType, vf("golden/roles/percona/templates/page.jinja2").fileType)
        assertNotSame(AnsibleJinjaFileType, vf("golden/roles/percona/templates/page.jinja").fileType)
        createFile("golden/roles/percona/files/copied.sh", "echo {{ never }}\n")
        assertNotSame("files/ is copied verbatim", AnsibleJinjaFileType, vf("golden/roles/percona/files/copied.sh").fileType)
        withJinjaSettings({ copy(treatJinjaTemplatesUnderTemplatesDir = false) }) {
            assertNotSame(AnsibleJinjaFileType, vf("golden/roles/deployment-target/templates/sudoers").fileType)
        }
        assertSame(AnsibleJinjaFileType, vf("golden/roles/deployment-target/templates/sudoers").fileType)
    }

    fun testContentProbeFollowsEdits() {
        createFile("roles/web/tasks/main.yml", "- debug: msg=x\n")
        val file = createFile("roles/web/templates/motd", "plain text\n")
        assertSame(PlainTextFileType.INSTANCE, file.fileType)
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(file, "Welcome to {{ inventory_hostname }}\n") }
        dispatchEvents()
        assertSame(AnsibleJinjaFileType, file.fileType)
        assertInstanceOf(viewProvider("roles/web/templates/motd"), AnsibleJinjaFileViewProvider::class.java)
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(file, "plain again\n") }
        dispatchEvents()
        assertSame(PlainTextFileType.INSTANCE, file.fileType)
        assertFalse(viewProvider("roles/web/templates/motd") is AnsibleJinjaFileViewProvider)
    }

    fun testNewAnsibleCfgClaimsTemplatesBelowIt() {
        createFile("site/files/motd.j2", "{{ x }}\n")
        assertNotSame(AnsibleJinjaFileType, vf("site/files/motd.j2").fileType)
        assertFalse(viewProvider("site/files/motd.j2") is AnsibleJinjaFileViewProvider)
        createFile("site/ansible.cfg", "[defaults]\n")
        dispatchEvents()
        assertSame(AnsibleJinjaFileType, vf("site/files/motd.j2").fileType)
        assertInstanceOf(viewProvider("site/files/motd.j2"), AnsibleJinjaFileViewProvider::class.java)
    }

    fun testNewRoleMarkerClaimsTheRoleTemplates() {
        createFile("lib/roles/web/templates/site.conf.j2", "{{ x }}\n")
        assertNotSame(AnsibleJinjaFileType, vf("lib/roles/web/templates/site.conf.j2").fileType)
        assertFalse(viewProvider("lib/roles/web/templates/site.conf.j2") is AnsibleJinjaFileViewProvider)
        createFile("lib/roles/web/tasks/main.yml", "- debug: msg=x\n")
        dispatchEvents()
        assertSame(AnsibleJinjaFileType, vf("lib/roles/web/templates/site.conf.j2").fileType)
        assertInstanceOf(viewProvider("lib/roles/web/templates/site.conf.j2"), AnsibleJinjaFileViewProvider::class.java)
    }

    fun testPathHelpers() {
        createFile("x/roles/r/templates/a/b.conf", "")
        createFile("x/roles/r/files/templates/c.conf", "")
        createFile("x/roles/templates/d.conf", "")
        assertTrue(AnsibleTemplatePaths.isUnderRoleTemplates(vf("x/roles/r/templates/a/b.conf")))
        assertFalse(AnsibleTemplatePaths.isUnderRoleTemplates(vf("x/roles/r/files/templates/c.conf")))
        assertFalse(AnsibleTemplatePaths.isUnderRoleTemplates(vf("x/roles/templates/d.conf")))
        assertTrue(JinjaContentProbe.hasMarkers("a {# c #}".toByteArray()))
        assertTrue(JinjaContentProbe.hasMarkers("{%".toByteArray()))
        assertFalse(JinjaContentProbe.hasMarkers("{ {} }".toByteArray()))
        assertFalse(JinjaContentProbe.hasMarkers("{".toByteArray()))
    }
}

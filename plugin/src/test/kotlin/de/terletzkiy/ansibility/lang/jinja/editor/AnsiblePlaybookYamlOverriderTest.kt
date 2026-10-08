package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.json.JsonFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.editor.coexist.AnsiblePlaybookYamlOverrider
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaTemplateTestCase
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.YAMLFileType

/**
 * X05: `*-playbook.yaml` names claimed by another file type (PyCharm Professional's `Jinja2` registers the patterns
 * `*-playbook.yml;*-playbook.yaml`; JSON stands in for it here) stay YAML in Ansible projects only.
 */
class AnsiblePlaybookYamlOverriderTest : AnsibleJinjaTemplateTestCase() {
    fun testComposeFilesNextToAnsibleRootsStayYaml() {
        createFile("repos/falcon/ansible/ansible.cfg", "[defaults]\n")
        createFile("repos/falcon/docker-compose.ansible-playbook.yaml", "services:\n  ansible-playbook:\n    image: x\n")
        createFile("repos/falcon/docker-compose.ansible-lint.yaml", "services: {}\n")
        createFile("web/app/docker-compose.deploy-playbook.yaml", "services: {}\n")
        withPlaybookPatternsAs(JsonFileType.INSTANCE) {
            assertSame(YAMLFileType.YML, vf("repos/falcon/docker-compose.ansible-playbook.yaml").fileType)
            assertSame("not a *-playbook name", YAMLFileType.YML, vf("repos/falcon/docker-compose.ansible-lint.yaml").fileType)
            assertSame("outside Ansible projects", JsonFileType.INSTANCE, vf("web/app/docker-compose.deploy-playbook.yaml").fileType)
        }
        assertSame(YAMLFileType.YML, vf("web/app/docker-compose.deploy-playbook.yaml").fileType)
    }

    fun testPlaybooksInsideAnsibleContent() {
        createFile("site/ansible.cfg", "[defaults]\n")
        createFile("site/site-playbook.yml", "- hosts: all\n  roles: [web]\n")
        createFile("site/docker-compose.site-playbook.yml", "services: {}\n")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/roles/web/templates/deploy-playbook.yml", "- hosts: {{ target }}\n")
        withPlaybookPatternsAs(JsonFileType.INSTANCE) {
            assertSame(YAMLFileType.YML, vf("site/site-playbook.yml").fileType)
            assertSame("templates stay templates", AnsibleJinjaFileType, vf("site/roles/web/templates/deploy-playbook.yml").fileType)
            // the setting re-types the files (AnsiblePlaybookYamlSettingsListener), also without PyCharm's Jinja2
            withJinjaSettings({ copy(deferToPyCharmJinja = true) }) {
                assertSame(JsonFileType.INSTANCE, vf("site/site-playbook.yml").fileType)
                assertSame("compose files stay YAML", YAMLFileType.YML, vf("site/docker-compose.site-playbook.yml").fileType)
            }
            assertSame(YAMLFileType.YML, vf("site/site-playbook.yml").fileType)
        }
    }

    fun testRules() {
        assertTrue(AnsiblePlaybookYamlOverrider.isPlaybookName("docker-compose.ansible-playbook.yaml"))
        assertTrue(AnsiblePlaybookYamlOverrider.isPlaybookName("site-playbook.yml"))
        assertFalse(AnsiblePlaybookYamlOverrider.isPlaybookName("-playbook.yml"))
        assertFalse(AnsiblePlaybookYamlOverrider.isPlaybookName("playbook-site.yml"))
        assertTrue(AnsiblePlaybookYamlOverrider.isComposeName("docker-compose.ansible-playbook.yaml"))
        assertTrue(AnsiblePlaybookYamlOverrider.isComposeName("compose.x-playbook.yml"))
        assertFalse(AnsiblePlaybookYamlOverrider.isComposeName("site-playbook.yml"))
        val compose = createFile("repos/a/docker-compose.ansible-playbook.yaml", "services: {}\n")
        assertFalse(AnsiblePlaybookYamlOverrider.keepsYaml(compose, JinjaSettings()))
        createFile("repos/a/ansible/ansible.cfg", "[defaults]\n")
        assertTrue(AnsiblePlaybookYamlOverrider.keepsYaml(compose, JinjaSettings(deferToPyCharmJinja = true)))
    }
}

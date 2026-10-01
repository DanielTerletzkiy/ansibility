package de.terletzkiy.ansibility.context

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.ContextTestTree.DANGER_ZONE
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PELICAN
import de.terletzkiy.ansibility.context.ContextTestTree.PLATFORM
import de.terletzkiy.ansibility.context.ContextTestTree.WT_FALCON
import de.terletzkiy.ansibility.semantics.CoreVersion

class TargetVersionDetectorTest : BasePlatformTestCase() {
    private lateinit var detector: TargetVersionDetector

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        detector = TargetVersionDetector.getInstance(project)
    }

    override fun tearDown() {
        try {
            detector.overrideFor = { null }
        } finally {
            super.tearDown()
        }
    }

    private fun root(path: String): AnsibleRoot {
        val dir = myFixture.findFileInTempDir(path)
        return AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == dir }
    }

    fun testParsesCorePins() {
        assertEquals(listOf(CoreVersion(2, 18, 8)), TargetVersionDetector.parsePins("RUN python3 -m pip install \"ansible-core==2.18.8\" passlib"))
        assertEquals(
            listOf(CoreVersion(2, 18, 8)),
            TargetVersionDetector.parsePins("RUN pip install --no-cache-dir \\\n      \"ansible-core==2.18.8\" \\\n      ansible-lint==25.1.0"),
        )
        assertEquals(listOf(CoreVersion(2, 19, 0)), TargetVersionDetector.parsePins("pip install ansible-core == 2.19"))
    }

    fun testMapsCommunityPackagePins() {
        assertEquals(listOf(CoreVersion(2, 18)), TargetVersionDetector.parsePins("RUN pip install ansible==11.1.0"))
        assertEquals(listOf(CoreVersion(2, 19)), TargetVersionDetector.parsePins("pip install 'ansible==12'"))
        assertEquals("ansible-lint is not ansible", emptyList<CoreVersion>(), TargetVersionDetector.parsePins("pip install ansible-lint==25.1.0"))
        assertEquals("a core pin wins over the package", listOf(CoreVersion(2, 17, 1)),
            TargetVersionDetector.parsePins("ansible==11.0.0 ansible-core==2.17.1"))
    }

    fun testDockerfilePinsOfTheRoot() {
        val falcon = detector.targetVersion(root(FALCON))
        assertEquals(CoreVersion(2, 18, 8), falcon.version)
        assertEquals(TargetVersionSource.DOCKERFILE, falcon.source)
        assertEquals("docker/ansible-playbook/Dockerfile", falcon.detail)
        assertFalse(falcon.guessed)

        val golden = detector.targetVersion(root(GOLDEN))
        assertEquals(CoreVersion(2, 18, 8), golden.version)
        assertEquals("docker/ansible-lint/Dockerfile", golden.detail)
    }

    fun testNestedRootsInheritTheParentPin() {
        assertEquals(CoreVersion(2, 18, 0), detector.targetVersion(root(PELICAN)).version)
        val nested = detector.targetVersion(root(DANGER_ZONE))
        assertEquals(CoreVersion(2, 18, 0), nested.version)
        assertEquals(TargetVersionSource.DOCKERFILE, nested.source)
    }

    fun testRootsWithoutPinsUseTheMajority() {
        // voters: falcon 2.18.8, golden 2.18.8, pelican 2.18.0; the nested root does not vote twice for pelican
        val platform = detector.targetVersion(root(PLATFORM))
        assertEquals(CoreVersion(2, 18, 8), platform.version)
        assertEquals(TargetVersionSource.MAJORITY, platform.source)
        assertEquals("majority of 3 roots", platform.detail)
        assertTrue(platform.guessed)
        assertEquals(TargetVersionSource.MAJORITY, detector.targetVersion(root(WT_FALCON)).source)
    }

    fun testSettingsOverrideWins() {
        detector.overrideFor = { if (it.displayName == "falcon") CoreVersion(2, 19, 1) else null }
        val falcon = detector.targetVersion(root(FALCON))
        assertEquals(CoreVersion(2, 19, 1), falcon.version)
        assertEquals(TargetVersionSource.SETTINGS, falcon.source)
        assertEquals(TargetVersionSource.DOCKERFILE, detector.targetVersion(root(GOLDEN)).source)
    }

    fun testNothingPinnedIsUnknownAndGuessed() {
        val roots = listOf(root(FALCON), root(PLATFORM))
        val result = TargetVersionDetector.resolve(roots, { null }, { emptyList() })
        assertEquals(TargetVersion.UNKNOWN, result[root(FALCON).dir])
        assertNull(result.getValue(root(PLATFORM).dir).version)
        assertTrue(TargetVersion.UNKNOWN.guessed)
    }

    fun testMajorityTieGoesToTheHighestVersion() {
        val falcon = root(FALCON)
        val golden = root(GOLDEN)
        val platform = root(PLATFORM)
        val pins = mapOf(
            falcon.dir to listOf(TargetVersionDetector.Pin(falcon.dir, "Dockerfile", CoreVersion(2, 17, 0))),
            golden.dir to listOf(TargetVersionDetector.Pin(golden.dir, "Dockerfile", CoreVersion(2, 18, 8))),
        )
        val result = TargetVersionDetector.resolve(listOf(falcon, golden, platform), { null }, { pins[it.dir].orEmpty() })
        assertEquals(CoreVersion(2, 18, 8), result.getValue(platform.dir).version)
    }

    fun testDockerfileEditsInvalidateTheCache() {
        assertEquals(CoreVersion(2, 18, 8), detector.targetVersion(root(FALCON)).version)
        val dockerfile = myFixture.findFileInTempDir("$FALCON/docker/ansible-playbook/Dockerfile")!!
        WriteAction.runAndWait<Exception> {
            dockerfile.setBinaryContent("RUN pip install \"ansible-core==2.19.2\"\n".toByteArray())
        }
        assertEquals(CoreVersion(2, 19, 2), detector.targetVersion(root(FALCON)).version)
    }
}

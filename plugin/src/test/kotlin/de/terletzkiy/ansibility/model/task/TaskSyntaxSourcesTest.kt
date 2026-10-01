package de.terletzkiy.ansibility.model.task

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.context.TargetVersionSource
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.runtime.BundledLine
import de.terletzkiy.ansibility.runtime.DocSnapshotStore
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * Where the task model's syntax comes from: the snapshot parsed once by [DocSnapshotStore], and the root's target
 * version, whose changes ([TargetVersionDetector.modificationTracker]) rebuild cached task models.
 */
class TaskSyntaxSourcesTest : BasePlatformTestCase() {
    private val detector: TargetVersionDetector get() = TargetVersionDetector.getInstance(project)

    override fun tearDown() {
        try {
            detector.overrideFor = { null }
        } finally {
            super.tearDown()
        }
    }

    fun testSyntaxComesFromTheSharedSnapshotStore() {
        val service = TaskSyntaxService.getInstance()
        val store = DocSnapshotStore.getInstance()
        // A fresh service extracts from the store: the store keeps its snapshots softly (the GC may drop them between
        // tests), so the shared service's cached syntax says nothing about the store's cache at this point.
        val fresh = TaskSyntaxService()
        for (line in BundledLine.entries) {
            val syntax = fresh.forLine(line)
            val snapshot = store.snapshot(line)
            assertEquals("$line comes from the shared store's snapshot", snapshot.core, syntax.coreVersion)
            assertSame("extracted once per line", syntax, fresh.forLine(line))
            assertEquals(snapshot.core, service.forLine(line).coreVersion)
        }
        assertSame(service.forLine(BundledLine.PINNED), service.forVersion(CoreVersion.PINNED))
        assertSame(service.forLine(BundledLine.PINNED), service.forVersion(null))
        assertSame(service.forLine(BundledLine.LATEST), service.forVersion(CoreVersion(2, 19)))
        assertEquals("the line choice is the store's", DocSnapshotStore.lineFor(CoreVersion(2, 21, 4)), BundledLine.LATEST)
    }

    fun testTrackerFollowsOverridesAndStructure() {
        val tracker = detector.modificationTracker
        val start = tracker.modificationCount
        detector.overrideFor = { CoreVersion(2, 20) }
        val afterOverride = tracker.modificationCount
        assertTrue("a new override hook", afterOverride > start)
        AnsibleWorkspace.getInstance(project).refreshStructure()
        assertTrue("a structure change", tracker.modificationCount > afterOverride)
    }

    fun testTargetOverrideRebuildsTheCachedTaskModel() {
        myFixture.addFileToProject("site/ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("site/docker/ansible-playbook/Dockerfile", "RUN pip install ansible-core==2.18.8\n")
        myFixture.addFileToProject("site/playbook-a.yml", "---\n- name: Play\n  hosts: all\n  validate_argspec: main\n  tasks: []\n")
        ModelFixture.rescan(project)
        val file = ModelFixture.yaml(myFixture, "site/playbook-a.yml")
        val root = ModelFixture.root(myFixture, "site")
        assertEquals(TargetVersionSource.DOCKERFILE, detector.targetVersion(root).source)
        val pinned = TaskFileModels.of(file)
        assertEquals("2.18 has no validate_argspec", listOf("validate_argspec"), pinned.plays.single().unknownKeys.map { it.key.text })
        assertSame(pinned, TaskFileModels.of(file))

        detector.overrideFor = { if (it.dir == root.dir) CoreVersion(2, 21, 4) else null }
        val overridden = TaskFileModels.of(file)
        assertNotSame("the override invalidates the cached model without a file or structure change", pinned, overridden)
        assertEquals(emptyList<Any>(), overridden.plays.single().unknownKeys)
        assertTrue("validate_argspec" in overridden.plays.single().keywords)
    }
}

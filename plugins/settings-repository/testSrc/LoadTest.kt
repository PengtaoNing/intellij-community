// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.settingsRepository.test

import com.intellij.configurationStore.TestScheme
import com.intellij.configurationStore.save
import com.intellij.configurationStore.serialize
import com.intellij.util.toByteArray
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jgit.lib.Repository
import org.jetbrains.settingsRepository.ReadonlySource
import org.jetbrains.settingsRepository.SyncType
import org.jetbrains.settingsRepository.git.GitRepositoryManager
import org.jetbrains.settingsRepository.git.cloneBare
import org.jetbrains.settingsRepository.git.commit
import org.junit.Test

class LoadTest : LoadTestBase() {

  @Test fun `load scheme`() {
    val localScheme = TestScheme("local")
    provider.write("$dirName/local.xml", serialize(localScheme)!!.toByteArray())

    val schemeManager = createSchemeManager(dirName)
    schemeManager.loadSchemes()
    assertThat(schemeManager.allSchemes).containsOnly(localScheme)
    val actualLocalScheme = schemeManager.findSchemeByName("local")!!

    schemeManager.save()

    val dirPath = (icsManager.repositoryManager as GitRepositoryManager).repository.workTree.toPath().resolve(dirName)
    assertThat(dirPath).isDirectory()

    schemeManager.removeScheme(actualLocalScheme)
    schemeManager.save()

    assertThat(dirPath).doesNotExist()

    provider.write("$dirName/local1.xml", serialize(TestScheme("local1"))!!.toByteArray())
    provider.write("$dirName/local2.xml", serialize(TestScheme("local2"))!!.toByteArray())

    assertThat(dirPath.resolve("local1.xml")).isRegularFile()
    assertThat(dirPath.resolve("local2.xml")).isRegularFile()

    schemeManager.loadSchemes()
    schemeManager.removeScheme("local1")
    schemeManager.save()

    assertThat(dirPath.resolve("local1.xml")).doesNotExist()
    assertThat(dirPath.resolve("local2.xml")).isRegularFile()
  }

  @Test fun `load scheme with the same names`() {
    val localScheme = TestScheme("local")
    val data = serialize(localScheme)!!.toByteArray()
    provider.write("$dirName/local.xml", data)
    provider.write("$dirName/local2.xml", data)

    val schemeManager = createSchemeManager(dirName)
    schemeManager.loadSchemes()
    assertThat(schemeManager.allSchemes).containsOnly(localScheme)
  }

  @Test
  fun `load scheme from repo and read-only repo`() = runBlocking {
    val localScheme = TestScheme("local")

    provider.write("$dirName/local.xml", serialize(localScheme)!!.toByteArray())

    val remoteScheme = TestScheme("remote")
    tempDirManager.createRepository().use { remoteRepository ->
      remoteRepository
        .add("$dirName/Mac OS X from RubyMine.xml", serialize(remoteScheme)!!.toByteArray())
        .commit("add")

      remoteRepository.useAsReadOnlySource {
        val schemeManager = createSchemeManager(dirName)
        schemeManager.loadSchemes()
        assertThat(schemeManager.allSchemes).containsOnly(remoteScheme, localScheme)
        assertThat(schemeManager.isMetadataEditable(localScheme)).isTrue()
        assertThat(schemeManager.isMetadataEditable(remoteScheme)).isFalse()

        remoteRepository
          .delete("$dirName/Mac OS X from RubyMine.xml")
          .commit("delete")

        icsManager.sync(SyncType.MERGE)
        assertThat(schemeManager.allSchemes).containsOnly(localScheme)
      }
    }
  }

  @Test fun `scheme overrides read-only`() {
    val schemeName = "Emacs"
    val localScheme = TestScheme(schemeName, "local")

    provider.write("$dirName/$schemeName.xml", serialize(localScheme)!!.toByteArray())

    val remoteScheme = TestScheme(schemeName, "remote")
    tempDirManager.createRepository("remote").use { remoteRepository ->
      remoteRepository
        .add("$dirName/$schemeName.xml", serialize(remoteScheme)!!.toByteArray())
        .commit("")

      remoteRepository.useAsReadOnlySource {
        val schemeManager = createSchemeManager(dirName)
        schemeManager.loadSchemes()
        assertThat(schemeManager.allSchemes).containsOnly(localScheme)
        assertThat(schemeManager.isMetadataEditable(localScheme)).isFalse()
      }
    }

  }

  private inline fun Repository.useAsReadOnlySource(runnable: () -> Unit) {
    createAndRegisterReadOnlySource()
    try {
      runnable()
    }
    finally {
      icsManager.readOnlySourcesManager.setSources(emptyList())
    }
  }

  private fun Repository.createAndRegisterReadOnlySource(): ReadonlySource {
    val source = ReadonlySource(workTree.absolutePath)
    assertThat(cloneBare(source.url!!, icsManager.readOnlySourcesManager.rootDir.resolve(source.path!!)).objectDatabase.exists()).isTrue()
    icsManager.readOnlySourcesManager.setSources(listOf(source))
    return source
  }
}
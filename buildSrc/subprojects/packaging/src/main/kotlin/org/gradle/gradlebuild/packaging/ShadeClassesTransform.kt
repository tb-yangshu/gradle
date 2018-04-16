/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.gradlebuild.packaging

import com.google.gson.GsonBuilder
import org.gradle.api.artifacts.transform.ArtifactTransform
import java.io.File
import javax.inject.Inject


private
const val classTreeFileName = "classTree.json"


private
const val entryPointsFileName = "entryPoints.json"


private
const val relocatedClassesDirName = "classes"


private
const val manifestFileName = "MANIFEST.MF"


open class ShadeClassesTransform @Inject constructor(
    private val shadowPackage: String,
    private val keepPackages: Set<String>,
    private val unshadedPackages: Set<String>,
    private val ignorePackages: Set<String>
) : ArtifactTransform() {

    override fun transform(input: File): List<File> {
        val classesDir = outputDirectory.resolve(relocatedClassesDirName)
        classesDir.mkdir()
        val manifestFile = outputDirectory.resolve(manifestFileName)

        val classGraph = JarAnalyzer(shadowPackage, keepPackages, unshadedPackages, ignorePackages).analyze(input, classesDir, manifestFile)
        val gson = GsonBuilder().setPrettyPrinting().create()

        outputDirectory.resolve(classTreeFileName).bufferedWriter().use {
            gson.toJson(classGraph.getDependencies().toSortedMap(), it)
        }
        outputDirectory.resolve(entryPointsFileName).bufferedWriter().use {
            gson.toJson(classGraph.entryPoints.map { it.outputClassFilename }.toSortedSet(), it)
        }

        return listOf(outputDirectory)
    }
}


open class FindClassTrees : ArtifactTransform() {
    override fun transform(input: File): List<File> {
        return listOf(input.resolve(classTreeFileName))
    }
}


open class FindEntryPoints : ArtifactTransform() {
    override fun transform(input: File): List<File> {
        return listOf(input.resolve(entryPointsFileName))
    }
}


open class FindRelocatedClasses : ArtifactTransform() {
    override fun transform(input: File): List<File> {
        return listOf(input.resolve(relocatedClassesDirName))
    }
}


open class FindManifests : ArtifactTransform() {
    override fun transform(input: File): List<File> {
        val manifest = input.resolve(manifestFileName)
        return listOf(manifest).filter { it.exists() }
    }
}

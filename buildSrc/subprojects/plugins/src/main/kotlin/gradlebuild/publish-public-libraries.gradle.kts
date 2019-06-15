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
package gradlebuild

import accessors.base
import accessors.groovy
import accessors.java

import groovy.lang.MissingPropertyException
import org.gradle.plugins.publish.createArtifactPattern

plugins {
    maven
}

val publishImplementation by configurations.creating
val publishRuntime by configurations.creating

// Subprojects assign dependencies to publishCompile to indicate that they should be part of the published pom.
// Therefore implementation needs to contain those dependencies and extend publishImplementation
configurations.named("implementation") {
    extendsFrom(publishImplementation)
}

val main by java.sourceSets
val sourceJar = tasks.register("sourceJar", Jar::class.java) {
    archiveClassifier.set("sources")
    from(main.java.srcDirs + main.groovy.srcDirs)
}

artifacts {

    fun publishRuntime(artifact: Any) =
        add(publishRuntime.name, artifact)

    publishRuntime(tasks.named("jar").get()) // TODO: LazyPublishArtifact has custom provider unpacking, see https://github.com/gradle/gradle-native/issues/719
    publishRuntime(sourceJar.get()) // TODO: LazyPublishArtifact has custom provider unpacking, see https://github.com/gradle/gradle-native/issues/719
}

// TODO: Make this lazy, see https://github.com/gradle/gradle-native/issues/718
tasks.getByName<Upload>("uploadArchives") {
    // TODO Add magic property to upcoming configuration interface
    onlyIf { !project.hasProperty("noUpload") }
    configuration = publishRuntime
    isUploadDescriptor = true

    // TODO Remove once task configuration on demand is available and we can enforce properties at task configuration time
    failEarlyIfCredentialsAreNotSet(this)

    repositories {
        ivy {
            artifactPattern(createArtifactPattern(rootProject.extra["isSnapshot"] as Boolean, project.group.toString(), base.archivesBaseName))
            credentials {
                username = artifactoryUserName
                password = artifactoryUserPassword
            }
        }
    }
}


afterEvaluate {
    maven.conf2ScopeMappings.apply {
        mappings.clear()
        addMapping(300, publishRuntime, Conf2ScopeMappingContainer.RUNTIME)
    }
    dependencies {
        publishImplementation.allDependencies.withType<ProjectDependency>().forEach {
            publishRuntime("org.gradle:${it.dependencyProject.base.archivesBaseName}:$version")
        }
        publishImplementation.allDependencies.withType<ExternalDependency>().forEach {
            publishRuntime(it)
        }
    }
}


fun Project.failEarlyIfCredentialsAreNotSet(upload: Upload) {
    gradle.taskGraph.whenReady({
        if (hasTask(upload)) {
            if (artifactoryUserName.isNullOrEmpty()) {
                throw MissingPropertyException("artifactoryUserName is not set!")
            }
            if (artifactoryUserPassword.isNullOrEmpty()) {
                throw MissingPropertyException("artifactoryUserPassword is not set!")
            }
        }
    })
}

// TODO Add magic property to upcoming configuration interface
val Project.artifactoryUserName
    get() = findProperty("artifactoryUserName") as String?

// TODO Add magic property to upcoming configuration interface
val Project.artifactoryUserPassword
    get() = findProperty("artifactoryUserPassword") as String?



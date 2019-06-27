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

package org.gradle.integtests.resolve.transform

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import spock.lang.Issue
import spock.lang.Unroll

class DisambiguateArtifactTransformIntegrationTest extends AbstractHttpDependencyResolutionTest {

    def "disambiguates A -> B -> C and B -> C by selecting the later"() {
        def m1 = mavenRepo.module("test", "test", "1.3").publish()
        m1.artifactFile.text = "1234"

        given:
        settingsFile << """
            rootProject.name = 'root'
            include 'lib'
            include 'app'
        """

        file('lib/src/main/java/test/MyClass.java') << """
package test;

public class MyClass {
    public static void main(String[] args) {
        System.out.println("Hello world!");
    }
}
"""

        buildFile << """
def artifactType = Attribute.of('artifactType', String)

allprojects {
    repositories {
        maven { url "${mavenRepo.uri}" }
    }
}
project(':lib') {
    apply plugin: 'java-library'
}

project(':app') {
    apply plugin: 'java'

    dependencies {
        implementation 'test:test:1.3'
        implementation project(':lib')
    }

    dependencies {
        registerTransform {
            from.attribute(artifactType, 'java-classes-directory')
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API))
            from.attribute(Format.FORMAT_ATTRIBUTE, objects.named(Format, Format.CLASSES))
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, 'size'))
            artifactTransform(FileSizer)
        }
        registerTransform {
            from.attribute(artifactType, 'jar')
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API))
            from.attribute(Format.FORMAT_ATTRIBUTE, objects.named(Format, Format.JAR))
            to.attribute(artifactType, 'java-classes-directory')
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_API))
            to.attribute(Format.FORMAT_ATTRIBUTE, objects.named(Format, Format.CLASSES))
            artifactTransform(FileSizer)
        }
    }

    task resolve(type: Copy) {
        def artifacts = configurations.compileClasspath.incoming.artifactView {
            attributes { it.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, 'size')) }
            if (project.hasProperty("lenient")) {
                lenient(true)
            }
        }.artifacts
        from artifacts.artifactFiles
        into "\${buildDir}/libs"
        doLast {
            println "files: " + artifacts.collect { it.file.name }
            println "ids: " + artifacts.collect { it.id }
            println "components: " + artifacts.collect { it.id.componentIdentifier }
            println "variants: " + artifacts.collect { it.variant.attributes }
        }
    }
}

class FileSizer extends ArtifactTransform {
    FileSizer() {
        println "Creating FileSizer"
    }
    
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name + ".txt")
        println "Transforming \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

"""



        when:
        run "resolve"

        then:
        output.count("Transforming") == 3
        output.count("Transforming main to main.txt") == 1
        output.count("Transforming test-1.3.jar to test-1.3.jar.txt") == 1
        output.count("Transforming test-1.3.jar.txt to test-1.3.jar.txt.txt") == 1
    }

    def "disambiguates A -> B -> C and D -> C by selecting the later iff attributes match"() {
        def m1 = mavenRepo.module("test", "test", "1.3").publish()
        m1.artifactFile.text = "1234"

        given:
        settingsFile << """
            rootProject.name = 'root'
            include 'lib'
            include 'app'
        """

        file('lib/src/main/java/test/MyClass.java') << """
package test;

public class MyClass {
    public static void main(String[] args) {
        System.out.println("Hello world!");
    }
}
"""

        buildFile << """
def artifactType = Attribute.of('artifactType', String)
def extraAttribute = Attribute.of('extra', String)

allprojects {
    repositories {
        maven { url "${mavenRepo.uri}" }
    }
}
project(':lib') {
    apply plugin: 'java-library'
}

project(':app') {
    apply plugin: 'java'

    dependencies {
        implementation 'test:test:1.3'
        implementation project(':lib')
    }

    dependencies {
        registerTransform {
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            from.attribute(artifactType, 'java-classes-directory')
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            to.attribute(artifactType, 'final')
            
            if (project.hasProperty('extraAttribute')) {
                from.attribute(extraAttribute, 'whatever')
                to.attribute(extraAttribute, 'value1')
            }
            
            artifactTransform(TestTransform)
        }
        registerTransform {
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            from.attribute(Format.FORMAT_ATTRIBUTE, objects.named(Format, Format.JAR))
            from.attribute(artifactType, 'jar')
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            to.attribute(Format.FORMAT_ATTRIBUTE, objects.named(Format, Format.CLASSES))
            to.attribute(artifactType, 'magic-jar')

            if (project.hasProperty('extraAttribute')) {
                from.attribute(extraAttribute, 'whatever')
                to.attribute(extraAttribute, 'value2')
            }
            
            artifactTransform(TestTransform)
        }
        registerTransform {
            from.attribute(artifactType, 'magic-jar')
            to.attribute(artifactType, 'final')
            artifactTransform(TestTransform)
        }
    }

    task resolve(type: Copy) {
        def artifacts = configurations.compileClasspath.incoming.artifactView {
            attributes { it.attribute(artifactType, 'final') }
            if (project.hasProperty("lenient")) {
                lenient(true)
            }
        }.artifacts
        from artifacts.artifactFiles
        into "\${buildDir}/libs"
        doLast {
            println "files: " + artifacts.collect { it.file.name }
            println "ids: " + artifacts.collect { it.id }
            println "components: " + artifacts.collect { it.id.componentIdentifier }
            println "variants: " + artifacts.collect { it.variant.attributes }
        }
    }
}

class TestTransform extends ArtifactTransform {
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name + ".txt")
        println "Transforming \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

"""



        when:
        run "resolve"

        then:
        output.count("Transforming") == 3
        output.count("Transforming main to main.txt") == 1
        output.count("Transforming test-1.3.jar to test-1.3.jar.txt") == 1
        output.count("Transforming test-1.3.jar.txt to test-1.3.jar.txt.txt") == 1

        when:
        fails 'resolve', '-PextraAttribute'

        then:
        failureCauseContains('Found multiple transforms')
    }

    def "transform with two attributes will not confuse"() {
        given:
        buildFile << """
class AarRelease extends ArtifactTransform {
    AarRelease() {
        println "Creating AarRelease"
    }
    
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name.replace(".jar",".release.aar"))
        println "Transforming \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

class AarDebug extends ArtifactTransform {
    AarDebug() {
        println "Creating AarDebug"
    }
    
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name.replace(".jar",".debug.aar"))
        println "Transforming \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

class AarClasses extends ArtifactTransform {
    AarClasses() {
        println "Creating AarClasses"
    }
    
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name.replace(".aar",".classes"))
        println "Transforming \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
} 


def buildType = Attribute.of('buildType', String)
def artifactType = Attribute.of('artifactType', String)

projectDir.mkdirs()
def jar1 = file('lib1.jar')
jar1.text = 'some text'

configurations {
    compile
}

dependencies {
    attributesSchema {
        attribute(buildType)
    }

    compile files(jar1)

    registerTransform {
        from.attribute(artifactType, 'jar')
        to.attribute(artifactType, 'aar')

        from.attribute(buildType, 'default')
        to.attribute(buildType, 'release')

        artifactTransform(AarRelease)
    }
    
    registerTransform {
        from.attribute(artifactType, 'jar')
        to.attribute(artifactType, 'aar')

        from.attribute(buildType, 'default')
        to.attribute(buildType, 'debug')

        artifactTransform(AarDebug)
    }
    
    registerTransform {
        from.attribute(artifactType, 'aar')
        to.attribute(artifactType, 'classes')

        artifactTransform(AarClasses)
    }
}

task resolveReleaseClasses {
    def artifacts = configurations.compile.incoming.artifactView {
        attributes { 
            it.attribute(artifactType, 'classes') 
            it.attribute(buildType, 'release') 
        }
    }.artifacts
    inputs.files artifacts.artifactFiles
    doLast {
        println "files: " + artifacts.collect { it.file.name }
        println "ids: " + artifacts.collect { it.id }
        println "components: " + artifacts.collect { it.id.componentIdentifier }
        println "variants: " + artifacts.collect { it.variant.attributes }
        println "content: " + artifacts.collect { it.file.text }
    }
}


task resolveTestClasses {
    def artifacts = configurations.compile.incoming.artifactView {
        attributes { 
            it.attribute(artifactType, 'classes') 
            it.attribute(buildType, 'test') 
        }
    }.artifacts
    inputs.files artifacts.artifactFiles
    doLast {
        println "files: " + artifacts.collect { it.file.name }
        println "ids: " + artifacts.collect { it.id }
        println "components: " + artifacts.collect { it.id.componentIdentifier }
        println "variants: " + artifacts.collect { it.variant.attributes }
        println "content: " + artifacts.collect { it.file.text }
    }
}
        """

        when:
        run "resolveReleaseClasses"

        then:
        outputContains("files: [lib1.release.classes]")
        outputContains("ids: [lib1.release.classes (lib1.jar)]")
        outputContains("components: [lib1.jar]")
        outputContains("variants: [{artifactType=classes, buildType=release}]")
        outputContains("content: [1]")

        and:
        output.count("Transforming") == 2

        when:
        run "resolveTestClasses"

        then:
        outputContains("files: []")
        outputContains("ids: []")
        outputContains("components: []")
        outputContains("variants: []")
        outputContains("content: []")

        and:
        output.count("Transforming") == 0
    }

    @Issue("gradle/gradle#8363")
    def "attribute compatible transform but insufficient will not be selected"() {
        def m1 = mavenRepo.module("test", "test", "1.3").publish()
        m1.artifactFile.text = "1234"

        given:
        buildFile << """
def artifactType = Attribute.of('artifactType', String)
def minified = Attribute.of('minified', Boolean)

apply plugin: 'java'

allprojects {
    repositories {
        maven { url "${mavenRepo.uri}" }
    }
}

dependencies {
    implementation 'test:test:1.3'
    
    artifactTypes.getByName("jar") {
        attributes.attribute(minified, false)
    }
    
    registerTransform {
        from.attribute(artifactType, 'jar')
        to.attribute(artifactType, 'size')
        artifactTransform(FileSizer)
    }
    
    registerTransform {
        from.attribute(minified, false)
        to.attribute(minified, true)
        artifactTransform(Minifier)
    }
}

class Minifier extends ArtifactTransform {
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name + ".min")
        println "Minifying \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

class FileSizer extends ArtifactTransform {
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name + ".txt")
        println "Sizing \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

task resolve(type: Copy) {
    def artifacts = configurations.compileClasspath.incoming.artifactView {
        attributes { it.attribute(minified, true) }
    }.artifacts
    from artifacts.artifactFiles
    into "\${buildDir}/libs"
    doLast {
        println "files: " + artifacts.collect { it.file.name }
        println "ids: " + artifacts.collect { it.id }
        println "components: " + artifacts.collect { it.id.componentIdentifier }
        println "variants: " + artifacts.collect { it.variant.attributes }
    }
}
"""
        when:
        succeeds "resolve"

        then:
        output.count("Minifying") == 1
        output.count('minified=true')
        output.count('Sizing') == 0
    }

    @Unroll
    def "disambiguation leverages schema rules before doing it size based"() {
        given:
        settingsFile << """
include('child')
"""
        buildFile << """
def artifactType = Attribute.of('artifactType', String)

apply plugin: 'java-library'

allprojects {
    repositories {
        maven { url "${mavenRepo.uri}" }
    }
}

project(':child') {
    configurations {
        runtimeOnly {
            canBeConsumed = false
            canBeResolved = false
        }
        runtimeElements {
            extendsFrom(runtimeOnly)
            canBeConsumed = true
            canBeResolved = false
            attributes {
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.EXTERNAL))
            }
        }
    }
    
    
    artifacts {
        buildDir.mkdirs()
        file("\$buildDir/test.jar").text = "toto"
        runtimeOnly file("\$buildDir/test.jar")
    }
}

dependencies {
    api project(':child')
    
    artifactTypes.getByName("jar") {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, "weird"))
    }
    
    if ($apiFirst) {
        registerTransform {
            from.attribute(artifactType, 'jar')
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, "weird"))
            to.attribute(artifactType, 'jar')
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            artifactTransform(Identity)
        }
    }
    registerTransform {
        from.attribute(artifactType, 'jar')
        from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, "weird"))
        to.attribute(artifactType, 'jar')
        to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
        artifactTransform(IllegalTransform)
    }
    if (!$apiFirst) {
        registerTransform {
            from.attribute(artifactType, 'jar')
            from.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, "weird"))
            to.attribute(artifactType, 'jar')
            to.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_API))
            artifactTransform(Identity)
        }
    }
    registerTransform {
        from.attribute(artifactType, 'jar')
        to.attribute(artifactType, 'size')
        artifactTransform(FileSizer)
    }
}

class Identity extends ArtifactTransform {
    List<File> transform(File input) {
        return [input]
    }
}

class IllegalTransform extends ArtifactTransform {
    List<File> transform(File input) {
        throw new IllegalStateException("IllegalTransform should not be invoked")
    }
}

class FileSizer extends ArtifactTransform {
    List<File> transform(File input) {
        assert outputDirectory.directory && outputDirectory.list().length == 0
        def output = new File(outputDirectory, input.name + ".txt")
        println "Sizing \${input.name} to \${output.name}"
        output.text = String.valueOf(input.length())
        return [output]
    }
}

task resolve(type: Copy) {
    def artifacts = configurations.compileClasspath.incoming.artifactView {
        attributes { it.attribute(artifactType, 'size') }
    }.artifacts
    from artifacts.artifactFiles
    into "\${buildDir}/libs"
    doLast {
        println "files: " + artifacts.collect { it.file.name }
        println "ids: " + artifacts.collect { it.id }
        println "components: " + artifacts.collect { it.id.componentIdentifier }
        println "variants: " + artifacts.collect { it.variant.attributes }
    }
}
"""
        when:
        succeeds "resolve"

        then:
        output.contains('variants: [{artifactType=size, org.gradle.dependency.bundling=external, org.gradle.format=jar, org.gradle.usage=java-api}]')

        where:
        apiFirst << [true, false]
    }
}

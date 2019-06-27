/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.java

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.resolve.ResolveTestFixture
import org.gradle.test.fixtures.server.http.MavenHttpModule
import spock.lang.Unroll

class JavaLibraryPublishedTargetJvmVersionIntegrationTest extends AbstractHttpDependencyResolutionTest {
    ResolveTestFixture resolve
    MavenHttpModule module

    def setup() {
        settingsFile << """
            rootProject.name = 'test'
        """
        buildFile << """
            apply plugin: 'java-library'
            
            repositories {
                maven { url '${mavenHttpRepo.uri}' }
            }
            
            dependencies {
                api 'org:producer:1.0'
            }
        """

        resolve = new ResolveTestFixture(buildFile, 'compileClasspath')
        resolve.prepare()

        module = mavenHttpRepo.module('org', 'producer', '1.0')
                .withModuleMetadata()
                .withGradleMetadataRedirection()
                .adhocVariants()
                .variant("apiElementsJdk6", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 6,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-api'], { artifact('producer-1.0-jdk6.jar') })
                .variant("apiElementsJdk7", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 7,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-api'], { artifact('producer-1.0-jdk7.jar') })
                .variant("apiElementsJdk9", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 9,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-api'], { artifact('producer-1.0-jdk9.jar') })
                .variant("runtimeElementsJdk6", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 6,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-runtime'], { artifact('producer-1.0-jdk6.jar') })
                .variant("runtimeElementsJdk7", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 7,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-runtime'], { artifact('producer-1.0-jdk7.jar') })
                .variant("runtimeElementsJdk9", [
                'org.gradle.dependency.bundling': 'external',
                'org.gradle.jvm.version': 9,
                'org.gradle.format': 'jar',
                'org.gradle.usage': 'java-runtime'], { artifact('producer-1.0-jdk9.jar') })
                .publish()

    }

    def "can fail resolution if producer doesn't have appropriate target version"() {
        buildFile << """
            configurations.compileClasspath.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 5)
        """

        when:
        module.pom.expectGet()
        module.moduleMetadata.expectGet()

        fails ':checkDeps'

        then:
        failure.assertHasCause('''Unable to find a matching variant of org:producer:1.0:
  - Variant 'apiElementsJdk6' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '6'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-api'.
  - Variant 'apiElementsJdk7' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '7'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-api'.
  - Variant 'apiElementsJdk9' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '9'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-api'.
  - Variant 'runtimeElementsJdk6' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '6'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-runtime'.
  - Variant 'runtimeElementsJdk7' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '7'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-runtime'.
  - Variant 'runtimeElementsJdk9' capability org:producer:1.0:
      - Incompatible attribute:
          - Required org.gradle.jvm.version '5' and found incompatible value '9'.
      - Other attributes:
          - Required org.gradle.dependency.bundling 'external' and found compatible value 'external'.
          - Required org.gradle.format 'classes' and found compatible value 'jar'.
          - Found org.gradle.status 'release' but wasn't required.
          - Required org.gradle.usage 'java-api' and found compatible value 'java-runtime'.''')
    }

    @Unroll
    def "can select the most appropriate producer variant (#expected) based on target compatibility (#requested)"() {
        buildFile << """
            configurations.compileClasspath.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, $requested)
        """

        when:
        module.pom.expectGet()
        module.moduleMetadata.expectGet()
        module.getArtifact(classifier: "jdk${selected}").expectGet()

        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org:producer:1.0') {
                    variant(expected, [
                            'org.gradle.dependency.bundling': 'external',
                            'org.gradle.jvm.version': selected,
                            'org.gradle.usage': 'java-api',
                            'org.gradle.format': 'jar',
                            'org.gradle.status': 'release'
                    ])
                    artifact(classifier: "jdk${selected}")
                }
            }
        }

        where:
        requested | selected
        6         | 6
        7         | 7
        8         | 7
        9         | 9
        10        | 9

        expected = "apiElementsJdk$selected"
    }
}

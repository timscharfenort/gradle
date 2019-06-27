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

package org.gradle.java.fixtures

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.FeaturePreviewsFixture
import org.gradle.integtests.fixtures.resolve.ResolveTestFixture
import org.gradle.test.fixtures.GradleModuleMetadata
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.maven.MavenPom

abstract class AbstractJavaTestFixturesIntegrationTest extends AbstractIntegrationSpec {
    abstract String getPluginName()

    def setup() {
        settingsFile << """
            rootProject.name = 'root'
        """

        buildFile << """
            allprojects {
                apply plugin: '${pluginName}'

                ${mavenCentralRepository()}
                
                dependencies { testImplementation 'junit:junit:4.12' }
            }
        """
    }

    def "can compile test fixtures"() {
        buildFile << """
            apply plugin: 'java-test-fixtures'
        """
        addPersonDomainClass()
        addPersonTestFixture()
        addPersonTestUsingTestFixtures()

        when:
        succeeds 'compileTestJava'

        then:
        def skippedJars = pluginName == 'java' ? [':testFixturesJar'] : [':jar', ':testFixturesJar']
        def producedJars = pluginName == 'java' ? [':jar'] : []
        executedAndNotSkipped(
            ":compileJava",
            ":compileTestFixturesJava",
            ":compileTestJava",
            *producedJars
        )
        notExecuted(*skippedJars)

        when:
        succeeds "test"

        then:
        def expectedJars = [':jar', ':testFixturesJar'] - producedJars
        executedAndNotSkipped(*expectedJars)
    }

    def "test fixtures can use their own dependencies"() {
        buildFile << """
            apply plugin: 'java-test-fixtures'
        
            dependencies {
                testFixturesImplementation 'org.apache.commons:commons-lang3:3.9'
            }
        """
        addPersonDomainClass()
        addPersonTestFixtureUsingApacheCommons()
        addPersonTestUsingTestFixtures()

        when:
        succeeds 'compileTestJava'

        then:
        def skippedJars = pluginName == 'java' ? [':testFixturesJar'] : [':jar', ':testFixturesJar']
        def producedJars = pluginName == 'java' ? [':jar'] : []
        executedAndNotSkipped(
            ":compileJava",
            ":compileTestFixturesJava",
            ":compileTestJava",
            *producedJars
        )
        notExecuted(*skippedJars)

        when:
        succeeds "test"

        then:
        def expectedJars = [':jar', ':testFixturesJar'] - producedJars
        executedAndNotSkipped(*expectedJars)
    }

    def "test fixtures implementation dependencies do not leak into the test compile classpath"() {
        buildFile << """
            apply plugin: 'java-test-fixtures'
        
            dependencies {
                testFixturesImplementation 'org.apache.commons:commons-lang3:3.9'
            }
        """
        addPersonDomainClass()
        addPersonTestFixtureUsingApacheCommons()
        addPersonTestUsingTestFixtures()
        file("src/test/java/org/Leaking.java") << """
            package org;
            import org.apache.commons.lang3.StringUtils;
            
            public class Leaking {
            }
        """
        when:
        fails 'compileTestJava'

        then:
        failure.assertHasCause("Compilation failed")
        errorOutput.contains("package org.apache.commons.lang3 does not exist")
    }

    def "test fixtures api dependencies are visible on the test compile classpath"() {
        buildFile << """
            apply plugin: 'java-test-fixtures'
        
            dependencies {
                testFixturesApi 'org.apache.commons:commons-lang3:3.9'
            }
        """
        addPersonDomainClass()
        addPersonTestFixtureUsingApacheCommons()
        addPersonTestUsingTestFixtures()
        file("src/test/java/org/Leaking.java") << """
            package org;
            import org.apache.commons.lang3.StringUtils;
            
            public class Leaking {
            }
        """

        expect:
        succeeds 'compileTestJava'
    }

    def "can consume test fixtures of subproject"() {
        settingsFile << """
            include 'sub'
        """
        file("sub/build.gradle") << """
            apply plugin: 'java-test-fixtures'
        """
        buildFile << """
            dependencies {
                testImplementation(testFixtures(project(":sub")))
            }           
        """
        addPersonDomainClass("sub")
        addPersonTestFixture("sub")
        // the test will live in the current project, instead of "sub"
        // which demonstrates that the test fixtures are exposed
        addPersonTestUsingTestFixtures()

        when:
        succeeds ':compileTestJava'

        then:
        executedAndNotSkipped(
            ":sub:compileTestFixturesJava"
        )
    }

    def "changing coordinates of subproject doesn't break consumption of fixtures"() {
        settingsFile << """
            include 'sub'
        """
        file("sub/build.gradle") << """
            apply plugin: 'java-test-fixtures'
            
            group = 'other' // this is applied _after_ the dependency is created
        """
        buildFile << """
            dependencies {
                testImplementation(testFixtures(project(":sub")))
            }           
        """
        addPersonDomainClass("sub")
        addPersonTestFixture("sub")
        // the test will live in the current project, instead of "sub"
        // which demonstrates that the test fixtures are exposed
        addPersonTestUsingTestFixtures()

        when:
        succeeds ':compileTestJava'

        then:
        executedAndNotSkipped(
            ":sub:compileTestFixturesJava"
        )
    }

    def "can publish test fixtures"() {
        FeaturePreviewsFixture.enableGradleMetadata(settingsFile)

        buildFile << """
            apply plugin: 'maven-publish'
            apply plugin: 'java-test-fixtures'

            dependencies {
                testFixturesImplementation 'org.apache.commons:commons-lang3:3.9'
            }

            publishing {
                repositories {
                    maven {
                        url "\${buildDir}/repo"
                    }
                    publications {
                        maven(MavenPublication) {
                            from components.java
                        }
                    }
                }
            }

            group = 'com.acme'
            version = '1.3'
        """
        addPersonDomainClass()
        addPersonTestFixtureUsingApacheCommons()
        addPersonTestUsingTestFixtures()

        when:
        succeeds 'publish'

        then: "a test fixtures jar is published"
        file("build/repo/com/acme/root/1.3/root-1.3-test-fixtures.jar").exists()

        and: "appears as optional dependency in Maven POM"
        MavenPom pom = new MavenPom(file("build/repo/com/acme/root/1.3/root-1.3.pom"))
        pom.scope("compile") {
            assertOptionalDependencies(
                "com.acme:root:1.3",
                "org.apache.commons:commons-lang3:3.9"
            )
        }

        and: "appears as a variant in Gradle Module metadata"
        GradleModuleMetadata gmm = new GradleModuleMetadata(file("build/repo/com/acme/root/1.3/root-1.3.module"))
        gmm.variant("testFixturesApiElements") {
            dependency("com.acme:root:1.3")
            noMoreDependencies()
        }
        gmm.variant("testFixturesRuntimeElements") {
            dependency("com.acme:root:1.3")
            dependency("org.apache.commons:commons-lang3:3.9")
            noMoreDependencies()
        }
    }

    def "can consume test fixtures of an external module"() {
        mavenRepo.module("com.acme", "external-module", "1.3")
            .variant("testFixturesApiElements", ['org.gradle.usage': 'java-api', 'org.gradle.format': 'jar']) {
                capability('com.acme', 'external-module-test-fixtures', '1.3')
                dependsOn("com.acme:external-module:1.3")
                artifact("external-module-1.3-test-fixtures.jar")
            }
            .variant("testFixturesRuntimeElements", ['org.gradle.usage': 'java-runtime', 'org.gradle.format': 'jar']) {
                capability('com.acme', 'external-module-test-fixtures', '1.3')
                dependsOn("com.acme:external-module:1.3")
                dependsOn("org.apache.commons:commons-lang3:3.9")
                artifact("external-module-1.3-test-fixtures.jar")
            }
            .withGradleMetadataRedirection()
            .withModuleMetadata()
            .publish()
        buildFile << """
            dependencies {
                testImplementation(testFixtures('com.acme:external-module:1.3'))
            }
            repositories {
                maven {
                    url "${mavenRepo.uri}"
                }
            }           
        """
        when:
        def resolve = new ResolveTestFixture(buildFile, "testCompileClasspath")
        resolve.prepare()
        succeeds ':checkdeps'

        then:
        resolve.expectGraph {
            root(":", ":root:unspecified") {
                module('junit:junit:4.12') {
                    configuration = 'compile' // external POM
                    module("org.hamcrest:hamcrest-core:1.3")
                }
                module('com.acme:external-module:1.3') {
                    variant("testFixturesApiElements", [
                        'org.gradle.status': 'release', 'org.gradle.usage': 'java-api', 'org.gradle.format': 'jar'
                    ])
                    firstLevelConfigurations = ['testFixturesApiElements']
                    module('com.acme:external-module:1.3') {
                        variant("api", ['org.gradle.status': 'release', 'org.gradle.usage': 'java-api', 'org.gradle.format': 'jar'])
                        artifact(name: 'external-module', version:'1.3')
                    }
                    artifact(name: 'external-module', version:'1.3', classifier:'test-fixtures')
                }
            }
        }

        when:
        resolve = new ResolveTestFixture(buildFile, "testRuntimeClasspath")
        resolve.prepare()
        succeeds ':checkdeps'

        then:
        resolve.expectGraph {
            root(":", ":root:unspecified") {
                module('junit:junit:4.12') {
                    configuration = 'runtime' // external POM
                    module("org.hamcrest:hamcrest-core:1.3")
                }
                module('com.acme:external-module:1.3') {
                    variant("testFixturesRuntimeElements", [
                        'org.gradle.status': 'release', 'org.gradle.usage': 'java-runtime', 'org.gradle.format': 'jar'
                    ])
                    firstLevelConfigurations = ['testFixturesRuntimeElements']
                    module('com.acme:external-module:1.3') {
                        variant("runtime", ['org.gradle.status': 'release', 'org.gradle.usage': 'java-runtime', 'org.gradle.format': 'jar'])
                        artifact(name: 'external-module', version:'1.3')
                    }
                    module("org.apache.commons:commons-lang3:3.9") {
                        configuration = 'runtime' // external POM
                    }
                    artifact(name: 'external-module', version:'1.3', classifier:'test-fixtures')
                }
            }
        }
    }

    protected TestFile addPersonTestUsingTestFixtures(String subproject = "") {
        file("${subproject ? "${subproject}/" : ""}src/test/java/org/PersonTest.java") << """
            import org.PersonFixture;
            import org.Person;
            import org.junit.Test;
            import static org.junit.Assert.*;
            
            public class PersonTest {
                @Test
                public void testAny() {
                    Person anyone = PersonFixture.anyone();
                    assertEquals("John", anyone.getFirstName());
                    assertEquals("Doe", anyone.getLastName());
                }
            }
        """
    }

    protected TestFile addPersonDomainClass(String subproject = "", String lang = 'java') {
        file("${subproject ? "${subproject}/" : ""}src/main/$lang/org/Person.$lang") << """
            package org;
            
            public class Person {
                private final String firstName;
                private final String lastName;
                
                public Person(String first, String last) {
                    this.firstName = first;
                    this.lastName = last;
                }
                
                public String getFirstName() {
                    return firstName;
                }
                
                public String getLastName() {
                    return lastName;
                }
            }
        """
    }

    protected TestFile addPersonTestFixture(String subproject = "", String lang="java") {
        file("${subproject ? "${subproject}/" : ""}src/testFixtures/$lang/org/PersonFixture.$lang") << """
            package org;
            
            public class PersonFixture {
                public static Person anyone() {
                    return new Person("John", "Doe");
                }
            }
        """
    }

    protected TestFile addPersonTestFixtureUsingApacheCommons(String subproject = "") {
        file("${subproject ? "${subproject}/" : ""}src/testFixtures/java/org/PersonFixture.java") << """
            package org;
            import org.apache.commons.lang3.StringUtils;
            
            public class PersonFixture {
                public static Person anyone() {
                    return new Person(StringUtils.capitalize("john"), StringUtils.capitalize("doe"));
                }
            }
        """
    }

}

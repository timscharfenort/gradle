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


import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Unroll

class JavaLibraryFeatureCompilationIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        settingsFile << """
            rootProject.name = "test"
        """
        buildFile << """
            allprojects {
                group = 'org.gradle.test'
                version = '1.0'
            }
        """
    }

    private toggleCompileClasspathPackaging(boolean activate) {
        if (activate) {
            propertiesFile << """
                systemProp.org.gradle.java.compile-classpath-packaging=true
            """.trim()
        }
    }

    @Unroll
    def "project can declare and compile feature [configuration=#configuration][compileClasspathPackaging=#compileClasspathPackaging]"() {
        toggleCompileClasspathPackaging(compileClasspathPackaging)
        settingsFile << """
            include 'b'
        """
        given:
        buildFile << """
            apply plugin: 'java-library'
            
            java {
                registerFeature("myFeature") {
                    usingSourceSet(sourceSets.main)
                }
            }
            
            dependencies {
                $configuration project(":b")
            }
        """
        file("b/build.gradle") << """
            apply plugin: 'java-library'
        """
        file("b/src/main/java/com/foo/Foo.java") << """
            package com.foo;
            public class Foo {
                public void foo() {
                }
            }
        """
        file("src/main/java/com/bar/Bar.java") << """
            package com.bar;
            import com.foo.Foo;
            
            public class Bar {
                public void bar() {
                    Foo foo = new Foo();
                    foo.foo();
                }
            }
        """

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':b:compileJava'
        packagingTasks(compileClasspathPackaging, 'b')

        where:
        compileClasspathPackaging | configuration
        false                     | "myFeatureApi"
        true                      | "myFeatureApi"
        false                     | "myFeatureImplementation"
        true                      | "myFeatureImplementation"
    }

    @Unroll
    def "Java Library can depend on feature of component [compileClasspathPackaging=#compileClasspathPackaging]"() {
        toggleCompileClasspathPackaging(compileClasspathPackaging)
        settingsFile << """
            include 'b', 'c', 'd'
        """
        given:
        file("b/build.gradle") << """
            apply plugin: 'java-library'
            
            java {
                registerFeature("myFeature") {
                    usingSourceSet(sourceSets.main)
                }
            }
            
            dependencies {
                myFeatureApi project(":c")
                myFeatureImplementation project(":d")
            }
            
        """
        buildFile << """
            apply plugin: 'java-library'
            
            dependencies {
                implementation(project(":b")) {
                    capabilities {
                        requireCapability("org.gradle.test:b-my-feature")
                    }
                }
            }
            
            task verifyClasspath {
                dependsOn(configurations.compileClasspath)
                dependsOn(configurations.runtimeClasspath)
                doLast {
                    assert configurations.compileClasspath.incoming.resolutionResult.allDependencies.collect {
                        it.toString()
                    } as Set == ['project :b', 'project :c'] as Set // only API dependencies
                    assert configurations.runtimeClasspath.incoming.resolutionResult.allDependencies.collect {
                        it.toString()
                    } as Set == ['project :b', 'project :c', 'project :d'] as Set // all dependencies
                }
            }
        """
        ['c', 'd'].each {
            file("$it/build.gradle") << """
            apply plugin: 'java-library'
        """
            file("$it/src/main/java/com/baz/Baz${it}.java") << """
            package com.baz;
            public class Baz${it} {}
        """
        }

        file("b/src/main/java/com/foo/Foo.java") << """
            package com.foo;
            public class Foo {
                public void foo() {
                }
            }
        """
        file("src/main/java/com/bar/Bar.java") << """
            package com.bar;
            import com.foo.Foo;
            
            public class Bar {
                public void bar() {
                    Foo foo = new Foo();
                    foo.foo();
                }
            }
        """

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':b:compileJava', ':c:compileJava', ':d:compileJava'
        packagingTasks(compileClasspathPackaging, 'b')
        packagingTasks(compileClasspathPackaging, 'c')
        packagingTasks(compileClasspathPackaging, 'd')

        when:
        succeeds 'clean', ':verifyClasspath'

        then:
        executedAndNotSkipped ':b:jar', ':c:jar', ':d:jar'

        where:
        compileClasspathPackaging | _
        false                     | _
        true                      | _

    }

    @Unroll
    def "main component doesn't expose dependencies from feature [compileClasspathPackaging=#compileClasspathPackaging]"() {
        toggleCompileClasspathPackaging(compileClasspathPackaging)
        settingsFile << """
            include 'b', 'c'
        """
        given:
        file("b/build.gradle") << """
            apply plugin: 'java-library'
            
            java {
                registerFeature("myFeature") {
                    usingSourceSet(sourceSets.main)
                }
            }
            
            dependencies {
                myFeatureImplementation project(":c")
            }
            
        """
        buildFile << """
            apply plugin: 'java-library'
            
            dependencies {
                implementation(project(":b"))
            }
            
            task resolveRuntime {
                dependsOn(configurations.runtimeClasspath)
                doLast {
                    assert configurations.runtimeClasspath.files.name as Set == ['b-1.0.jar'] as Set
                }
            }
        """
        file("c/build.gradle") << """
            apply plugin: 'java-library'
        """
        file("c/src/main/java/com/baz/Baz.java") << """
            package com.baz;
            public class Baz {}
        """
        file("b/src/main/java/com/foo/Foo.java") << """
            package com.foo;
            public class Foo {
                public void foo() {
                }
            }
        """
        file("src/main/java/com/bar/Bar.java") << """
            package com.bar;
            import com.foo.Foo;
            
            public class Bar {
                public void bar() {
                    Foo foo = new Foo();
                    foo.foo();
                }
            }
        """

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':b:compileJava', ':c:compileJava'
        packagingTasks(compileClasspathPackaging, 'b')
        packagingTasks(compileClasspathPackaging, 'c')

        when:
        succeeds 'clean', ':resolveRuntime'

        then:
        executedAndNotSkipped ':b:jar'

        where:
        compileClasspathPackaging | _
        false                     | _
        true                      | _
    }

    @Unroll
    def "can build a feature that uses its own source directory [compileClasspathPackaging=#compileClasspathPackaging]"() {
        toggleCompileClasspathPackaging(compileClasspathPackaging)
        settingsFile << """
            include 'b'
        """
        given:
        buildFile << """
            apply plugin: 'java-library'

            sourceSets {
                myFeature {
                    java {
                        srcDir "src/myFeature/java"
                    }
                }
            }            

            java {
                registerFeature("myFeature") {
                    usingSourceSet(sourceSets.myFeature)
                }
            }
            
            dependencies {
                $configuration project(":b")
            }
        """
        file("b/build.gradle") << """
            apply plugin: 'java-library'
        """
        file("b/src/main/java/com/foo/Foo.java") << """
            package com.foo;
            public class Foo {
                public void foo() {
                }
            }
        """
        file("src/myFeature/java/com/bar/Bar.java") << """
            package com.bar;
            import com.foo.Foo;
            
            public class Bar {
                public void bar() {
                    Foo foo = new Foo();
                    foo.foo();
                }
            }
        """

        when:
        succeeds ':compileMyFeatureJava'

        then:
        executedAndNotSkipped ':b:compileJava'
        packagingTasks(compileClasspathPackaging, 'b')
        notExecuted ':compileJava'

        where:
        compileClasspathPackaging | configuration
        false                     | "myFeatureApi"
        true                      | "myFeatureApi"
        false                     | "myFeatureImplementation"
        true                      | "myFeatureImplementation"
    }

    @Unroll
    def "Java Library can depend on feature of component which uses its own source set [compileClasspathPackaging=#compileClasspathPackaging]"() {
        toggleCompileClasspathPackaging(compileClasspathPackaging)
        settingsFile << """
            include 'b', 'c', 'd'
        """
        given:
        file("b/build.gradle") << """
            apply plugin: 'java-library'
            
            sourceSets {
                myFeature {
                    java {
                        srcDir "src/myFeature/java"
                    }
                }
            }            

            java {
                registerFeature("myFeature") {
                    usingSourceSet(sourceSets.myFeature)
                }
            }
            
            dependencies {
                myFeatureApi project(":c")
                myFeatureImplementation project(":d")
            }
            
        """
        buildFile << """
            apply plugin: 'java-library'
            
            dependencies {
                implementation(project(":b")) {
                    capabilities {
                        requireCapability("org.gradle.test:b-my-feature")
                    }
                }
            }
            
            task verifyClasspath {
                dependsOn(configurations.compileClasspath)
                dependsOn(configurations.runtimeClasspath)
                doLast {
                    assert configurations.compileClasspath.incoming.resolutionResult.allDependencies.collect {
                        it.toString()
                    } as Set == ['project :b', 'project :c'] as Set // only API dependencies
                    assert configurations.runtimeClasspath.incoming.resolutionResult.allDependencies.collect {
                        it.toString()
                    } as Set == ['project :b', 'project :c', 'project :d'] as Set // all dependencies
                    assert configurations.runtimeClasspath.files.name as Set == ['b-1.0-my-feature.jar', 'c-1.0.jar', 'd-1.0.jar'] as Set
                }
            }
        """
        ['c', 'd'].each {
            file("$it/build.gradle") << """
            apply plugin: 'java-library'
        """
            file("$it/src/main/java/com/baz/Baz${it}.java") << """
            package com.baz;
            public class Baz${it} {}
        """
        }

        file("b/src/myFeature/java/com/foo/Foo.java") << """
            package com.foo;
            public class Foo {
                public void foo() {
                }
            }
        """
        file("src/main/java/com/bar/Bar.java") << """
            package com.bar;
            import com.foo.Foo;
            
            public class Bar {
                public void bar() {
                    Foo foo = new Foo();
                    foo.foo();
                }
            }
        """

        when:
        succeeds ':compileJava'

        then:
        executedAndNotSkipped ':b:compileMyFeatureJava', ':c:compileJava', ':d:compileJava'
        packagingTasks(compileClasspathPackaging, 'b', 'myFeature')
        packagingTasks(compileClasspathPackaging, 'c')
        packagingTasks(compileClasspathPackaging, 'd')

        when:
        succeeds 'clean', ':verifyClasspath'

        then:
        executedAndNotSkipped ':b:myFeatureJar', ':c:jar', ':d:jar'
        notExecuted ':b:jar' // main jar should NOT be built in this case

        where:
        compileClasspathPackaging | _
        false                     | _
        true                      | _
    }

    private void packagingTasks(boolean expectExecuted, String subproject, String feature = '') {
        def tasks = [":$subproject:process${feature.capitalize()}Resources", ":$subproject:${feature.isEmpty()? 'classes' : feature + 'Classes'}", ":$subproject:${feature.isEmpty()? 'jar' : feature + 'Jar'}"]
        if (expectExecuted) {
            executed(*tasks)
        } else {
            notExecuted(*tasks)
        }
    }
}

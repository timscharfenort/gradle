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

package org.gradle.integtests.tooling.r56

import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion


@ToolingApiVersion(">=5.6")
@TargetGradleVersion(">=5.6")
class TestLauncherDebuggingCrossVersionTest extends ToolingApiSpecification {

    def "can launch tests in debug mode"() {
        setup:
        buildFile << """
            plugins { id 'java-library' }
            ${mavenCentralRepository()}
            dependencies { testCompile 'junit:junit:4.12' }
        """
        file('src/test/java/example/MyTest.java').text = """
            package example;
            public class MyTest {
                @org.junit.Test public void foo() throws Exception {
                     org.junit.Assert.assertEquals(1, 1);
                }
            }
        """
        when:
        withConnection { connection ->
            connection.newTestLauncher()
                .setStandardOutput(stdout)
                .withJvmTestClasses("example.MyTest")
                .withDebugOptions(4008, false)
                .run()
        }

        then:
        stdout.toString().contains("Listening for transport dt_socket at address: 4008")
    }
}

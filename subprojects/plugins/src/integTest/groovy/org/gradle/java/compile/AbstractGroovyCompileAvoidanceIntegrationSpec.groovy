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

package org.gradle.java.compile


abstract class AbstractGroovyCompileAvoidanceIntegrationSpec extends AbstractJavaGroovyCompileAvoidanceIntegrationSpec {
    Language language = Language.GROOVY

    private String goodAstTransformation() {
        """
import org.codehaus.groovy.transform.*;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.control.*;
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class MyASTTransformation extends AbstractASTTransformation {
    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
            System.out.println("Hello from AST transformation!");
    }
}
    """
    }

    private String goodAstTransformationWithABIChange() {
        """
import org.codehaus.groovy.transform.*;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.control.*;
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class MyASTTransformation extends AbstractASTTransformation {
    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
            System.out.println("Hello from AST transformation!");
    }
    public void foo() { }
}
    """
    }

    private String badAstTransformationNonABIChange() {
        """
import org.codehaus.groovy.transform.*;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.control.*;
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class MyASTTransformation extends AbstractASTTransformation {
    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        assert false: "Bad AST transformation!"
    }
    public void foo() { }
}
    """
    }

    private String astTransformationDeclaration() {
        """
            project(':b') {
                configurations { astTransformation }
                dependencies {
                    astTransformation project(':a')
                }
                
                tasks.withType(GroovyCompile) {
                    compilerPluginClasspath.from(configurations.astTransformation)
                }
            }
        """
    }

    private String astTransformationAnnotation() {
        """
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE])
@org.codehaus.groovy.transform.GroovyASTTransformationClass("MyASTTransformation")
public @interface MyAnnotation { }
        """
    }

    def 'always recompile if compilation avoidance is not enabled'() {
        given:
        enableGroovyCompilationAvoidance = false
        buildFile << """
            project(':b') {
                dependencies {
                    implementation project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/groovy/ToolImpl.groovy")
        sourceFile << """
            public class ToolImpl { 
                public String thing() { return null; }
            }
        """
        file("b/src/main/groovy/Main.groovy") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ":b:compileGroovy"

        then:
        outputDoesNotContain('Groovy compilation avoidance is an incubating feature')
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"

        when:
        sourceFile.text = """
            public class ToolImpl { 
                public String thing() { return ""; }
            }
        """

        then:
        succeeds ":b:compileGroovy"
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"
    }

    def "recompile with change of local ast transformation"() {
        given:
        executer.beforeExecute {
            executer.withArgument('--info')
        }
        buildFile << astTransformationDeclaration()
        file("a/src/main/groovy/MyAnnotation.groovy") << astTransformationAnnotation()
        def astTransformationSourceFile = file("a/src/main/groovy/MyASTTransformation.groovy")
        file("b/src/main/groovy/Main.groovy") << """
            @MyAnnotation 
            public class Main { }
        """

        when:
        astTransformationSourceFile << goodAstTransformation()

        then:
        succeeds ":b:compileGroovy"
        outputContains('Hello from AST transformation!')
        outputContains('Groovy compilation avoidance is an incubating feature')
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"

        when:
        astTransformationSourceFile.text = goodAstTransformationWithABIChange()

        then:
        succeeds ":b:compileGroovy"
        outputContains('Hello from AST transformation!')
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"

        when:
        astTransformationSourceFile.text = badAstTransformationNonABIChange()

        then:
        fails ":b:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"
        failure.assertHasCause('Bad AST transformation!')
    }

    def "recompile with change of global ast transformation"() {
        given:
        executer.beforeExecute {
            executer.withArgument('--info')
        }
        buildFile << astTransformationDeclaration()
        file("a/src/main/resources/META-INF/services/org.codehaus.groovy.transform.ASTTransformation") << "MyASTTransformation"
        def astTransformationSourceFile = file("a/src/main/groovy/MyASTTransformation.groovy")
        file("b/src/main/groovy/Main.groovy") << """
            public class Main { }
        """

        when:
        astTransformationSourceFile << goodAstTransformation()

        then:
        succeeds ":b:compileGroovy"
        outputContains('Hello from AST transformation!')
        outputContains('Groovy compilation avoidance is an incubating feature')
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"

        when:
        astTransformationSourceFile.text = goodAstTransformationWithABIChange()

        then:
        succeeds ":b:compileGroovy"
        outputContains('Hello from AST transformation!')
        executedAndNotSkipped ":a:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"

        when:
        astTransformationSourceFile.text = badAstTransformationNonABIChange()

        then:
        fails ":b:compileGroovy"
        executedAndNotSkipped ":b:compileGroovy"
        failure.assertHasCause('Bad AST transformation!')
    }

    def "doesn't recompile when private element of implementation class changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    implementation project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/${language.name}/ToolImpl.${language.name}")
        sourceFile << """
            public class ToolImpl { 
                private String thing() { return null; }
                private ToolImpl t = this;
            }
        """
        file("b/src/main/${language.name}/Main.${language.name}") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ":b:${language.compileTaskName}"

        then:
        executedAndNotSkipped ":a:${language.compileTaskName}"
        executedAndNotSkipped ":b:${language.compileTaskName}"

        when:
        // change signatures
        sourceFile.text = """
            public class ToolImpl { 
                private Number thing() { return null; }
                private Object t = this;
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}"
        skipped ":b:${language.compileTaskName}"

        when:
        // add private elements
        sourceFile.text = """
            public class ToolImpl { 
                private Number thing() { return null; }
                private Object t = this;
                private static void someMethod() { }
                private String s;
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}"
        skipped ":b:${language.compileTaskName}"

        when:
        // remove private elements
        sourceFile.text = """
            public class ToolImpl { 
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}"
        skipped ":b:${language.compileTaskName}"

        when:
        // add public method, should change
        sourceFile.text = """
            public class ToolImpl { 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}", ":b:${language.compileTaskName}"

        when:
        // add public field, should change
        sourceFile.text = """
            public class ToolImpl { 
                public static ToolImpl instance; 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}", ":b:${language.compileTaskName}"

        when:
        // add public constructor, should change
        sourceFile.text = """
            public class ToolImpl { 
                public ToolImpl() { }
                public ToolImpl(String s) { }
                public static ToolImpl instance; 
                public void execute() { String s = toString(); } 
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}", ":b:${language.compileTaskName}"
    }

    def "doesn't recompile when implementation class code changes"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    implementation project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/${language.name}/ToolImpl.${language.name}")
        sourceFile << """
            public class ToolImpl {
                public Object s = String.valueOf(12);
                public ToolImpl() { }
                public void execute() { int i = 12; }
            }
        """
        file("b/src/main/${language.name}/Main.${language.name}") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ":b:${language.compileTaskName}"

        then:
        executedAndNotSkipped ":a:${language.compileTaskName}"
        executedAndNotSkipped ":b:${language.compileTaskName}"

        when:
        // change method body and field initializer
        sourceFile.text = """
            public class ToolImpl {
                public Object s = "12";
                public ToolImpl() { }
                public void execute() { String s = toString(); }
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}"
        skipped ":b:${language.compileTaskName}"

        when:
        // change static initializer and constructor
        sourceFile.text = """
            public class ToolImpl {
                static { int i = 123; }
                public ToolImpl() { System.out.println("created!"); }
                public Object s = "12";
                public void execute() { String s = toString(); }
            }
"""

        then:
        succeeds ":b:${language.compileTaskName}"
        executedAndNotSkipped ":a:${language.compileTaskName}"
        skipped ":b:${language.compileTaskName}"
    }
}

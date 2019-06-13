/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.java.compile.incremental

import groovy.transform.NotYetImplemented
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.CompilationOutputsFixture
import org.gradle.integtests.fixtures.CompiledLanguage
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Issue
import spock.lang.Unroll

abstract class AbstractSourceIncrementalCompilationIntegrationTest extends AbstractIntegrationSpec {
    abstract CompiledLanguage getLanguage()

    CompilationOutputsFixture outputs

    def setup() {
        outputs = new CompilationOutputsFixture(file("build/classes"))

        buildFile << """
            apply plugin: '${language.name}'
        """
    }

    private File source(String... classBodies) {
        File out
        for (String body : classBodies) {
            def className = (body =~ /(?s).*?(?:class|interface|enum) (\w+) .*/)[0][1]
            assert className: "unable to find class name"
            def f = file("src/main/${language.name}/${className}.${language.name}")
            f.createFile()
            f.text = body
            out = f
        }
        out
    }

    def "detects deletion of an isolated source class with an inner class"() {
        def a = source """class A {
            class InnerA {}
        }"""
        source "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        assert a.delete()
        run language.compileTaskName

        then:
        outputs.noneRecompiled() //B is not recompiled
        outputs.deletedClasses 'A', 'A$InnerA' //inner class is also deleted
    }

    def "detects deletion of a source base class that leads to compilation failure"() {
        def a = source "class A {}"
        source "class B extends A {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        assert a.delete()
        then:
        fails language.compileTaskName
        outputs.noneRecompiled()
        outputs.deletedClasses 'A', 'B'
    }

    def "detects change of an isolated source class with an inner class"() {
        source """class A {
            class InnerA {}
        }"""
        source "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source """class A {
            class InnerA { /* change */ }
        }"""
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'A$InnerA'
    }

    def "detects change of an isolated class"() {
        source "class A {}", "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A'
    }

    def "detects deletion of an inner class"() {
        source """class A {
            class InnerA {}
        }"""
        source "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A {}"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A'
        outputs.deletedClasses 'A$InnerA'
    }

    def "detects rename of an inner class"() {
        source """class A {
            class InnerA {}
        }"""
        source "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source """class A {
            class InnerA2 {}
        }"""
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'A$InnerA2'
        outputs.deletedClasses 'A$InnerA'
    }

    def "detects addition af a new class with an inner class"() {
        source "class B {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source """class A {
            class InnerA {}
        }"""
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'A$InnerA'
    }

    def "detects transitive dependencies"() {
        source "class A {}", "class B extends A {}", "class C extends B {}", "class D {}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'B', 'C'

        when:
        outputs.snapshot()
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B', 'C'
    }

    def "detects transitive dependencies with inner classes"() {
        source "class A {}", "class B extends A {}", "class D {}"
        source """class C extends B {
            class InnerC {}
        }
        """
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'B', 'C', 'C$InnerC'
    }

    def "handles cycles in class dependencies"() {
        source "class A {}", "class D {}"
        source "class B extends A { C c; }", "class C extends B {}" //cycle
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'A', 'B', 'C'
    }

    @Unroll
    def "change to #retention retention annotation class recompiles #desc"() {
        def annotationClass = file("src/main/${language.name}/SomeAnnotation.${language.name}") << """
            import java.lang.annotation.*;

            @Retention(RetentionPolicy.$retention) 
            public @interface SomeAnnotation {}
        """
        source "@SomeAnnotation class A {}", "class B {}"
        outputs.snapshot { run language.compileTaskName }

        when:
        annotationClass.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses(expected as String[])

        where:
        desc              | retention | expected
        'all'             | 'SOURCE'  | ['A', 'B', 'SomeAnnotation']
        'annotated types' | 'CLASS'   | ['SomeAnnotation', 'A']
        'annotated types' | 'RUNTIME' | ['SomeAnnotation', 'A']
    }

    def "change to class referenced by an annotation recompiles annotated types"() {
        source """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) 
            public @interface B {
                Class<?> value();
            }
        """
        def a = source "class A {}"
        source "@B(A.class) class OnClass {}",
            "class OnMethod { @B(A.class) void foo() {} }",
            "class OnField { @B(A.class) String foo; }",
            "class OnParameter { void foo(@B(A.class) int x) {} }"
        outputs.snapshot { run language.compileTaskName }

        when:
        a.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "OnClass", "OnMethod", "OnParameter", "OnField")
    }

    def "change to class referenced by an array value in an annotation recompiles annotated types"() {
        source """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) 
            public @interface B {
                Class<?>[] value();
            }
        """
        def a = source "class A {}"
        source "@B(A.class) class OnClass {}",
            "class OnMethod { @B(A.class) void foo() {} }",
            "class OnField { @B(A.class) String foo; }",
            "class OnParameter { void foo(@B(A.class) int x) {} }"
        outputs.snapshot { run language.compileTaskName }

        when:
        a.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "OnClass", "OnMethod", "OnParameter", "OnField")
    }

    def "change to enum referenced by an annotation recompiles annotated types"() {
        source """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) 
            public @interface B {
                A value();
            }
        """
        def a = source "enum A { FOO }"
        source "@B(A.FOO) class OnClass {}",
            "class OnMethod { @B(A.FOO) void foo() {} }",
            "class OnField { @B(A.FOO) String foo; }",
            "class OnParameter { void foo(@B(A.FOO) int x) {} }"
        outputs.snapshot { run language.compileTaskName }

        when:
        a.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "B", "OnClass", "OnMethod", "OnParameter", "OnField")
    }

    def "change to value in nested annotation recompiles annotated types"() {
        source """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) 
            public @interface B {
                A value();
            }
        """
        source "public @interface A { Class<?> value(); }"
        def c = source "class C {}"
        source "@B(@A(C.class)) class OnClass {}",
            "class OnMethod { @B(@A(C.class)) void foo() {} }",
            "class OnField { @B(@A(C.class)) String foo; }",
            "class OnParameter { void foo(@B(@A(C.class)) int x) {} }"
        outputs.snapshot { run language.compileTaskName }

        when:
        c.text += "/* change */"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("C", "OnClass", "OnMethod", "OnParameter", "OnField")
    }

    def "changed class with private constant does not incur full rebuild"() {
        source "class A {}", "class B { private final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B'
    }

    def "changed class with used non-private constant incurs full rebuild"() {
        source "class A { int foo() { return 1; } }", "class B { final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B', 'A'
    }

    @NotYetImplemented
    //  Can re-enable with compiler plugins. See gradle/gradle#1474
    def "changing an unused non-private constant incurs partial rebuild"() {
        source "class A { int foo() { return 2; } }", "class B { final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B'
    }

    def "dependent class with non-private constant does not incur full rebuild"() {
        source "class A {}", "class B extends A { final static int x = 1;}", "class C {}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B', 'A'
    }

    def "detects class changes in subsequent runs ensuring the class dependency data is refreshed"() {
        source "class A {}", "class B {}", "class C {}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B extends A {}"
        run language.compileTaskName

        then:
        outputs.recompiledClasses('B')

        when:
        outputs.snapshot()
        source "class A { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses('A', 'B')
    }

    def "handles multiple compile tasks within a single project"() {
        source "class A {}", "class B extends A {}"
        file("src/integTest/${language.name}/X.${language.name}") << "class X {}"
        file("src/integTest/${language.name}/Y.${language.name}") << "class Y extends X {}"

        //new separate compile task (integTestCompile)
        file("build.gradle") << """
            sourceSets { integTest.${language.name}.srcDir 'src/integTest/java' }
        """

        outputs.snapshot { run "compileIntegTestJava", language.compileTaskName }

        when: //when A class is changed
        source "class A { String change; }"
        run "compileIntegTestJava", language.compileTaskName, "-i"

        then: //only B and A are recompiled
        outputs.recompiledClasses("A", "B")

        when: //when X class is changed
        outputs.snapshot()
        file("src/integTest/${language.name}/X.${language.name}").text = "class X { String change;}"
        run "compileIntegTestJava", language.compileTaskName, "-i"

        then: //only X and Y are recompiled
        outputs.recompiledClasses("X", "Y")
    }

    def "recompiles classes from extra source directories"() {
        buildFile << "sourceSets.main.${language.name}.srcDir 'extra-java'"

        source("class B {}")
        file("extra-java/A.${language.name}") << "class A extends B {}"
        file("extra-java/C.${language.name}") << "class C {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source("class B { String change; } ")
        run language.compileTaskName

        then:
        outputs.recompiledClasses("B", "A")
    }

    def "recompilation considers changes from dependent sourceSet"() {
        buildFile << """
sourceSets {
    other {}
    main { compileClasspath += sourceSets.other.output }
}
"""

        source("class Main extends com.foo.Other {}")
        file("src/other/java/com/foo/Other.${language.name}") << "package com.foo; public class Other {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/other/java/com/foo/Other.${language.name}").text = "package com.foo; public class Other { String change; }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("Other", "Main")
    }

    def "recompilation does not process removed classes from dependent sourceSet"() {
        def unusedClass = source("public class Unused {}")
        // Need another class or :compileJava will always be considered UP-TO-DATE
        source("public class Other {}")

        file("src/test/java/BazTest.${language.name}") << "public class BazTest {}"

        outputs.snapshot { run "compileTestJava" }

        when:
        file("src/test/java/BazTest.${language.name}").text = "public class BazTest { String change; }"
        unusedClass.delete()

        run "compileTestJava"

        then:
        outputs.recompiledClasses("BazTest")
        outputs.deletedClasses("Unused")
    }

    def "detects changes to source in extra source directories"() {
        buildFile << "sourceSets.main.${language.name}.srcDir 'extra-java'"

        source("class A extends B {}")
        file("extra-java/B.${language.name}") << "class B {}"
        file("extra-java/C.${language.name}") << "class C {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        file("extra-java/B.${language.name}").text = "class B { String change; }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("B", "A")
    }

    def "recompiles classes from extra source directory provided as #type"() {
        given:
        buildFile << "compileJava.source $method('extra-java')"

        source("class B {}")
        file("extra-java/A.${language.name}") << "class A extends B {}"
        file("extra-java/C.${language.name}") << "class C {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        source("class B { String change; } ")
        run language.compileTaskName

        then:
        outputs.recompiledClasses("B", "A")

        where:
        type            | method
        "File"          | "file"
        "DirectoryTree" | "fileTree"
    }

    def "detects changes to source in extra source directory provided as #type"() {
        buildFile << "compileJava.source $method('extra-java')"

        source("class A extends B {}")
        file("extra-java/B.${language.name}") << "class B {}"
        file("extra-java/C.${language.name}") << "class C {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        file("extra-java/B.${language.name}").text = "class B { String change; }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("B", "A")

        where:
        type            | method
        "File"          | "file"
        "DirectoryTree" | "fileTree"
    }

    def "reports source type that does not support detection of source root"() {
        buildFile << "compileJava.source([file('extra-java'), file('other'), file('text-file.txt')])"

        source("class A extends B {}")
        file("extra-java/B.${language.name}") << "class B {}"
        file("extra-java/C.${language.name}") << "class C {}"
        def textFile = file('text-file.txt')
        textFile.text = "text file as root"

        outputs.snapshot { run language.compileTaskName }

        when:
        file("extra-java/B.${language.name}").text = "class B { String change; }"
        executer.withArgument "--info"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "B", "C")
        output.contains("Cannot infer source root(s) for source `file '${textFile.absolutePath}'`. Supported types are `File` (directories only), `DirectoryTree` and `SourceDirectorySet`.")
        output.contains("Full recompilation is required because the source roots could not be inferred.")
    }

    def "missing files are ignored as source roots"() {
        buildFile << """
            compileJava {
                source([
                    fileTree('missing-tree'),
                    file('missing-file')
                ])
            }"""

        source("class A extends B {}")
        source("class B {}")
        source("class C {}")

        outputs.snapshot { run language.compileTaskName }

        when:
        source("class B { String change; }")
        executer.withArgument "--info"
        run language.compileTaskName

        then:
        outputs.recompiledClasses("A", "B")
    }

    def "can remove source root"() {
        def toBeRemoved = file("to-be-removed")
        buildFile << """
            ${language.getCompileTaskName()} {
                source([fileTree('to-be-removed')])
            }"""

        source("class A extends B {}")
        source("class B {}")
        toBeRemoved.file("C.${language.name}").text = "class C {}"

        outputs.snapshot { run language.compileTaskName }

        when:
        toBeRemoved.deleteDir()
        executer.withArgument "--info"
        run language.compileTaskName

        then:
        outputs.recompiledClasses()
    }

    def "handles duplicate class across source directories"() {
        //compiler does not allow this scenario, documenting it here
        buildFile << "sourceSets.main.${language.name}.srcDir 'java'"

        source("class A {}")
        file("java/A.${language.name}") << "class A {}"

        when:
        fails language.compileTaskName
        then:
        failure.assertHasCause("Compilation failed")
    }

    @Issue("GRADLE-3426")
    def "supports Java 1.2 dependencies"() {
        source "class A {}"

        buildFile << """
        ${jcenterRepository()}
dependencies { implementation 'com.ibm.icu:icu4j:2.6.1' }
"""
        expect:
        succeeds language.compileTaskName
    }

    @Issue("GRADLE-3426")
    def "fully recompiles when a non-analyzable jar is changed"() {
        def a =  source """
            import com.ibm.icu.util.Calendar;
            class A {
                Calendar cal;
            }
        """

        buildFile << """
            ${jcenterRepository()}
            if (hasProperty("withIcu")) {
                dependencies { implementation 'com.ibm.icu:icu4j:2.6.1' }
            }

        """
        succeeds language.compileTaskName, "-PwithIcu"

        when:
        a.text = "class A {}"

        then:
        succeeds language.compileTaskName, "--info"
        outputContains("Full recompilation is required because LocaleElements_zh__PINYIN.class could not be analyzed for incremental compilation.")
    }

    @Issue("GRADLE-3495")
    def "supports Java 1.1 dependencies"() {
        source "class A {}"

        buildFile << """
${jcenterRepository()}
dependencies { implementation 'net.sf.ehcache:ehcache:2.10.2' }
"""
        expect:
        run language.compileTaskName
    }

    @Unroll("detects changes to class referenced through a #modifier field")
    def "detects changes to class referenced through a field"() {
        given:
        source """class A {
    $modifier B b;
    void doSomething() {
        Runnable r = b;
        r.run();
    }
}"""
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b;'

        where:
        modifier << ['', 'static']
    }

    @Unroll("detects changes to class referenced through a #modifier array field")
    def "detects changes to class referenced through an array field"() {
        given:
        source """class A {
    $modifier B[] b;
    void doSomething() {
        Runnable r = b[0];
        r.run();
    }
}"""
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b[0];'

        where:
        modifier << ['', 'static']
    }

    @Unroll("detects changes to class referenced through a #modifier multi-dimensional array field")
    def "detects changes to class referenced through an multi-dimensional array field"() {
        given:
        source """class A {
    $modifier B[][] b;
    void doSomething() {
        Runnable r = b[0][0];
        r.run();
    }
}"""
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b[0][0];'

        where:
        modifier << ['', 'static']
    }

    def "detects changes to class referenced in method body"() {
        given:
        java '''class A {
    void doSomething(Object b) {
        Runnable r = (B) b;
        r.run();
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = (B) b;'
    }

    def "detects changes to class referenced through return type"() {
        given:
        java '''class A {
    B b() { return null; }
    
    void doSomething() {
        Runnable r = b();
        r.run();
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b();'
    }

    def "detects changes to class referenced through method signature"() {
        given:
        java '''class A {
    Runnable go(B b) {
        Runnable r = b;
        r.run();
        return b;
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b;'
    }

    def "detects changes to class referenced through type argument in field"() {
        given:
        java '''class A {
    java.util.List<B> bs;
    void doSomething() {
        for (B b: bs) {
           Runnable r = b;
           r.run();
        }
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b;'
    }

    def "detects changes to class referenced through type argument in return type"() {
        given:
        java '''class A {
    java.util.List<B> bs() { return null; }
    
    void doSomething() {
        for (B b: bs()) {
           Runnable r = b;
           r.run();
        }
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b;'
    }

    def "detects changes to class referenced through type argument in parameter"() {
        given:
        java '''class A {
    
    void doSomething(java.util.List<B> bs) {
        for (B b: bs) {
           Runnable r = b;
           r.run();
        }
    }
}'''
        java '''abstract class B implements Runnable { }'''

        outputs.snapshot { run language.compileTaskName }

        when:
        file("src/main/${language.name}/B.${language.name}").text = "class B { }"
        fails language.compileTaskName

        then:
        failure.assertHasErrorOutput 'Runnable r = b;'
    }

    def "deletes empty packages dirs"() {
        given:
        def a = file('src/main/${language.name}/com/foo/internal/A.${language.name}') << """
            package com.foo.internal;
            public class A {}
        """
        file('src/main/${language.name}/com/bar/B.${language.name}') << """
            package com.bar;
            public class B {}
        """

        succeeds language.compileTaskName
        a.delete()

        when:
        succeeds language.compileTaskName

        then:
        ! file("build/classes/java/main/com/foo").exists()
    }

    def "recompiles types whose names look like inne classes even if they aren't"() {
        given:
        file('src/main/${language.name}/Test.${language.name}') << 'public class Test{}'
        file('src/main/${language.name}/Test$$InnerClass.${language.name}') << 'public class Test$$InnerClass{}'
        buildFile << '''
            apply plugin: 'java'
        '''.stripIndent()

        when:
        succeeds ":${language.compileTaskName}"

        then:
        executedAndNotSkipped ":${language.compileTaskName}"
        file('build/classes/java/main/Test.class').assertExists()
        file('build/classes/java/main/Test$$InnerClass.class').assertExists()

        when:
        file('src/main/${language.name}/Test.${language.name}').text = 'public class Test{ void foo() {} }'
        succeeds ":${language.compileTaskName}"

        then:
        executedAndNotSkipped ":${language.compileTaskName}"
        file('build/classes/java/main/Test.class').assertExists()
        file('build/classes/java/main/Test$$InnerClass.class').assertExists()
    }

    def "incremental java compilation ignores empty packages"() {
        given:
        file('src/main/${language.name}/org/gradle/test/MyTest.${language.name}').text = """
            package org.gradle.test;
            
            class MyTest {}
        """

        when:
        run language.compileTaskName
        then:
        executedAndNotSkipped(":${language.compileTaskName}")

        when:
        file('src/main/${language.name}/org/gradle/different').createDir()
        run(language.compileTaskName)

        then:
        skipped(":${language.compileTaskName}")
    }

    @Requires(TestPrecondition.JDK9_OR_LATER)
    def "recompiles when module info changes"() {
        given:
        source("""
            import java.util.logging.Logger;
            class Foo {
                Logger logger;
            }
        """)
        def moduleInfo = file("src/main/${language.name}/module-info.${language.name}")
        moduleInfo.text = """
            module foo {
                requires java.logging;
            }
        """

        succeedslanguage.compileTaskName

        when:
        moduleInfo.text = """
            module foo {
            }
        """

        then:
        failslanguage.compileTaskName
        result.assertHasErrorOutput("package java.util.logging is not visible")
    }

    def "recompiles all classes in a package if the package-info file changes"() {
        given:
        def packageFile = file("src/main/${language.name}/foo/package-info.${language.name}")
        packageFile.text = """package foo;"""
        file("src/main/${language.name}/foo/A.${language.name}").text = "package foo; class A {}"
        file("src/main/${language.name}/foo/B.${language.name}").text = "package foo; public class B {}"
        file("src/main/${language.name}/foo/bar/C.${language.name}").text = "package foo.bar; class C {}"
        file("src/main/${language.name}/baz/D.${language.name}").text = "package baz; class D {}"
        file("src/main/${language.name}/baz/E.${language.name}").text = "package baz; import foo.B; class E extends B {}"

        outputs.snapshot { succeeds language.compileTaskName }

        when:
        packageFile.text = """@Deprecated package foo;"""
        succeeds language.compileTaskName

        then:
        outputs.recompiledClasses("A", "B", "E", "package-info")
    }

    @Requires(TestPrecondition.JDK8_OR_LATER)
    def "deletes headers when source file is deleted"() {
        given:
        buildFile << """
            compileJava.options.headerOutputDirectory = file("build/headers/java/main")
        """
        def source = source("""class Foo {
            public native void foo();
        }""")
        source("""class Bar {
            public native void bar();
        }""")

        succeeds language.compileTaskName

        when:
        source.delete()
        succeeds language.compileTaskName

        then:
        file("build/headers/java/main/Foo.h").assertDoesNotExist()
        file("build/headers/java/main/Bar.h").assertExists()
    }

    def "recompiles all dependents when no jar analysis is present"() {
        given:
        source """class A {
            com.google.common.base.Splitter splitter;
        }"""
        source """class B {}"""

        buildFile << """
        ${jcenterRepository()}
dependencies { implementation 'com.google.guava:guava:21.0' }
"""
        outputs.snapshot { succeeds language.compileTaskName }

        when:
        executer.requireOwnGradleUserHomeDir()
        source """class B {
            //some change
        }"""

        then:
        succeeds language.compileTaskName
        outputs.recompiledClasses("A", "B")
    }

    def "does not recompile when a resource changes"() {
        given:
        buildFile << """
            compileJava.inputs.dir 'src/main/resources'
        """
        source("class A {}")
        source("class B {}")
        def resource = file("src/main/resources/foo.txt")
        resource.text = 'foo'

        outputs.snapshot { succeeds language.compileTaskName }

        when:
        resource.text = 'bar'

        then:
        succeeds language.compileTaskName
        outputs.noneRecompiled()
    }
}

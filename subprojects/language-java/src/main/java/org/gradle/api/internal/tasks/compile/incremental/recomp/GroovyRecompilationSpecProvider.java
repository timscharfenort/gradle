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

package org.gradle.api.internal.tasks.compile.incremental.recomp;

import com.google.common.collect.Sets;
import org.gradle.internal.change.DefaultFileChange;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.impl.IgnoredPathFingerprintingStrategy;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;

import java.io.File;
import java.util.Set;

public class GroovyRecompilationSpecProvider implements RecompilationSpecProvider {
    private final InputChanges inputChanges;
    private final Iterable<FileChange> classpathChanges;
    private final Iterable<FileChange> sourceChanges;
    private final SourceFileClassNameConverter sourceFileClassNameConverter;

    public GroovyRecompilationSpecProvider(InputChanges inputChanges,
                                           Iterable<FileChange> sourceChanges,
                                           Iterable<FileChange> classpathChanges,
                                           SourceFileClassNameConverter sourceToNameConverter) {
        this.inputChanges = inputChanges;
        this.classpathChanges = classpathChanges;
        this.sourceChanges = sourceChanges;
        this.sourceFileClassNameConverter = sourceToNameConverter;
    }

    @Override
    public boolean isIncremental() {
        return inputChanges.isIncremental();
    }

    @Override
    public RecompilationSpec provideRecompilationSpec(CurrentCompilation current, PreviousCompilation previous) {
        RecompilationSpec spec = new RecompilationSpec(sourceFileClassNameConverter);

        processClasspathChanges(current, previous, spec);
        processOtherChanges(current, previous, spec);

        spec.getClassesToProcess().addAll(previous.getTypesToReprocess());
        return spec;
    }

    private void processClasspathChanges(CurrentCompilation current, PreviousCompilation previous, RecompilationSpec spec) {
        ClasspathEntryChangeProcessor classpathEntryChangeProcessor = new ClasspathEntryChangeProcessor(current.getClasspathSnapshot(), previous);
        Set<File> transformedClasspathEntryDetectionSet = Sets.newHashSet();
        for (FileChange fileChange : classpathChanges) {
            switch (fileChange.getChangeType()) {
                case ADDED:
                    if (!transformedClasspathEntryDetectionSet.add(fileChange.getFile())) {
                        spec.setFullRebuildCause("Classpath has been changed", null);
                        return;
                    }
                    DefaultFileChange added = DefaultFileChange.added(fileChange.getFile().getAbsolutePath(), "classpathEntry", FileType.RegularFile, IgnoredPathFingerprintingStrategy.IGNORED_PATH);
                    classpathEntryChangeProcessor.processChange(added, spec);
                    break;
                case REMOVED:
                    if (!transformedClasspathEntryDetectionSet.add(fileChange.getFile())) {
                        spec.setFullRebuildCause("Classpath has been changed", null);
                        return;
                    }
                    DefaultFileChange removed = DefaultFileChange.removed(fileChange.getFile().getAbsolutePath(), "classpathEntry", FileType.RegularFile, IgnoredPathFingerprintingStrategy.IGNORED_PATH);
                    classpathEntryChangeProcessor.processChange(removed, spec);
                    break;
                case MODIFIED:
                    DefaultFileChange modified = DefaultFileChange.modified(fileChange.getFile().getAbsolutePath(), "classpathEntry", FileType.RegularFile, FileType.RegularFile, IgnoredPathFingerprintingStrategy.IGNORED_PATH);
                    classpathEntryChangeProcessor.processChange(modified, spec);
                    break;
            }
        }
    }

    private void processOtherChanges(CurrentCompilation current, PreviousCompilation previous, RecompilationSpec spec) {
        if (spec.getFullRebuildCause() != null) {
            return;
        }
        JavaChangeProcessor javaChangeProcessor = new JavaChangeProcessor(previous, sourceFileClassNameConverter);
        for (FileChange fileChange : sourceChanges) {
            javaChangeProcessor.processChange((DefaultFileChange) fileChange, spec);
        }
    }
}

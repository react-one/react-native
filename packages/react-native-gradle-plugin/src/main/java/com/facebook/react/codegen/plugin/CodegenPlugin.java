/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.codegen.plugin;

import com.android.build.gradle.BaseExtension;
import com.facebook.react.ReactExtension;
import com.facebook.react.codegen.generator.JavaGenerator;
import com.facebook.react.tasks.BuildCodegenCLITask;
import com.facebook.react.utils.GradleUtils;
import com.facebook.react.utils.PathUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;

/**
 * A Gradle plugin to enable react-native-codegen in Gradle environment. See the Gradle API docs for
 * more information: https://docs.gradle.org/6.5.1/javadoc/org/gradle/api/Project.html
 */
public class CodegenPlugin {

  public void apply(final Project project) {
    final ReactExtension extension =
        GradleUtils.createOrGet(project.getExtensions(), "react", ReactExtension.class, project);

    // 1. Set up build dir.
    final File generatedSrcDir = new File(project.getBuildDir(), "generated/source/codegen");
    final File generatedSchemaFile = new File(generatedSrcDir, "schema.json");

    // 2. Task: produce schema from JS files.
    String os = System.getProperty("os.name").toLowerCase();

    TaskProvider<BuildCodegenCLITask> buildCodegenTask =
        project
            .getTasks()
            .register(
                "buildCodegenCLI",
                BuildCodegenCLITask.class,
                task -> {
                  task.getCodegenDir().set(extension.getCodegenDir());
                  String bashWindowsHome = (String) project.findProperty("REACT_WINDOWS_BASH");
                  task.getBashWindowsHome().set(bashWindowsHome);
                });

    project
        .getTasks()
        .register(
            "generateCodegenSchemaFromJavaScript",
            Exec.class,
            task -> {
              // This is needed when using codegen from source, not from npm.
              task.dependsOn(buildCodegenTask);

              task.doFirst(
                  s -> {
                    generatedSrcDir.delete();
                    generatedSrcDir.mkdirs();
                  });

              task.getInputs()
                  .files(
                      project.fileTree(
                          ImmutableMap.of("dir", extension.getCodegenDir().getAsFile().get())));
              task.getInputs()
                  .files(
                      project.fileTree(
                          ImmutableMap.of(
                              "dir",
                              extension.getJsRootDir().getAsFile().get(),
                              "includes",
                              ImmutableList.of("**/*.js"))));
              task.getOutputs().file(generatedSchemaFile);

              ImmutableList<String> execCommands =
                  new ImmutableList.Builder<String>()
                      .add(os.contains("windows") ? "yarn.cmd" : "yarn")
                      .addAll(ImmutableList.copyOf(extension.getNodeExecutableAndArgs().get()))
                      .add(PathUtils.codegenGenerateSchemaCLI(extension).getAbsolutePath())
                      .add(generatedSchemaFile.getAbsolutePath())
                      .add(extension.getJsRootDir().getAsFile().get().getAbsolutePath())
                      .build();
              task.commandLine(execCommands);
            });

    // 3. Task: generate Java code from schema.
    project
        .getTasks()
        .register(
            "generateCodegenArtifactsFromSchema",
            Exec.class,
            task -> {
              task.dependsOn("generateCodegenSchemaFromJavaScript");

              task.getInputs()
                  .files(
                      project.fileTree(
                          ImmutableMap.of("dir", extension.getCodegenDir().getAsFile().get())));
              task.getInputs()
                  .files(PathUtils.codegenGenerateSchemaCLI(extension).getAbsolutePath());
              task.getInputs().files(generatedSchemaFile);
              task.getOutputs().dir(generatedSrcDir);

              if (extension.getUseJavaGenerator().get()) {
                task.doLast(
                    s -> {
                      generateJavaFromSchemaWithJavaGenerator(
                          generatedSchemaFile,
                          extension.getCodegenJavaPackageName().get(),
                          generatedSrcDir);
                    });
              }

              ImmutableList<String> execCommands =
                  new ImmutableList.Builder<String>()
                      .add(os.contains("windows") ? "yarn.cmd" : "yarn")
                      .addAll(ImmutableList.copyOf(extension.getNodeExecutableAndArgs().get()))
                      .add(
                          PathUtils.codegenGenerateNativeModuleSpecsCLI(extension)
                              .getAbsolutePath())
                      .add("android")
                      .add(generatedSchemaFile.getAbsolutePath())
                      .add(generatedSrcDir.getAbsolutePath())
                      .add(extension.getLibraryName().get())
                      .add(extension.getCodegenJavaPackageName().get())
                      .build();
              task.commandLine(execCommands);
            });

    // 4. Add dependencies & generated sources to the project.
    // Note: This last step needs to happen after the project has been evaluated.
    project.afterEvaluate(
        s -> {
          // `preBuild` is one of the base tasks automatically registered by Gradle.
          // This will invoke the codegen before compiling the entire project.
          Task preBuild = project.getTasks().findByName("preBuild");
          if (preBuild != null) {
            preBuild.dependsOn("generateCodegenArtifactsFromSchema");
          }

          /**
           * Finally, update the android configuration to include the generated sources. This
           * equivalent to this DSL:
           *
           * <p>android { sourceSets { main { java { srcDirs += "$generatedSrcDir/java" } } } }
           *
           * <p>See documentation at
           * https://google.github.io/android-gradle-dsl/current/com.android.build.gradle.BaseExtension.html.
           */
          BaseExtension android = (BaseExtension) project.getExtensions().getByName("android");
          android
              .getSourceSets()
              .getByName("main")
              .getJava()
              .srcDir(new File(generatedSrcDir, "java"));
        });
  }

  // Use Java-based generator implementation to produce the source files, instead of using the
  // JS-based generator.
  private void generateJavaFromSchemaWithJavaGenerator(
      final File schemaFile, final String javaPackageName, final File outputDir) {
    final JavaGenerator generator = new JavaGenerator(schemaFile, javaPackageName, outputDir);
    try {
      generator.build();
    } catch (final Exception ex) {
      throw new GradleException("Failed to generate Java from schema.", ex);
    }
  }
}

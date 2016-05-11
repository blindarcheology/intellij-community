/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.updater;

import com.intellij.openapi.util.io.FileUtil;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

public class PatchTest extends PatchTestCase {
  @Test
  public void testDigestFiles() throws Exception {
    Patch patch = createPatch();
    Map<String, Long> checkSums = patch.digestFiles(getDataDir(), Collections.emptyList(), false, TEST_UI);
    assertEquals(9, checkSums.size());
  }

  @Test
  public void testBasics() throws Exception {
    Patch patch = createPatch();
    assertThat(patch.getActions()).containsOnly(
      new CreateAction(patch, "newDir/newFile.txt"),
      new UpdateAction(patch, "Readme.txt", CHECKSUMS.README_TXT),
      new UpdateZipAction(patch, "lib/annotations.jar",
                          Collections.singletonList("org/jetbrains/annotations/NewClass.class"),
                          Collections.singletonList("org/jetbrains/annotations/Nullable.class"),
                          Collections.singletonList("org/jetbrains/annotations/TestOnly.class"),
                          CHECKSUMS.ANNOTATIONS_JAR),
      new UpdateZipAction(patch, "lib/bootstrap.jar",
                          Collections.emptyList(),
                          Collections.emptyList(),
                          Collections.singletonList("com/intellij/ide/ClassloaderUtil.class"),
                          CHECKSUMS.BOOTSTRAP_JAR),
      new DeleteAction(patch, "bin/idea.bat", CHECKSUMS.IDEA_BAT));
  }

  @Test
  public void testCreatingWithIgnoredFiles() throws Exception {
    PatchSpec spec = new PatchSpec()
      .setOldFolder(myOlderDir.getAbsolutePath())
      .setNewFolder(myNewerDir.getAbsolutePath())
      .setIgnoredFiles(Arrays.asList("Readme.txt", "bin/idea.bat"));
    Patch patch = new Patch(spec, TEST_UI);

    assertThat(patch.getActions()).containsOnly(
      new CreateAction(patch, "newDir/newFile.txt"),
      new UpdateZipAction(patch, "lib/annotations.jar",
                          Collections.singletonList("org/jetbrains/annotations/NewClass.class"),
                          Collections.singletonList("org/jetbrains/annotations/Nullable.class"),
                          Collections.singletonList("org/jetbrains/annotations/TestOnly.class"),
                          CHECKSUMS.ANNOTATIONS_JAR),
      new UpdateZipAction(patch, "lib/bootstrap.jar",
                          Collections.emptyList(),
                          Collections.emptyList(),
                          Collections.singletonList("com/intellij/ide/ClassloaderUtil.class"),
                          CHECKSUMS.BOOTSTRAP_JAR));
  }

  @Test
  public void testValidation() throws Exception {
    Patch patch = createPatch();
    FileUtil.writeToFile(new File(myOlderDir, "bin/idea.bat"), "changed");
    FileUtil.createDirectory(new File(myOlderDir, "extraDir"));
    FileUtil.writeToFile(new File(myOlderDir, "extraDir/extraFile.txt"), "");
    FileUtil.createDirectory(new File(myOlderDir, "newDir"));
    FileUtil.writeToFile(new File(myOlderDir, "newDir/newFile.txt"), "");
    FileUtil.writeToFile(new File(myOlderDir, "Readme.txt"), "changed");
    FileUtil.writeToFile(new File(myOlderDir, "lib/annotations.jar"), "changed");
    FileUtil.delete(new File(myOlderDir, "lib/bootstrap.jar"));

    assertThat(patch.validate(myOlderDir, TEST_UI)).containsOnly(
      new ValidationResult(ValidationResult.Kind.CONFLICT,
                           "newDir/newFile.txt",
                           ValidationResult.Action.CREATE,
                           ValidationResult.ALREADY_EXISTS_MESSAGE,
                           ValidationResult.Option.REPLACE, ValidationResult.Option.KEEP),
      new ValidationResult(ValidationResult.Kind.ERROR,
                           "Readme.txt",
                           ValidationResult.Action.UPDATE,
                           ValidationResult.MODIFIED_MESSAGE,
                           ValidationResult.Option.IGNORE),
      new ValidationResult(ValidationResult.Kind.ERROR,
                           "lib/annotations.jar",
                           ValidationResult.Action.UPDATE,
                           ValidationResult.MODIFIED_MESSAGE,
                           ValidationResult.Option.IGNORE),
      new ValidationResult(ValidationResult.Kind.ERROR,
                           "lib/bootstrap.jar",
                           ValidationResult.Action.UPDATE,
                           ValidationResult.ABSENT_MESSAGE,
                           ValidationResult.Option.IGNORE),
      new ValidationResult(ValidationResult.Kind.CONFLICT,
                           "bin/idea.bat",
                           ValidationResult.Action.DELETE,
                           ValidationResult.MODIFIED_MESSAGE,
                           ValidationResult.Option.DELETE, ValidationResult.Option.KEEP));
  }

  @Test
  public void testValidationWithOptionalFiles() throws Exception {
    Patch patch1 = createPatch();
    FileUtil.writeToFile(new File(myOlderDir, "lib/annotations.jar"), "changed");
    assertThat(patch1.validate(myOlderDir, TEST_UI)).containsOnly(
      new ValidationResult(ValidationResult.Kind.ERROR,
                           "lib/annotations.jar",
                           ValidationResult.Action.UPDATE,
                           ValidationResult.MODIFIED_MESSAGE,
                           ValidationResult.Option.IGNORE));

    PatchSpec spec = new PatchSpec()
      .setOldFolder(myOlderDir.getAbsolutePath())
      .setNewFolder(myNewerDir.getAbsolutePath())
      .setOptionalFiles(Collections.singletonList("lib/annotations.jar"));
    Patch patch2 = new Patch(spec, TEST_UI);
    FileUtil.delete(new File(myOlderDir, "lib/annotations.jar"));
    assertThat(patch2.validate(myOlderDir, TEST_UI)).isEmpty();
  }

  @Test
  public void testValidatingNonAccessibleFiles() throws Exception {
    Patch patch = createPatch();
    File f = new File(myOlderDir, "Readme.txt");
    try (FileOutputStream s = new FileOutputStream(f, true); FileLock ignored = s.getChannel().lock()) {
      String message = UtilsTest.mIsWindows ? System.getProperty("java.vm.name").contains("OpenJDK")
                                              ? "Locked by: OpenJDK Platform binary" : "Locked by: Java(TM) Platform SE binary"
                                            : ValidationResult.ACCESS_DENIED_MESSAGE;
      ValidationResult.Option option = UtilsTest.mIsWindows ? ValidationResult.Option.KILL_PROCESS : ValidationResult.Option.IGNORE;
      assertThat(patch.validate(myOlderDir, TEST_UI)).containsOnly(
        new ValidationResult(ValidationResult.Kind.ERROR,
                             "Readme.txt",
                             ValidationResult.Action.UPDATE,
                             message,
                             option));
    }
  }

  @Test
  public void testSaveLoad() throws Exception {
    Patch patch = createPatch();
    File f = getTempFile("file");
    try (FileOutputStream out = new FileOutputStream(f)) {
      patch.write(out);
    }
    try (FileInputStream in = new FileInputStream(f)) {
      assertEquals(patch.getActions(), new Patch(in).getActions());
    }
  }

  private Patch createPatch() throws IOException, OperationCancelledException {
    PatchSpec spec = new PatchSpec()
      .setOldFolder(myOlderDir.getAbsolutePath())
      .setNewFolder(myNewerDir.getAbsolutePath());
    return new Patch(spec, TEST_UI);
  }
}
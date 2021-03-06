/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.jetbrains.python.sdk.flavors;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SystemProperties;
import com.jetbrains.python.sdk.PySdkExtKt;
import com.jetbrains.python.sdk.PythonSdkType;
import icons.PythonIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * User : catherine
 */
public class VirtualEnvSdkFlavor extends CPythonSdkFlavor {
  private VirtualEnvSdkFlavor() {
  }
  private final static String[] NAMES = new String[]{"jython", "pypy", "python.exe", "jython.bat", "pypy.exe"};

  public static VirtualEnvSdkFlavor INSTANCE = new VirtualEnvSdkFlavor();

  @Override
  public Collection<String> suggestHomePaths(@Nullable Module module) {
    final List<String> candidates = new ArrayList<>();
    if (module != null) {
      final VirtualFile baseDir = PySdkExtKt.getBaseDir(module);
      if (baseDir != null) {
        candidates.addAll(findInDirectory(baseDir));
      }
    }

    final VirtualFile path = getDefaultLocation();
    if (path != null) {
      candidates.addAll(findInDirectory(path));
    }

    final VirtualFile pyEnvLocation = getPyEnvDefaultLocations();
    if (pyEnvLocation != null) {
      candidates.addAll(findInDirectory(pyEnvLocation));
    }

    return candidates;
  }

  @Nullable
  public static VirtualFile getPyEnvDefaultLocations() {
    final String path = System.getenv().get("PYENV_ROOT");
    if (!StringUtil.isEmpty(path)) {
      final VirtualFile pyEnvRoot = LocalFileSystem.getInstance().findFileByPath(FileUtil.expandUserHome(path).replace('\\', '/'));
      if (pyEnvRoot != null) {
        return pyEnvRoot.findFileByRelativePath("versions");
      }
    }
    final VirtualFile userHome = LocalFileSystem.getInstance().findFileByPath(SystemProperties.getUserHome().replace('\\','/'));
    if (userHome != null) {
      return userHome.findFileByRelativePath(".pyenv/versions");
    }
    return null;
  }

  public static VirtualFile getDefaultLocation() {
    final String path = System.getenv().get("WORKON_HOME");
    if (!StringUtil.isEmpty(path)) {
      return LocalFileSystem.getInstance().findFileByPath(FileUtil.expandUserHome(path).replace('\\','/'));
    }

    final VirtualFile userHome = LocalFileSystem.getInstance().findFileByPath(SystemProperties.getUserHome().replace('\\','/'));
    if (userHome != null) {
      final VirtualFile predefinedFolder = userHome.findChild(".virtualenvs");
      if (predefinedFolder == null)
        return userHome;
      return predefinedFolder;
    }
    return null;
  }

  public static Collection<String> findInDirectory(VirtualFile rootDir) {
    List<String> candidates = new ArrayList<>();
    if (rootDir != null) {
      rootDir.refresh(true, false);
      VirtualFile[] suspects = rootDir.getChildren();
      for (VirtualFile child : suspects) {
        if (child.isDirectory()) {
          final VirtualFile bin = child.findChild("bin");
          final VirtualFile scripts = child.findChild("Scripts");
          if (bin != null) {
            final String interpreter = findInterpreter(bin);
            if (interpreter != null) candidates.add(interpreter);
          }
          if (scripts != null) {
            final String interpreter = findInterpreter(scripts);
            if (interpreter != null) candidates.add(interpreter);
          }
        }
      }
    }
    return candidates;
  }

  @Nullable
  private static String findInterpreter(VirtualFile dir) {
    for (VirtualFile child : dir.getChildren()) {
      if (!child.isDirectory()) {
        final String childName = child.getName().toLowerCase();
        for (String name : NAMES) {
          if (SystemInfo.isWindows) {
            if (childName.equals(name)) {
              return FileUtil.toSystemDependentName(child.getPath());
            }
          }
          else {
            if (childName.startsWith(name) || PYTHON_RE.matcher(childName).matches()) {
              if (!childName.endsWith("-config")) {
                return child.getPath();
              }
            }
          }
        }
      }
    }
    return null;
  }

  @Override
  public boolean isValidSdkPath(@NotNull File file) {
    if (!super.isValidSdkPath(file)) return false;
    return PythonSdkType.getVirtualEnvRoot(file.getPath()) != null;
  }

  @Override
  public Icon getIcon() {
    return PythonIcons.Python.Virtualenv;
  }
}

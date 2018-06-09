/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.java.codeInspection
import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.codeInsight.Nullability
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInspection.dataFlow.DfaUtil
import com.intellij.codeInspection.dataFlow.inference.JavaSourceInference
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.PsiMethodImpl
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import org.jetbrains.annotations.Contract

import static com.intellij.codeInsight.Nullability.*
/**
 * @author peter
 */
abstract class NullityInferenceFromSourceTestCase extends LightCodeInsightFixtureTestCase {

  void "test return string literal"() {
    assert inferNullability(parse('String foo() { return "a"; }')) == NOT_NULL
  }

  void "test return null"() {
    assert inferNullability(parse('String foo() { return null; }')) == NULLABLE
  }

  void "test primitive return type"() {
    assert inferNullability(parse('int foo() { return "z"; }')) == UNKNOWN
  }

  void "test delegation"() {
    assert inferNullability(parse('String foo() { return bar(); }; String bar() { return "z"; }; ')) == NOT_NULL
  }

  void "test same delegate method invoked twice"() {
    assert inferNullability(parse('''
String foo() { 
  if (equals(2)) return bar();
  if (equals(3)) return bar();
  return "abc"; 
}
String bar() { return "z"; }
''')) == NOT_NULL
  }

  void "test unknown wins over a single delegate"() {
    assert inferNullability(parse('''
String foo() { 
  if (equals(3)) return bar();
  return smth; 
}
String bar() { return "z"; }
''')) == UNKNOWN
  }

  void "test if branch returns null"() {
    assert inferNullability(parse('String bar() { if (equals(2)) return null; return "a"; }; ')) == NULLABLE
  }

  void "test ternary branch returns null"() {
    assert inferNullability(parse('String bar() { return equals(2) ? "a" : equals(3) ? null : "a"; }; ')) == NULLABLE
  }

  void "test ternary branch notnull"() {
    assert inferNullability(parse('String bar() { return equals(2) ? "a" : equals(3) ? "b" : "c"; }; ')) == NOT_NULL
  }

  void "test type cast notnull"() {
    assert inferNullability(parse('String foo() { return (String)bar(); }; Object bar() { return "a"; }; ')) == NOT_NULL
  }

  void "test string concatenation"() {
    assert inferNullability(parse('String bar(String s1, String s2) { return s1 + s2; }; ')) == NOT_NULL
  }

  void "test delegation to nullable means nothing"() {
    assert inferNullability(parse('String foo() { return bar("2"); }; String bar(String s) { if (s != "2") return null; return "a"; }; ')) == UNKNOWN
  }

  void "test return boxed boolean constant"() {
    assert inferNullability(parse('Object foo() { return true; }')) == NOT_NULL
  }

  void "test return boxed boolean value"() {
    assert inferNullability(parse('Object foo(Object o) { return o == null; }')) == NOT_NULL
  }

  void "test return boxed integer"() {
    assert inferNullability(parse('Object foo() { return 1; }')) == NOT_NULL
  }

  void "test null inside lambda"() {
    assert inferNullability(parse('Object foo() { return () -> { return null; }; }')) == NOT_NULL
  }

  void "test in presence of explicit null contract"() {
    assert inferNullability(parse('''
@Contract("null->null")
Object foo(Object o) { if (o == null) return null; return 2; }
''')) == UNKNOWN
  }
  void "test in presence of inferred null contract"() {
    assert inferNullability(parse('''
Object foo(Object o) { if (o == null) return null; return 2; }
''')) == UNKNOWN
  }

  void "test in presence of fail contract"() {
    assert inferNullability(parse('''
@Contract("null->fail")
Object foo(Object o) { if (o == null) return o.hashCode(); return 2; }
''')) == NOT_NULL
  }

  void "test returning instanceof-ed variable via statement"() {
    assert inferNullability(parse('''
    String foo(Object o) { 
      if (o instanceof String) return ((String)o); 
      return "abc"; 
    }''')) == NOT_NULL
  }

  void "test returning instanceof-ed variable via ternary"() {
    assert inferNullability(parse('String foo(Object o) { return o instanceof String ? (String)o : "abc"; }')) == NOT_NULL
  }
  
  void "test System exit"() {
    assert inferNullability(parse('String foo(Object obj) {try {return bar();} catch(Exception ex) {System.exit(1);return null;}}')) == UNKNOWN
  }

  void "test System exit2"() {
    assert inferNullability(parse('String foo(boolean b) {' +
                                  'if(b) return null;' +
                                  'try {x();} ' +
                                  'catch(Exception ex) {System.exit(1);return null;}' +
                                  'return "xyz".trim();' +
                                  '}')) == UNKNOWN
  }

  protected abstract Nullability inferNullability(PsiMethod method)

  protected PsiMethod parse(String method) {
    return myFixture.addClass("import org.jetbrains.annotations.*; final class Foo { $method }").methods[0]
  }

  static class LightInferenceTest extends NullityInferenceFromSourceTestCase {
    Nullability inferNullability(PsiMethod method) {
      def file = (PsiFileImpl)method.containingFile
      assert !file.contentsLoaded
      def result = NullableNotNullManager.getInstance(method.getProject()).findEffectiveNullability(method)
      assert !file.contentsLoaded

      // check inference works same on both light and real AST
      WriteCommandAction.runWriteCommandAction(project) {
        file.viewProvider.document.insertString(0, ' ')
        PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
      assert method.node
      assert result == JavaSourceInference.inferNullability(method as PsiMethodImpl)
      return result
    }

    void "test skip when errors"() {
      assert inferNullability(parse('String foo() { if(); return 2; } ')) == UNKNOWN
    }

    void "test no nullable annotation in presence of inferred null contract"() {
      def method = parse('Object foo(Object o) { if (o == null) return null; return 2; }')
      def annos = InferredAnnotationsManager.getInstance(project).findInferredAnnotations(method)
      assert annos.collect { it.qualifiedName } == [Contract.name]
    }

  }

  static class DfaInferenceTest extends NullityInferenceFromSourceTestCase {
    Nullability inferNullability(PsiMethod method) {
      return DfaUtil.inferMethodNullability(method)
    }
  }
}

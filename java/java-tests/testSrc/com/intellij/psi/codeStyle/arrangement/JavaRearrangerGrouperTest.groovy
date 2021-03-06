/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.psi.codeStyle.arrangement

import org.junit.Test

import static com.intellij.psi.codeStyle.arrangement.std.StdArrangementTokens.Grouping.*
import static com.intellij.psi.codeStyle.arrangement.std.StdArrangementTokens.Modifier.PUBLIC
import static com.intellij.psi.codeStyle.arrangement.std.StdArrangementTokens.Order.BREADTH_FIRST
import static com.intellij.psi.codeStyle.arrangement.std.StdArrangementTokens.Order.DEPTH_FIRST

/**
 * @author Denis Zhdanov
 * @since 9/18/12 11:19 AM
 */
class JavaRearrangerGrouperTest extends AbstractJavaRearrangerTest {
  
  void setUp() {
    super.setUp()
    commonSettings.BLANK_LINES_AROUND_METHOD = 0
  }
  
  void "test getters and setters"() {
    commonSettings.BLANK_LINES_AROUND_METHOD = 1
    
    doTest(
            initial: '''\
class Test {
  public void setValue(int i) {}
  protected void util() {}
  public int getValue() { return 1; }
}''',
            expected: '''\
class Test {
  public int getValue() { return 1; }

  public void setValue(int i) {}

  protected void util() {}
}''',
      groups: [group(GETTERS_AND_SETTERS)],
      rules: [rule(PUBLIC)]
    )
  }
  
  @Test
  void "test utility methods depth-first"() {
    doTest(
      initial: '''\
class Test {
  void util1() { util11(); }
  void service1() { util1(); }
  void util2() {}
  void util11() {}
  void service2() { util2(); }
}''',
      groups: [group(DEPENDENT_METHODS, DEPTH_FIRST)],
      expected: '''\
class Test {
  void service1() { util1(); }
  void util1() { util11(); }
  void util11() {}
  void service2() { util2(); }
  void util2() {}
}''')
  }

  @Test
  void "test utility methods breadth-first"() {
    doTest(
      initial: '''\
class Test {
  void util2() { util3(); }
  void service1() { util1(); util2(); }
  void service2() { util2(); util1(); }
  void util3() {}
}''',
      groups: [group(DEPENDENT_METHODS, BREADTH_FIRST)],
      expected: '''\
class Test {
  void service1() { util1(); util2(); }
  void util2() { util3(); }
  void util3() {}
  void service2() { util2(); util1(); }
}''')
  }

  void "test overridden methods"() {
    doTest(
      initial: '''\
class Base {
  void base1() {}
  void base2() {}
}

<range>class Sub extends Base {
  void base2() {}
  void test1() {}
  void base1() {}</range>
  void test2() {}
}''',
      groups: [group(OVERRIDDEN_METHODS)],
      expected: '''\
class Base {
  void base1() {}
  void base2() {}
}

class Sub extends Base {
  void test1() {}
  void base1() {}
  void base2() {}
  void test2() {}
}''')
  }
  void "test overriden and utility methods"() {
    doTest(
      initial: '''\
class Base {
  void base1() {}
  void base2() {}
}

<range>class Sub extends Base {
  void test3() { test4(); }
  void base2() { test3(); }
  void test2() {}
  void base1() { test1(); }
  void test4() {}
  void test1() { test2(); }</range>
}''',
      groups: [group(DEPENDENT_METHODS, DEPTH_FIRST), group(OVERRIDDEN_METHODS)],
      expected: '''\
class Base {
  void base1() {}
  void base2() {}
}

class Sub extends Base {
  void base1() { test1(); }
  void test1() { test2(); }
  void test2() {}
  void base2() { test3(); }
  void test3() { test4(); }
  void test4() {}
}''')
  }

  void "test that calls from anonymous class create a dependency"() {
    doTest(
      initial: '''
class Test {
  void test2() {}
  void test1() { test2(); }
  void root() {
    new Runnable() {
      public void run() {
        test1();
      }
    }.run();
  }
}''',
      groups: [group(DEPENDENT_METHODS, DEPTH_FIRST)],
      expected: '''
class Test {
  void root() {
    new Runnable() {
      public void run() {
        test1();
      }
    }.run();
  }
  void test1() { test2(); }
  void test2() {}
}'''
    )
  }
}

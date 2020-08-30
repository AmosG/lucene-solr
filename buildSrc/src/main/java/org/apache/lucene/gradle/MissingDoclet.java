/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.gradle;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import com.sun.source.util.DocTrees;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import jdk.javadoc.doclet.StandardDoclet;

/**
 * Checks for missing javadocs, where missing also means "only whitespace" or "license header".
 * Has option --missing-level (package, class, method) so that we can improve over time.
 * Has option --missing-ignore to ignore individual elements (such as split packages).
 * Has option --missing-method to apply "method" level to selected packages (fix one at a time).
 */
public class MissingDoclet extends StandardDoclet {
  private static final int PACKAGE = 0;
  private static final int CLASS = 1;
  private static final int METHOD = 2;
  int level = METHOD;
  Reporter reporter;
  DocletEnvironment docEnv;
  DocTrees docTrees;
  Elements elementUtils;
  Set<String> ignored = Collections.emptySet();
  Set<String> methodPackages = Collections.emptySet();
  
  @Override
  public Set<Doclet.Option> getSupportedOptions() {
    Set<Doclet.Option> options = new HashSet<>();
    options.addAll(super.getSupportedOptions());
    options.add(new Doclet.Option() {
      @Override
      public int getArgumentCount() {
        return 1;
      }

      @Override
      public String getDescription() {
        return "level to enforce for missing javadocs: [package, class, method]";
      }

      @Override
      public Kind getKind() {
        return Option.Kind.STANDARD;
      }

      @Override
      public List<String> getNames() {
        return Collections.singletonList("--missing-level");
      }

      @Override
      public String getParameters() {
        return "level";
      }

      @Override
      public boolean process(String option, List<String> arguments) {
        switch(arguments.get(0)) {
          case "package":
            level = PACKAGE;
            return true;
          case "class":
            level = CLASS;
            return true;
          case "method":
            level = METHOD;
            return true;
          default:
            return false;
        }
      }
    });
    options.add(new Doclet.Option() {
      @Override
      public int getArgumentCount() {
        return 1;
      }

      @Override
      public String getDescription() {
        return "comma separated list of element names to ignore (e.g. as a workaround for split packages)";
      }

      @Override
      public Kind getKind() {
        return Option.Kind.STANDARD;
      }

      @Override
      public List<String> getNames() {
        return Collections.singletonList("--missing-ignore");
      }

      @Override
      public String getParameters() {
        return "ignoredNames";
      }

      @Override
      public boolean process(String option, List<String> arguments) {
        ignored = new HashSet<>(Arrays.asList(arguments.get(0).split(",")));
        return true;
      }
    });
    options.add(new Doclet.Option() {
      @Override
      public int getArgumentCount() {
        return 1;
      }

      @Override
      public String getDescription() {
        return "comma separated list of packages to check at 'method' level";
      }

      @Override
      public Kind getKind() {
        return Option.Kind.STANDARD;
      }

      @Override
      public List<String> getNames() {
        return Collections.singletonList("--missing-method");
      }

      @Override
      public String getParameters() {
        return "packages";
      }

      @Override
      public boolean process(String option, List<String> arguments) {
        methodPackages = new HashSet<>(Arrays.asList(arguments.get(0).split(",")));
        return true;
      }
    });
    return options;
  }

  @Override
  public void init(Locale locale, Reporter reporter) {
    this.reporter = reporter;
    super.init(locale, reporter);
  }

  @Override
  public boolean run(DocletEnvironment docEnv) {
    this.docEnv = docEnv;
    this.docTrees = docEnv.getDocTrees();
    this.elementUtils = docEnv.getElementUtils();
    for (var element : docEnv.getIncludedElements()) {
      check(element);
    }

    return super.run(docEnv);
  }
  
  /**
   * Returns effective check level for this element
   */
  private int level(Element element) {
    String pkg = elementUtils.getPackageOf(element).getQualifiedName().toString();
    if (methodPackages.contains(pkg)) {
      return METHOD;
    } else {
      return level;
    }
  }
  
  /** 
   * Check an individual element.
   * This checks packages and types from the doctrees.
   * It will recursively check methods/fields from encountered types when the level is "method"
   */
  private void check(Element element) {
    switch(element.getKind()) {
      case MODULE:
        // don't check the unnamed module, it won't have javadocs
        if (!((ModuleElement)element).isUnnamed()) {
          checkComment(element);
        }
        break;
      case PACKAGE:
        checkComment(element);
        break;
      // class-like elements, check them, then recursively check their children (fields and methods)
      case CLASS:
      case INTERFACE:
      case ENUM:
      case ANNOTATION_TYPE:
        if (level(element) >= CLASS) {
          checkComment(element);
          for (var subElement : element.getEnclosedElements()) {
            // don't check enclosed types, otherwise we'll double-check since they are in the included docTree
            if (subElement.getKind() == ElementKind.METHOD || 
                subElement.getKind() == ElementKind.CONSTRUCTOR || 
                subElement.getKind() == ElementKind.FIELD || 
                subElement.getKind() == ElementKind.ENUM_CONSTANT) {
              check(subElement);
            }
          }
        }
        break;
      // method-like elements, check them if we are configured to do so
      case METHOD:
      case CONSTRUCTOR:
      case FIELD:
      case ENUM_CONSTANT:
        if (level(element) >= METHOD && !isOverridden(element) && !isSyntheticEnumMethod(element)) {
          checkComment(element);
        }
        break;
      default:
        error(element, "I don't know how to analyze " + element.getKind() + " yet.");
    }
  }
  
  /** Return true if the method is annotated with Override, if so, don't require javadocs (they'll be copied) */
  private boolean isOverridden(Element element) {
    for (var annotation : element.getAnnotationMirrors()) {
      if (annotation.getAnnotationType().toString().equals(Override.class.getName())) {
        return true;
      }
    }
    return false;
  }
  
  /** 
   * Return true if the method is synthetic enum method (values/valueOf).
   * According to the doctree documentation, the "included" set never includes synthetic elements.
   * UweSays: It should not happen but it happens!
   */
  private boolean isSyntheticEnumMethod(Element element) {
    if (element.getSimpleName().toString().equals("values") || element.getSimpleName().toString().equals("valueOf")) {
      if (element.getEnclosingElement().getKind() == ElementKind.ENUM) {
        return true;
      }
    }
    return false;
  }
  
  /**
   * Checks that an element doesn't have missing javadocs.
   * In addition to truly "missing", check that comments aren't solely whitespace (generated by some IDEs),
   * that they aren't a license header masquerading as a javadoc comment.
   */
  private void checkComment(Element element) {
    if (!docEnv.isIncluded(element)) {
      return;
    }
    if (ignored.contains(element.toString())) {
      return;
    }
    var tree = docTrees.getDocCommentTree(element);
    if (tree == null || tree.getFirstSentence().isEmpty()) {
      error(element, "javadocs are missing");
    } else {
      var normalized = tree.getFirstSentence().get(0).toString()
                       .replace('\u00A0', ' ')
                       .trim()
                       .toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
        error(element, "blank javadoc comment");
      } else if (normalized.startsWith("licensed to the apache software foundation") ||
                 normalized.startsWith("copyright 2004 the apache software foundation")) {
        error(element, "comment is really a license");
      }
    }
  }
  
  /** logs a new error for the particular element */
  private void error(Element element, String message) {
    var fullMessage = new StringBuilder();
    switch(element.getKind()) {
      case MODULE:
      case PACKAGE:
        // for modules/packages, we don't have filename + line number, fully qualify
        fullMessage.append(element.toString());
        break;
      case METHOD:
      case CONSTRUCTOR:
      case FIELD:
      case ENUM_CONSTANT:
        // for method-like elements, include the enclosing type to make it easier
        fullMessage.append(element.getEnclosingElement().getSimpleName());
        fullMessage.append(".");
        fullMessage.append(element.getSimpleName());
        break;
      default:
        // for anything else, use a simple name
        fullMessage.append(element.getSimpleName());
        break;
    }
    fullMessage.append(" (");
    fullMessage.append(element.getKind().toString().toLowerCase(Locale.ROOT));
    fullMessage.append("): ");
    fullMessage.append(message);
    reporter.print(Diagnostic.Kind.ERROR, element, fullMessage.toString());
  }
}

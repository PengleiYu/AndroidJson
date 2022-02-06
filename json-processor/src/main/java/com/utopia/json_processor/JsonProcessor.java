package com.utopia.json_processor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import com.utopia.json_annotation.Json;

@SupportedOptions("key1")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
public class JsonProcessor extends AbstractProcessor {

  private Messager mMessager;
  private Types mTypeUtils;
  private Elements mElementUtils;
  private Filer mFiler;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    mMessager = processingEnv.getMessager();
    mTypeUtils = processingEnv.getTypeUtils();
    mElementUtils = processingEnv.getElementUtils();
    mFiler = processingEnv.getFiler();

    Map<String, String> options = processingEnv.getOptions();
    note("注解处理器options=" + options);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton(Json.class.getCanonicalName());
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    note("json process called");

    Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Json.class);
    note("annotations=" + annotations + ",elements=" + elements);
    for (Element e : elements) {
      if (!(e instanceof TypeElement)) {
        error("注解不支持非type类型");
        continue;
      }
      oneJson((TypeElement) e);
    }

    return false;
  }

  private void oneJson(TypeElement element) {
    List<VariableElement> variableElements = element.getEnclosedElements()
        .stream().filter(it -> it instanceof VariableElement)
        .map(it -> (VariableElement) it)
        .collect(Collectors.toList());
    if (variableElements.isEmpty()) return;

    // 1，创建类空间
    ClassName targetClz = getTargetJsonClzName(element);
    note(targetClz);
    TypeSpec.Builder builder = TypeSpec.classBuilder(targetClz)
        .addModifiers(Modifier.PUBLIC);
    // 2，添加代理方法
    // 3，添加实现方法
    // 4，生成文件
    TypeSpec typeSpec = builder.build();
    try {
      JavaFile.builder(mElementUtils.getPackageOf(element).toString(), typeSpec)
          .build()
          .writeTo(mFiler);
    } catch (IOException e) {
      e.printStackTrace();
      error(e.getLocalizedMessage(), element);
    }
  }

  private ClassName getTargetJsonClzName(TypeElement element) {
    ClassName typeClz = ClassName.get(element);
    return ClassName.get(typeClz.packageName(), typeClz.simpleName() + "Json");
  }

  private void note(Object obj) {
    mMessager.printMessage(Diagnostic.Kind.OTHER, obj + "");
  }

  private void warning(String msg) {
    mMessager.printMessage(Diagnostic.Kind.WARNING, msg);
  }

  private void error(String msg) {
    mMessager.printMessage(Diagnostic.Kind.ERROR, msg);
  }

  private void error(String msg, Element element) {
    mMessager.printMessage(Diagnostic.Kind.ERROR, msg, element);
  }
}
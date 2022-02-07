package com.utopia.json_processor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
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
  
  private Map<TypeName, String> mJsonOptMap;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    mMessager = processingEnv.getMessager();
    mTypeUtils = processingEnv.getTypeUtils();
    mElementUtils = processingEnv.getElementUtils();
    mFiler = processingEnv.getFiler();

    mJsonOptMap = getJsonOptMap();

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
    // 0，判断是否需要生成
    Result result = checkCondition(element);
    if (!result.success) {
      warning("类型检查失败: " + result.msg, element);
      return;
    }

    // 1，创建类空间
    ClassName targetClz = getTargetJsonClzName(element);
    note(targetClz);
    TypeSpec.Builder builder = TypeSpec.classBuilder(targetClz)
        .addModifiers(Modifier.PUBLIC);
    // 3，添加实现方法
    MethodSpec methodImpl = getImplMethod(ClassName.get(element), getVariables(element));
    builder.addMethod(methodImpl);
    // 2，添加代理方法
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

  private List<? extends VariableElement> getVariables(TypeElement element) {
    return element.getEnclosedElements()
        .stream().filter(it -> it instanceof VariableElement)
        .map(it -> (VariableElement) it)
        .collect(Collectors.toList());
  }

  private Result checkCondition(TypeElement element) {
    // 需要有变量
    long countVars = getVariables(element).size();
    if (countVars == 0) return Result.fail("没有成员变量");

    // 需要有无参构造方法
    long countConstructorNoParam = element.getEnclosedElements().stream()
        .filter(it -> it.getKind() == ElementKind.CONSTRUCTOR)
        .filter(it -> ((ExecutableElement) it).getParameters().isEmpty())
        .count();
    if (countConstructorNoParam != 1) return Result.fail("没有无参构造方法");
    return Result.success();
  }

  private MethodSpec getImplMethod(ClassName returnType, List<? extends VariableElement> variables) {
    // TODO: 2022/2/7 待抽取常量json、bean
    ParameterSpec parameterSpec = ParameterSpec.builder(Constants.CLZ_JSON_OBJECT, "json")
        .build();
    CodeBlock blockInitField = getInitFieldBlock(variables);

    CodeBlock codeBlock = CodeBlock.builder()
        .addStatement("$T bean = new $T()", returnType, returnType)
        .beginControlFlow("if (json == null)")
        .addStatement("return bean")
        .endControlFlow()
        .add(blockInitField)
        .addStatement("return bean")
        .build();

    return MethodSpec.methodBuilder("fromJson")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addParameter(parameterSpec)
        .returns(returnType)
        .addCode(codeBlock)
        .build();
  }

  private CodeBlock getInitFieldBlock(List<? extends VariableElement> variables) {
    CodeBlock.Builder builder = CodeBlock.builder();

    for (VariableElement var : variables) {
      // TODO: 2022/2/8 考虑支持gson的别名注解
      String varName = var.getSimpleName().toString();
      TypeName typeName = ClassName.get(var.asType());
      // 1， 基本类型、包装类型、String
      if (mJsonOptMap.containsKey(typeName)) {
        String jsonOptType = Objects.requireNonNull(mJsonOptMap.get(typeName));
        TypeName castType = typeName.isBoxedPrimitive() ? typeName.unbox() : typeName;

        builder.beginControlFlow("if($L.has($S))", "json", varName)
            .addStatement("$L.$L = ($L)$L.opt$L($S)",
                "bean", varName, castType, "json", jsonOptType, varName)
            .endControlFlow();
      }
    }
    return builder.build();
  }

  private Map<TypeName, String> getJsonOptMap() {
    Map<TypeName, String> jsonOptTypeMap = new HashMap<>();
    jsonOptTypeMap.put(TypeName.get(String.class), "String");
    jsonOptTypeMap.put(TypeName.BOOLEAN, "Boolean");
    jsonOptTypeMap.put(TypeName.CHAR, "Int");
    jsonOptTypeMap.put(TypeName.BYTE, "Int");
    jsonOptTypeMap.put(TypeName.SHORT, "Int");
    jsonOptTypeMap.put(TypeName.INT, "Int");
    jsonOptTypeMap.put(TypeName.LONG, "Long");
    jsonOptTypeMap.put(TypeName.FLOAT, "Double");
    jsonOptTypeMap.put(TypeName.DOUBLE, "Double");
    jsonOptTypeMap.put(TypeName.BOOLEAN.box(), "Boolean");
    jsonOptTypeMap.put(TypeName.CHAR.box(), "Int");
    jsonOptTypeMap.put(TypeName.BYTE.box(), "Int");
    jsonOptTypeMap.put(TypeName.SHORT.box(), "Int");
    jsonOptTypeMap.put(TypeName.INT.box(), "Int");
    jsonOptTypeMap.put(TypeName.LONG.box(), "Long");
    jsonOptTypeMap.put(TypeName.FLOAT.box(), "Double");
    jsonOptTypeMap.put(TypeName.DOUBLE.box(), "Double");
    return jsonOptTypeMap;
  }

  private ClassName getTargetJsonClzName(TypeElement element) {
    ClassName typeClz = ClassName.get(element);
    return ClassName.get(typeClz.packageName(), typeClz.simpleName() + "Json");
  }

  private void note(Object obj) {
    mMessager.printMessage(Diagnostic.Kind.OTHER, obj + "");
  }

  private void note(Object obj, Element element) {
    mMessager.printMessage(Diagnostic.Kind.OTHER, obj + "", element);
  }

  private void warning(String msg) {
    mMessager.printMessage(Diagnostic.Kind.WARNING, msg);
  }

  private void warning(String msg, Element element) {
    mMessager.printMessage(Diagnostic.Kind.WARNING, msg, element);
  }

  private void error(String msg) {
    mMessager.printMessage(Diagnostic.Kind.ERROR, msg);
  }

  private void error(String msg, Element element) {
    mMessager.printMessage(Diagnostic.Kind.ERROR, msg, element);
  }
}
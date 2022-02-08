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
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ArrayTypeName;
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
    TypeSpec.Builder builder = TypeSpec.classBuilder(targetClz)
        .addModifiers(Modifier.PUBLIC);
    // 3，添加实现方法
    MethodSpec methodImpl = getImplMethod(ClassName.get(element), getVariables(element));
    builder.addMethod(methodImpl);
    // 2，添加代理方法
    // 4，生成文件
    TypeSpec typeSpec = builder.build();
    try {
      String packageName = mElementUtils.getPackageOf(element).toString();
      JavaFile.builder(packageName, typeSpec)
          .skipJavaLangImports(true)
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
    String methodName = Constants.METHOD_NAME_FROM_JSON;
    String varBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    ParameterSpec parameterSpec = ParameterSpec.builder(Constants.CLZ_JSON_OBJECT, paramJson)
        .build();
    CodeBlock blockInitField = getInitFieldBlock(variables);

    CodeBlock codeBlock = CodeBlock.builder()
        .addStatement("$T $L = new $T()", returnType, varBean, returnType)
        .beginControlFlow("if ($L == null)", paramJson)
        .addStatement("return $L", varBean)
        .endControlFlow()
        .add(blockInitField)
        .addStatement("return $L", varBean)
        .build();

    return MethodSpec.methodBuilder(methodName)
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addParameter(parameterSpec)
        .returns(returnType)
        .addCode(codeBlock)
        .build();
  }

  private CodeBlock getInitFieldBlock(List<? extends VariableElement> fields) {
    CodeBlock.Builder builder = CodeBlock.builder();

    String methodName = Constants.METHOD_NAME_FROM_JSON;
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;
    for (VariableElement fieldElement : fields) {
      // TODO: 2022/2/8 考虑支持gson的别名注解
      String fieldName = fieldElement.getSimpleName().toString();
      TypeMirror typeMirror = fieldElement.asType();
      TypeName typeName = TypeName.get(typeMirror);
      // 1，简单类型： 基本类型、包装类型、String、JSONObject、JSONArray
      if (mJsonOptMap.containsKey(typeName)) {
        String jsonOptType = Objects.requireNonNull(mJsonOptMap.get(typeName));
        TypeName castType = typeName.isBoxedPrimitive() ? typeName.unbox() : typeName;

        builder.beginControlFlow("if($L.has($S))", paramJson, fieldName)
            .addStatement("$L.$L = ($L)$L.opt$L($S)",
                localBean, fieldName, castType, paramJson, jsonOptType, fieldName)
            .endControlFlow();
      }
      // 2, 数组类型
      else if (typeName instanceof ArrayTypeName) {
        ArrayTypeName arrName = (ArrayTypeName) typeName;
        TypeName componentType = arrName.componentType;
        // 2.1 简单类型
        String jsonOptType = mJsonOptMap.get(componentType);
        ClassName clzJsonArray = Constants.CLZ_JSON_ARRAY;
        if (jsonOptType != null) {
          TypeName componentCastType = componentType.isBoxedPrimitive()
              ? componentType.unbox() : componentType;
          String localJsonArr = "jsonArr";
          builder.beginControlFlow("if($L.has($S))", paramJson, fieldName)
              .addStatement("$T $L = $L.opt$L($S)",
                  clzJsonArray, localJsonArr, paramJson, "JSONArray", fieldName)
              .addStatement("$T arr = new $T[$L.length()]", arrName, componentType, localJsonArr)
              .beginControlFlow("for(int i=0;i<$L.length();i++)", localJsonArr)
              .addStatement("arr[i]=($T)$L.opt$L(i)", componentCastType, localJsonArr, jsonOptType)
              .endControlFlow()
              .addStatement("$L.$L=arr", localBean, fieldName)
              .endControlFlow();
        } else {
          warning("不支持的数组类型: " + typeName, fieldElement);
        }
      } else {
        warning("不支持的字段类型:" + typeName, fieldElement);
        warning(typeName.getClass().toString());
      }
    }
    return builder.build();
  }

  private Map<TypeName, String> getJsonOptMap() {
    Map<TypeName, String> map = new HashMap<>();
    map.put(TypeName.get(String.class), "String");
    map.put(TypeName.BOOLEAN, "Boolean");
    map.put(TypeName.CHAR, "Int");
    map.put(TypeName.BYTE, "Int");
    map.put(TypeName.SHORT, "Int");
    map.put(TypeName.INT, "Int");
    map.put(TypeName.LONG, "Long");
    map.put(TypeName.FLOAT, "Double");
    map.put(TypeName.DOUBLE, "Double");
    map.put(TypeName.BOOLEAN.box(), "Boolean");
    map.put(TypeName.CHAR.box(), "Int");
    map.put(TypeName.BYTE.box(), "Int");
    map.put(TypeName.SHORT.box(), "Int");
    map.put(TypeName.INT.box(), "Int");
    map.put(TypeName.LONG.box(), "Long");
    map.put(TypeName.FLOAT.box(), "Double");
    map.put(TypeName.DOUBLE.box(), "Double");

    map.put(Constants.CLZ_JSON_ARRAY, "JSONArray");
    map.put(Constants.CLZ_JSON_OBJECT, "JSONObject");
    return map;
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
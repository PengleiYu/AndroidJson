package com.utopia.json_processor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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
import javax.lang.model.type.DeclaredType;
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
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import com.utopia.json_annotation.Json;

import jdk.internal.jline.internal.Nullable;

@SupportedOptions("key1")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
public class JsonProcessor extends AbstractProcessor {

  private Helper mHelper;
  private Messager mMessager;
  private Types mTypeUtils;
  private Elements mElementUtils;
  private Filer mFiler;
  private Map<TypeName, String> mJsonOptMap;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    mHelper = new Helper(processingEnv);
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
    return fields.stream()
        .map(this::dispatchFieldProcess)
        .filter(Objects::nonNull)
        .collect(CodeBlock::builder, CodeBlock.Builder::add, ((b1, b2) -> b1.add(b2.build())))
        .build();
  }

  private CodeBlock dispatchFieldProcess(VariableElement fieldElement) {
    TypeMirror typeMirror = fieldElement.asType();
    // TODO: 2022/2/8 考虑支持gson的别名注解
    TypeName typeName = TypeName.get(typeMirror);
    // 1，简单类型： 基本类型、包装类型、String、JSONObject、JSONArray
    if (mJsonOptMap.containsKey(typeName)) {
      return processSimpleField(fieldElement);
    }
    // 2, 数组类型
    else if (typeName instanceof ArrayTypeName) {
      return processArrayField(fieldElement);
    }
    // 3，集合类型
    else if (mHelper.isAssignable(fieldElement, Constants.CLASS_COLLECTION)) {
      return processCollectionField(fieldElement);
    }
    // 4，map类型
    else if (mHelper.isAssignable(fieldElement, Constants.CLASS_MAP)) {
      return processMapField(fieldElement);
    } else {
      warning("不支持的字段类型:" + typeName, fieldElement);
      warning(typeName.getClass().toString());
      return null;
    }
  }

  @Nullable
  private CodeBlock processMapField(VariableElement fieldElement) {
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    note("processMap: " + fieldElement + ",type: " + fieldElement.asType());
    ClassName clzImpl;
    if (mHelper.isAssignable(Constants.CLASS_HASH_MAP, fieldElement)) {
      clzImpl = Constants.CLASS_HASH_MAP;
    } else {
      warning("不支持的map类型", fieldElement);
      return null;
    }

    TypeName chosenSecondParamType = chooseMapSecondParamType(fieldElement);
    if (chosenSecondParamType == null) {
      // 已在内部处理提示，这里直接返回
      return null;
    }
    note("chooseSecondParamType: " + chosenSecondParamType);
    String optType = mJsonOptMap.get(chosenSecondParamType);
    if (optType == null) {
      warning("map泛型的第二个参数是不支持的类型:" + chosenSecondParamType, fieldElement);
      return null;
    }
    TypeName chooseSecondParamCastType = chosenSecondParamType.isBoxedPrimitive()
        ? chosenSecondParamType.unbox() : chosenSecondParamType;

    return CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldElement)
        .addStatement("$T map=new $T()", Map.class, clzImpl)
        .addStatement("$T jsonObj=$L.$L($S)",
            Constants.CLZ_JSON_OBJECT, paramJson, Constants.FUNCTION_OPT_JSON_OBJECT, fieldElement)
        .addStatement("$T<String> keys = jsonObj.keys()", Iterator.class)
        .beginControlFlow("while (keys.hasNext())")
        .addStatement("String next = keys.next()")
        .addStatement("$T value = ($T)jsonObj.opt$L(next)",
            chosenSecondParamType, chooseSecondParamCastType, optType)
        .addStatement("map.put(next,value)")
        .addStatement("$L.$L = map", localBean, fieldElement)
        .endControlFlow()
        .endControlFlow()
        .build();
  }

  private TypeName chooseMapSecondParamType(VariableElement mapFieldElement) {
    if (!(mapFieldElement.asType() instanceof DeclaredType)) {
      warning("map类型不是declaredType", mapFieldElement);
      return null;
    }

    List<? extends TypeMirror> typeArguments = ((DeclaredType) mapFieldElement.asType()).getTypeArguments();
    if (typeArguments.size() > 0 && typeArguments.size() != 2) {
      warning("map的泛型数量不正确", mapFieldElement);
      return null;
    }
    if (typeArguments.size() == 0) {
      note("无泛型map，视为String类型的key，Object类型的value");
      return TypeNames.OBJECT;
    }

    TypeName typeName0 = TypeName.get(typeArguments.get(0));
    boolean isStringIndex0 = TypeNames.STRING.equals(typeName0);
    if (!isStringIndex0) {
      warning("map泛型第一个参数不是String类型", mapFieldElement);
      return null;
    }
    TypeName typeName1 = TypeName.get(typeArguments.get(1));
    TypeName chooseSecondParamType;
    // 2.1 普通类
    if (typeName1 instanceof ClassName) {
      note("map泛型第二个参数类型是className");
      chooseSecondParamType = typeName1;
    }
    // 2.2 泛型
    else if (typeName1 instanceof TypeVariableName) {
      note(" map泛型第二个参数是typeVar");
      List<TypeName> bounds = ((TypeVariableName) typeName1).bounds;
//      note("bounds=" + bounds);
      if (bounds.isEmpty()) {
        warning("map泛型第二个参数必须指明泛型上界", mapFieldElement);
        return null;
      }
      if (bounds.size() > 1) {
        warning("map泛型第二个参数仅支持一个上界", mapFieldElement);
        return null;
      }
      chooseSecondParamType = bounds.get(0);
    }
    // 2.3 通配符
    else if (typeName1 instanceof WildcardTypeName) {
      List<TypeName> lowerBounds = ((WildcardTypeName) typeName1).lowerBounds;
      List<TypeName> upperBounds = ((WildcardTypeName) typeName1).upperBounds;
      if (!lowerBounds.isEmpty()) {
        warning("map泛型第二个参数不支持泛型下界", mapFieldElement);
        return null;
      }
      if (upperBounds.isEmpty()) {
        warning("map泛型第二个参数没有明确指定上界", mapFieldElement);
        return null;
      }
      if (upperBounds.size() > 1) {
        warning("map泛型第二个参数不支持多个上界", mapFieldElement);
        return null;
      }
      chooseSecondParamType = upperBounds.get(0);
    } else {
      warning("map泛型第二个参数是不支持的类型:" + typeName1, mapFieldElement);
      return null;
    }
    return chooseSecondParamType;
  }

  @Nullable
  private CodeBlock processSimpleField(VariableElement fieldElement) {
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    TypeName typeName = TypeName.get(fieldElement.asType());

    String jsonOptType = mJsonOptMap.get(typeName);
    if (jsonOptType == null) {
      warning("不是简单类型", fieldElement);
      return null;
    }

    TypeName castType = typeName.isBoxedPrimitive() ? typeName.unbox() : typeName;

    return CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldElement)
        .addStatement("$L.$L = ($L)$L.opt$L($S)",
            localBean, fieldElement, castType, paramJson, jsonOptType, fieldElement)
        .endControlFlow()
        .build();
  }

  @Nullable
  private CodeBlock processArrayField(VariableElement fieldElement) {
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;
    TypeName typeName = TypeName.get(fieldElement.asType());
    if (!(typeName instanceof ArrayTypeName)) {
      warning("不是数组类型", fieldElement);
      return null;
    }

    TypeName componentType = ((ArrayTypeName) typeName).componentType;
    // 2.1 简单类型
    String jsonOptType = mJsonOptMap.get(componentType);
    ClassName clzJsonArray = Constants.CLZ_JSON_ARRAY;
    if (jsonOptType != null) {
      TypeName componentCastType = componentType.isBoxedPrimitive()
          ? componentType.unbox() : componentType;
      String localJsonArr = "jsonArr";
      return CodeBlock.builder()
          .beginControlFlow("if($L.has($S))", paramJson, fieldElement)
          .addStatement("$T $L = $L.opt$L($S)",
              clzJsonArray, localJsonArr, paramJson, "JSONArray", fieldElement)
          .addStatement("$T arr = new $T[$L.length()]", typeName, componentType, localJsonArr)
          .beginControlFlow("for(int i=0;i<$L.length();i++)", localJsonArr)
          .addStatement("arr[i]=($T)$L.opt$L(i)", componentCastType, localJsonArr, jsonOptType)
          .endControlFlow()
          .addStatement("$L.$L=arr", localBean, fieldElement)
          .endControlFlow()
          .build();
    } else {
      // TODO: 2022/2/8 更详细的提示信息
      warning("不支持的数组类型: " + typeName, fieldElement);
      return null;
    }
  }

  @Nullable
  private CodeBlock processCollectionField(VariableElement fieldElement) {
    ClassName clzImpl;
    if (mHelper.isAssignable(Constants.CLASS_HASH_SET, fieldElement)) {
      clzImpl = Constants.CLASS_HASH_SET;
    } else if (mHelper.isAssignable(Constants.CLASS_ARRAY_LIST, fieldElement)) {
      clzImpl = Constants.CLASS_ARRAY_LIST;
    } else if (mHelper.isAssignable(Constants.CLASS_LINKED_LIST, fieldElement)) {
      clzImpl = Constants.CLASS_LINKED_LIST;
    } else {
      warning("不支持的集合类型", fieldElement);
      return null;
    }

    TypeMirror typeMirror = fieldElement.asType();
    if (!(typeMirror instanceof DeclaredType)) {
      warning("集合类型element is not DeclaredType", fieldElement);
      return null;
    }
    List<? extends TypeMirror> typeArguments = ((DeclaredType) typeMirror).getTypeArguments();
    if (typeArguments.size() > 1) {
      warning("泛型参数过多，无法处理", fieldElement);
      return null;
    }

    if (!typeArguments.isEmpty()) {
      TypeName componentType = TypeName.get(typeArguments.get(0));
      if (!mJsonOptMap.containsKey(componentType)) {
        warning("不支持的集合泛型", fieldElement);
        return null;
      }
    }

    TypeName componentType = typeArguments.isEmpty()
        ? TypeNames.OBJECT : TypeName.get(typeArguments.get(0));
    String jsonOptType = mJsonOptMap.get(componentType);
    // 简单类型
    if (jsonOptType == null) {
      error("WTF，不应为null", fieldElement);
    }

    TypeName componentCastType = componentType.isBoxedPrimitive()
        ? componentType.unbox() : componentType;

    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    CodeBlock.Builder builder = CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldElement)
        .addStatement("$T collection=new $T()", fieldElement, clzImpl)
        .addStatement("$T jsonArr=$L.$L($S)",
            Constants.CLZ_JSON_ARRAY, paramJson, Constants.FUNCTION_OPT_JSON_ARRAY, fieldElement)
        .beginControlFlow("for(int i=0;i<jsonArr.length();i++)")
        .addStatement("collection.add(($L)jsonArr.opt$L(i))", componentCastType, jsonOptType)
        .endControlFlow()
        .addStatement("$L.$L=collection", localBean, fieldElement)
        .endControlFlow();
    return builder.build();
  }

  private Map<TypeName, String> getJsonOptMap() {
    Map<TypeName, String> map = new HashMap<>();
    map.put(TypeNames.OBJECT, "");
    map.put(TypeNames.STRING, "String");
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
package com.utopia.json_processor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
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

    note("processMap: " + fieldElement);
    ClassName clzImpl;
    if (mHelper.isAssignable(Constants.CLASS_HASH_MAP, fieldElement)) {
      clzImpl = Constants.CLASS_HASH_MAP;
    } else {
      warning("不支持的map类型", fieldElement);
      return null;
    }

    if (!(fieldElement.asType() instanceof DeclaredType)) {
      warning("map类型不是declaredType", fieldElement);
      return null;
    }

    List<? extends TypeMirror> typeArguments = ((DeclaredType) fieldElement.asType()).getTypeArguments();
    if (typeArguments.size() > 0 && typeArguments.size() != 2) {
      warning("map的泛型数量不正确", fieldElement);
      return null;
    }
    if (typeArguments.size() > 0) {
      warning("不支持泛型map", fieldElement);
      return null;
    }
//    boolean hasGeneric = !typeArguments.isEmpty();

    CodeBlock codeBlock = CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldElement)
        .addStatement("$T map=new $T()", Map.class, clzImpl)
        .addStatement("$T jsonObj=$L.$L($S)",
            Constants.CLZ_JSON_OBJECT, paramJson, Constants.FUNCTION_OPT_JSON_OBJECT, fieldElement)
        .addStatement("$T<String> keys = jsonObj.keys()", Iterator.class)
        .beginControlFlow("while (keys.hasNext())")
        .addStatement("String next = keys.next()")
        .addStatement("Object value = jsonObj.opt(next)")
        .addStatement("map.put(next,value)")
        .addStatement("$L.$L = map", localBean, fieldElement)
        .endControlFlow()
        .endControlFlow()
        .build();

    return codeBlock;
  }

  @Nullable
  private CodeBlock processSimpleField(VariableElement fieldElement) {
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    TypeName typeName = TypeName.get(fieldElement.asType());

    String fieldName = fieldElement.getSimpleName().toString();
    String jsonOptType = mJsonOptMap.get(typeName);
    if (jsonOptType == null) {
      warning("不是简单类型", fieldElement);
      return null;
    }

    TypeName castType = typeName.isBoxedPrimitive() ? typeName.unbox() : typeName;

    return CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldName)
        .addStatement("$L.$L = ($L)$L.opt$L($S)",
            localBean, fieldName, castType, paramJson, jsonOptType, fieldName)
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

    String fieldName = fieldElement.getSimpleName().toString();
    TypeName componentType = ((ArrayTypeName) typeName).componentType;
    // 2.1 简单类型
    String jsonOptType = mJsonOptMap.get(componentType);
    ClassName clzJsonArray = Constants.CLZ_JSON_ARRAY;
    if (jsonOptType != null) {
      TypeName componentCastType = componentType.isBoxedPrimitive()
          ? componentType.unbox() : componentType;
      String localJsonArr = "jsonArr";
      return CodeBlock.builder().beginControlFlow("if($L.has($S))", paramJson, fieldName)
          .addStatement("$T $L = $L.opt$L($S)",
              clzJsonArray, localJsonArr, paramJson, "JSONArray", fieldName)
          .addStatement("$T arr = new $T[$L.length()]", typeName, componentType, localJsonArr)
          .beginControlFlow("for(int i=0;i<$L.length();i++)", localJsonArr)
          .addStatement("arr[i]=($T)$L.opt$L(i)", componentCastType, localJsonArr, jsonOptType)
          .endControlFlow()
          .addStatement("$L.$L=arr", localBean, fieldName)
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

    boolean hasGeneric = typeArguments.size() == 1;
    boolean supportedGeneric = true;
    if (!typeArguments.isEmpty()) {
      TypeName componentType = TypeName.get(typeArguments.get(0));
      supportedGeneric = mJsonOptMap.containsKey(componentType);
    }

    if (!supportedGeneric) {
      warning("不支持的集合泛型", fieldElement);
      return null;
    }
    Supplier<CodeBlock> statementSupplier = () -> {
      // 2.1 无泛型
      if (!hasGeneric) {
        return CodeBlock.builder()
            .addStatement("collection.add(jsonArr.opt(i))")
            .build();
      }
      // 2.2 泛型
      else {
        TypeName componentType = TypeName.get(typeArguments.get(0));
        String jsonOptType = mJsonOptMap.get(componentType);
        // 2.2.1 简单类型
        if (jsonOptType == null) {
          error("WTF，不应为null", fieldElement);
        }

        TypeName componentCastType = componentType.isBoxedPrimitive()
            ? componentType.unbox() : componentType;
        return CodeBlock.builder()
            .addStatement("collection.add(($L)jsonArr.opt$L(i))", componentCastType, jsonOptType)
            .build();
      }
    };

    return getCollectionCodeBlock(fieldElement, clzImpl, statementSupplier);
  }

  private CodeBlock getCollectionCodeBlock(VariableElement fieldElement,
                                           ClassName clzImpl,
                                           Supplier<CodeBlock> statementCollectionAdd) {
    String fieldName = fieldElement.getSimpleName().toString();
    String localBean = Constants.METHOD_FROM_JSON_LOCAL_VAR_BEAN;
    String paramJson = Constants.METHOD_FROM_JSON_PARAM_KEY_JSON;

    CodeBlock.Builder builder = CodeBlock.builder()
        .beginControlFlow("if($L.has($S))", paramJson, fieldName)
        .addStatement("$T collection=new $T()", fieldElement, clzImpl)
        .addStatement("$T jsonArr=$L.$L($S)",
            Constants.CLZ_JSON_ARRAY, paramJson, Constants.FUNCTION_OPT_JSON_ARRAY, fieldName)
        .beginControlFlow("for(int i=0;i<jsonArr.length();i++)")
        .add(statementCollectionAdd.get())
        .endControlFlow()
        .addStatement("$L.$L=collection", localBean, fieldName)
        .endControlFlow();
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
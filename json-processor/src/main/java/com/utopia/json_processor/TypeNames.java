package com.utopia.json_processor;

import java.util.Map;

import com.squareup.javapoet.TypeName;

public class TypeNames {
  public static final TypeName STRING = TypeName.get(String.class);
  public static final TypeName OBJECT = TypeName.get(Object.class);
  public static final TypeName MAP = TypeName.get(Map.class);
}

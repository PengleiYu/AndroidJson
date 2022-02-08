package com.utopia.json_processor;

import com.squareup.javapoet.ClassName;

public class Constants {
  public static final String PKG_JSON = "org.json";
  public static final ClassName CLZ_JSON_OBJECT = ClassName.get(PKG_JSON, "JSONObject");
  public static final ClassName CLZ_JSON_ARRAY = ClassName.get(PKG_JSON, "JSONArray");
  public static final ClassName CLZ_JSON_EXCEPTION = ClassName.get(PKG_JSON, "JSONException");

  public static final String METHOD_NAME_FROM_JSON = "fromJson";
  public static final String METHOD_FROM_JSON_PARAM_KEY_JSON = "json";
  public static final String METHOD_FROM_JSON_LOCAL_VAR_BEAN = "bean";

  public static final ClassName CLASS_COLLECTION = ClassName.get("java.util", "Collection");
  public static final ClassName CLASS_LIST = ClassName.get("java.util", "List");
  public static final ClassName CLASS_ARRAY_LIST = ClassName.get("java.util", "ArrayList");
  public static final ClassName CLASS_SET = ClassName.get("java.util", "Set");
  public static final ClassName CLASS_HASH_SET = ClassName.get("java.util", "HashSet");
  public static final ClassName CLASS_QUEUE = ClassName.get("java.util", "Queue");
  public static final ClassName CLASS_LINKED_LIST = ClassName.get("java.util", "LinkedList");
  public static final ClassName CLASS_MAP = ClassName.get("java.util", "Map");
  public static final ClassName CLASS_HASH_MAP = ClassName.get("java.util", "HashMap");

  public static final String FUNCTION_OPT_JSON_ARRAY = "optJSONArray";
  public static final String FUNCTION_OPT_JSON_OBJECT = "optJSONObject";
}

package com.utopia.json_processor;

import com.squareup.javapoet.ClassName;

public class Constants {
  public static final String PKG_JSON = "org.json";
  public static final ClassName CLZ_JSON_OBJECT = ClassName.get(PKG_JSON, "JSONObject");
  public static final ClassName CLZ_JSON_ARRAY = ClassName.get(PKG_JSON, "JSONArray");
  public static final ClassName CLZ_JSON_EXCEPTION = ClassName.get(PKG_JSON, "JSONException");
}
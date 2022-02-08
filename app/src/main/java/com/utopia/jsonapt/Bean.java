package com.utopia.jsonapt;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.utopia.json_annotation.Json;

@Json
public class Bean extends BaseBean {
  public String fString;
  public boolean fBoolean;
  public Boolean fBoolean2;
  public short fShort;
  public Short fShort2;
  public int fInt;
  public Integer fInt2;
  public float fFloat;
  public Float fFloat2;

  public JSONObject fJsonObj;
  public JSONArray fJsonArray;

  public int[] fIntArr;
  public Integer[] fIntArr2;

  public float[] fFloatArr;
  public Float[] fFloatArr2;

  public JSONObject[] fJsonObjArr;
  public JSONArray[] fJsonArrArr;

  public Bean[] fBeanArr;

  public List<String> fListStr;

  public Bean(String fString) {
    this.fString = fString;
  }

  public Bean() {
  }

  public void method1() {
  }
}

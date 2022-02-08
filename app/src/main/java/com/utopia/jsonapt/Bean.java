package com.utopia.jsonapt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

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

  // 不支持嵌套泛型
  public Bean[] fBeanArr;

  // 常用集合，带泛型
  public Collection<Integer> fCollectionInt;
  public List<String> fListStr;
  public Set<Float> fSetFloat;
  public Queue<Character> fQueueChar;

  // 不支持的嵌套泛型
  public Set<Bean> fSetBean;

  // 无泛型集合
  public Collection fCollectionNoType;
  public Set fSetNotType;

  // 不支持非常用集合
  public TreeSet<String> fTreeSetStr;

  // 无泛型map
  public Map fMap;

  // 通配符泛型map
  public Map<String, ? extends Number> fMapStrInt;

  public Bean(String fString) {
    this.fString = fString;
  }

  public Bean() {
  }

  public void method1() {
  }
}

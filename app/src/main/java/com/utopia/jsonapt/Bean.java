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
public class Bean<
    // 支持的单一上界
    T extends Integer,
    // 不支持的单一上界
    A extends Number,
    // 不支持的多上界
    B extends Integer & Iterable<Float>
    > extends BaseBean {
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
  public Map<String, ?> fMapString;
  // 泛型上界map
  public Map<String, ? extends Number> fMapStrExtendsNumber;
  public Map<String, ? extends Float> fMapStrExtendsFloat;
  // 泛型下界map
  public Map<String, ? super Integer> fMapStrSuperInt;
  // 泛型多个上界
  public Map<String, T> fMapStrT;
  public Map<String, A> fMapStrA;
  public Map<String, B> fMapStrB;

  public Bean(String fString) {
    this.fString = fString;
  }

  public Bean() {
  }

  public void method1() {
  }
}

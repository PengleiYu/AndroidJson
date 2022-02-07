package com.utopia.jsonapt;

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

  public Bean(String fString) {
    this.fString = fString;
  }

  public Bean() {
  }

  public void method1() {
  }
}

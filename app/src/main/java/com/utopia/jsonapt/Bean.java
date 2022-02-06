package com.utopia.jsonapt;

import com.utopia.json_annotation.Json;

@Json
public class Bean  extends BaseBean{
  public String fString;

  public Bean(String fString) {
    this.fString = fString;
  }

  public Bean() {
  }

  public void method1() {
  }
}

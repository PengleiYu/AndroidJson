package com.utopia.json_processor;

public class Result {
  public final boolean success;
  public final String msg;

  public Result(boolean success, String msg) {
    this.success = success;
    this.msg = msg;
  }

  public static Result success() {
    return new Result(true, "");
  }

  public static Result fail(String msg) {
    return new Result(false, msg);
  }
}

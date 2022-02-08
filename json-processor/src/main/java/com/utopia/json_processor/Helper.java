package com.utopia.json_processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.squareup.javapoet.ClassName;

public class Helper {
  private final Elements mElementUtils;
  private final Types mTypeUtils;

  public Helper(ProcessingEnvironment environment) {
    mElementUtils = environment.getElementUtils();
    mTypeUtils = environment.getTypeUtils();
  }

  public boolean isSubType(Element element, ClassName parentClzName) {
    return mTypeUtils.isSubtype(
        mTypeUtils.erasure(element.asType()),
        mTypeUtils.erasure(mElementUtils.getTypeElement(parentClzName.canonicalName()).asType())
    );
  }

  public boolean isSameType(Element element, ClassName parentClzName) {
    return mTypeUtils.isSameType(
        mTypeUtils.erasure(element.asType()),
        mTypeUtils.erasure(mElementUtils.getTypeElement(parentClzName.canonicalName()).asType())
    );
  }

  public boolean isAssignable(Element element, ClassName parentClzName) {
    return mTypeUtils.isAssignable(
        mTypeUtils.erasure(element.asType()),
        mTypeUtils.erasure(mElementUtils.getTypeElement(parentClzName.canonicalName()).asType())
    );
  }

  public boolean isAssignable(ClassName className, Element element) {
    return mTypeUtils.isAssignable(
        mTypeUtils.erasure(mElementUtils.getTypeElement(className.canonicalName()).asType()),
        mTypeUtils.erasure(element.asType())
    );
  }
}

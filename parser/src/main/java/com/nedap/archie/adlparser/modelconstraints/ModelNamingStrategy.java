package com.nedap.archie.adlparser.modelconstraints;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Naming strategy for models. Default implementation exists for the Archie Reference Model implementation.
 *
 * Other implementations can be made for other reference model implementations.
 *
 * Created by pieter.bos on 26/03/16.
 */
public interface ModelNamingStrategy {

    String getRMAttributeName(Class clazz, Method getMethod);
    public String getRMAttributeName(Field field);
    String getRMTypeName(Class clazz);

}

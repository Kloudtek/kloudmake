/*
 * Copyright (c) 2015. Kelewan Technologies Ltd
 */

package com.kloudtek.kloudmake;

import com.kloudtek.kloudmake.annotation.Default;
import com.kloudtek.kloudmake.annotation.Param;
import com.kloudtek.kloudmake.dsl.Parameter;
import com.kloudtek.kloudmake.exception.InvalidServiceException;
import com.kloudtek.kloudmake.exception.KMRuntimeException;
import com.kloudtek.kloudmake.exception.MethodInvocationException;
import com.kloudtek.kloudmake.util.ReflectionHelper;
import com.kloudtek.util.StringUtils;
import org.apache.commons.beanutils.ConvertUtils;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MethodInvoker {
    private java.lang.String serviceName;
    private final java.lang.String name;
    private final Method method;
    private final int paramCount;
    private ArrayList<java.lang.String> paramsOrder = new ArrayList<>();
    private LinkedHashMap<java.lang.String, MethodParam> paramsMap = new LinkedHashMap<>();

    public MethodInvoker(java.lang.String serviceName, java.lang.String name, Method method) throws InvalidServiceException {
        this.serviceName = serviceName;
        this.name = name;
        this.method = method;
        Annotation[][] annotations = method.getParameterAnnotations();
        paramCount = annotations.length;
        for (int i = 0; i < annotations.length; i++) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Annotation[] anlist = annotations[i];
            MethodParam param = new MethodParam(method, parameterTypes[i], anlist);
            paramsMap.put(param.name, param);
            paramsOrder.add(param.name);
        }
    }

    public Object invoke(KMContextImpl ctx, @NotNull Parameters params) throws KMRuntimeException {
        Object[] plist = new Object[paramCount];
        List<Parameter> parameters = params.getParameters();
        if (parameters.size() > paramCount) {
            throw new MethodInvocationException("Too many arguments provided when calling method " + name);
        }
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            String parameterValue = p.eval(ctx, null);
            plist[i] = ConvertUtils.convert(parameterValue, paramsMap.get(paramsOrder.get(i)).type);
        }
        for (Map.Entry<java.lang.String, Parameter> entry : params.getNamedParameters().entrySet()) {
            int idx = paramsOrder.indexOf(entry.getKey());
            if (idx == -1) {
                throw new MethodInvocationException("Invalid argument " + entry.getKey() + " provided when calling method " + name);
            }
            if (plist[idx] != null) {
                throw new MethodInvocationException("Multiple assignments of argument " + entry.getKey() + " when calling method " + name);
            }
            String paramValue = entry.getValue().eval(ctx, null);
            Class<?> type = paramsMap.get(paramsOrder.get(idx)).type;
            plist[idx] = ConvertUtils.convert(paramValue, type);
        }
        for (int i = 0; i < plist.length; i++) {
            if (plist[i] == null) {
                MethodParam methodParam = paramsMap.get(paramsOrder.get(i));
                String def = methodParam.def;
                if (def != null) {
                    plist[i] = ConvertUtils.convert(def, methodParam.type);
                }
            }
        }
        try {
            Object service = ctx.getServiceManager().getService(serviceName);
            if (service == null) {
                throw new KMRuntimeException("BUG: Couldn't find service " + serviceName);
            }
            return method.invoke(service, plist);
        } catch (IllegalAccessException | InvocationTargetException | InvalidServiceException e) {
            throw new KMRuntimeException(e.getMessage(), e);
        }
    }

    public class MethodParam {
        private java.lang.String name;
        private java.lang.String def;
        private Class<?> type;

        public MethodParam(Method method, Class<?> type, Annotation[] annotations) throws InvalidServiceException {
            this.type = type;
            for (Annotation annotation : annotations) {
                if (annotation instanceof Param) {
                    if (name != null) {
                        throw new InvalidServiceException(ReflectionHelper.toString(method) + " has more than one @Function annotation");
                    }
                    name = ((Param) annotation).value();
                    String defVal = ((Param) annotation).def();
                    if (StringUtils.isNotEmpty(defVal)) {
                        def = defVal;
                    }
                } else if (annotation instanceof Default) {
                    if (def != null) {
                        throw new InvalidServiceException(ReflectionHelper.toString(method) + " has more than one @Default annotation");
                    }
                    def = ((Default) annotation).value();
                }
            }
            if (name == null) {
                throw new InvalidServiceException("No @Function specified for " + ReflectionHelper.toString(method));
            }
        }
    }
}

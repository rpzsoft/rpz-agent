package com.rpzsoft.agent.common;

/**
 * 
 */
public class CallTracer {
    //TODO: to see other threads use Thread.currentThread().getStackTrace()[2].getClassName()
    public static String caller(){
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String clz = stack[2].getClassName();
        return clz.substring(clz.lastIndexOf('.')+1) +"." +stack[2].getMethodName()+"["+stack[2].getLineNumber()+"]";
    }

    public static String callerMethod(){
        StackTraceElement[] stack = new Throwable().getStackTrace();
        return stack[2].getMethodName()+"["+stack[2].getLineNumber()+"]";
    }

    //this is callee
    public static String currentMethod(){
        StackTraceElement[] stack = new Throwable().getStackTrace();
        return stack[1].getMethodName()+"["+stack[1].getLineNumber()+"]";
    }

    //this is callee
    public static String callee(){
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String clz = stack[1].getClassName();
        return clz.substring(clz.lastIndexOf('.')+1) +"." +stack[1].getMethodName()+"["+stack[1].getLineNumber()+"]";
    }

    public static String line(){
        return "["+new Throwable().getStackTrace()[1].getLineNumber()+"]";
    }
}

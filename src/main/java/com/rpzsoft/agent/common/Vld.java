

package com.rpzsoft.agent.common;

import java.util.*;

/**
 * 
 */
public class Vld {

    //TODO: can we generatae msgErr from the parameter name?
    public static boolean checkEmpty(String arg, String what){
        if (arg == null || arg.isEmpty()) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    //TODO: can we generatae msgErr from the parameter name?
    public static boolean checkNull(Object arg, String what){
        if (arg == null) throw new IllegalArgumentException(what+" cannot be null");
        return true;
    }

    public static boolean checkEmpty(Map arg, String what){
        if (arg == null || arg.size() == 0) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    public static boolean checkEmpty(Collection arg, String what){
        if (arg == null || arg.size() == 0) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    public static <T> boolean checkEmpty(T [] arg, String what){
        if (arg == null || arg.length == 0) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    public static boolean checkEmpty(byte [] arg, String what){
        if (arg == null || arg.length == 0) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    public static boolean checkEmpty(int [] arg, String what){
        if (arg == null || arg.length == 0) throw new IllegalArgumentException(what+" cannot be null/empty");
        return true;
    }

    public static boolean checkMatch(String arg1, String arg2, String what){
        if (arg1 == null || arg1.isEmpty()) throw new IllegalArgumentException(what+" cannot be null/empty");
        //if (arg2 == null || arg2.isEmpty()) throw new IllegalArgumentException(what+" cannot be null/empty");
        if(!arg1.equals(arg2)) throw new IllegalArgumentException(what+" does not match or is null/empty");
        return true;
    }

    public static boolean checkMSize(Collection arg, int size, String what){
        if (arg == null) throw new IllegalArgumentException(what+" cannot be null");
        if (arg.size() < size) throw new IllegalArgumentException(what+" cannot be "+size);
        return true;
    }

    public static boolean checkMSize(Object [] arg, int size, String what){
        if (arg == null) throw new IllegalArgumentException(what+" cannot be null");
        if (arg.length < size) throw new IllegalArgumentException(what+" cannot be "+size);
        return true;
    }

    public static boolean isEmpty(String arg){
        return (arg == null || arg.trim().isEmpty());
    }

    public static boolean isEmpty(Map arg){
        return (arg== null || arg.size() == 0);
    }

    public static boolean isEmpty(Collection arg){
        return (arg== null || arg.size() == 0);
    }

    public static <T> boolean isEmpty(T [] arg){
        return (arg== null || arg.length == 0);
    }

    public static boolean isEmpty(int [] arg){
        return (arg== null || arg.length == 0);
    }

    public static boolean isEmpty(byte [] arg){
        return (arg== null || arg.length == 0);
    }

    public static boolean isNull(Object arg){
        return arg== null;
    }
    public static void checkTrue(boolean arg, String what){
        if(!arg)
            throw new IllegalArgumentException(what+" cannot be false");
    }

    public static boolean isEmpty(Object arg){
        if(arg== null) return true;
        else if (arg instanceof Object []) return ((Object []) arg).length == 0;
        else if (arg instanceof Map) return ((Map) arg).size() == 0;
        else if (arg instanceof Collection) return ((Collection) arg).size() == 0;
        else if (arg instanceof String) return ((String) arg).trim().isEmpty();
        else if (arg instanceof Number) return false;
        throw new IllegalArgumentException("Size/Object type Unknown!");
    }

    public static boolean isMatch(String arg1, String arg2){
        return arg1 != null && !arg1.isEmpty() && arg1.equals(arg2);
    }

    private static boolean startWith(String what, String [] lst){
        for(String s:lst){
            if(what.startsWith(s)) return true;
        }
        return false;
    }


}

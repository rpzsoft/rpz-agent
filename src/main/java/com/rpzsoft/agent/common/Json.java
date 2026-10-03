package com.rpzsoft.agent.common;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

//null element are not allowed
public class Json extends LinkedHashMap<String, Object> implements Serializable, Iterable{

    static final Logger logger = LoggerFactory.getLogger(Json.class);
    List<Object> array = null;

    //status of a data that exchange between interfaces!
    //JsonMsg is the right solution for this but JsonMsg is a overkill for simple status return.
    transient Object msg;

    //    static final DateFormat df = new ISO8601DateFormat();
    /*
         mapper.configOverride(Date.class)
            .setFormat(JsonFormat.Value.forPattern("yyyy.dd.MM"));
     */

    /**
     * Streaming JSON parsing for efficiency. of cause it is not flexible but this is the best for this engine.
     */
    static final JsonFactory factory;
    static final JsonFactory yamlFactory;
    public static final ObjectMapper mapper;
    static {
        factory = new JsonFactory();
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        factory.setCodec(mapper);
        yamlFactory = new YAMLFactory();
    }

    static final String LINE_PATTERN = ".*(\r\n|[\n\r])|.+$"; //unicode style=> ".*(\r\n|[\n\r\u2028\u2029\u0085])|.+$";
    static final String SPACE_PATTERN = "\\s+";
    static final Pattern lineptr = Pattern.compile(LINE_PATTERN);
    static final Pattern spptr = Pattern.compile(SPACE_PATTERN);

    public Json() {
    }

    //TODO: perf - result in creating two objects - map & arraylist
    public Json(boolean isArray) {
        if(isArray)
            array = new ArrayList<>();
    }

    public Json(List<Object> array) {
        Vld.checkNull(array, "array");
        this.array = array;

    }

    public Json(Json js) {
        Vld.checkNull(js, "json");
        if(js.isArray()) array = new ArrayList<>(js.array);
        else putAll(js);
    }

    //FIXME: security - possible denial of service attack if we do not restrict the upper limit for the input data.
    public Json(String json) throws Exception {
        Json js = fromJson(json);
        if (js.isArray()) array = new ArrayList<>(js.array);
        else putAll(js);
    }

    /*
        Construct a json array from string data
        This code is performed optimally if the delimiter is a single character such as
        comma, space, tab, |, or user defined single character or one-or-more spaces with compiled pattern
        For multiple rows of strings, data must have line-feeds (LF) or/and carriage return (CRLF).

        FIXME: security - possible denial of service attack if we do not restrict the upper limit for the input data.

        FIXME: possible issue with List<> vs Json(true) in array
     */
    public Json(boolean isArray, String data, String delimiter) throws IOException {
        Vld.checkEmpty(data, "data");
        Vld.checkEmpty(delimiter, "delimiter");

        if(!isArray) throw new IllegalArgumentException("Cannot defined an array isArray=false");
        array = new ArrayList<>();

        //if the delimiter is a simple single char or scaped char
        Json lst = new Json(true);
        char ch;
        if (((delimiter.length() == 1 && ".$|()[{^?*+\\".indexOf(ch = delimiter.charAt(0)) == -1)
                || (delimiter.length() == 2 && delimiter.charAt(0) == '\\'
                && (((ch = delimiter.charAt(1))-'0')|('9'-ch)) < 0 && ((ch-'a')|('z'-ch)) < 0 && ((ch-'A')|('Z'-ch)) < 0))
                && (ch < Character.MIN_HIGH_SURROGATE || ch > Character.MAX_LOW_SURROGATE))    {

            int st=0;
            int len = data.length();
            int i=0;
            for(; i < len; i++){
                char cpos = data.charAt(i);
                if(cpos == ch){
                    lst.add(data.substring(st, i));
                    st = i+1;
                }else {
                    switch (cpos) {
                        case '\n':
                        case '\r':
                            lst.add(data.substring(st, i));
                            if(i + 1 < len && data.charAt(i+1) == '\n') i++;
                            st = i+1;
                            array.add(lst);
                            lst = new Json(true);
                            break;
                    }
                }
            }
            if(i == len && st < i) lst.add(data.substring(st, i));
        }else {
            //if the delimiter is a regex;
            String [] lines = lineptr.split(data);
            boolean spp = delimiter.equals(SPACE_PATTERN);
            for(String ln:lines){
                if(spp){
                    //use a compiled regex instead of dynamic regex that used by split(regex)
                    lst.add(spptr.split(data));
                }else{
                    lst.add(data.split(delimiter));
                }
            }
        }

        if(lst.size() > 0) {
            if(array.size() > 0) array.add(lst);
            else array = lst.array;
        }
    }

    public Json(InputStream jsons) throws Exception {
        Json js = fromJson(jsons);
        if(js.isArray()) array = js.array;
        else putAll(js);
    }

    //allow creating Json with name/value pairs
    public Json(Pair<String, Object> ... nv){
        Vld.checkNull(nv, "nvp");
        for(Pair<String, Object> itm:nv){
            put(itm.key, itm.value);
        }
    }

    //allow creating Json with name/value pairs
    public Json(String[] keys, Object[] values){
        Vld.checkNull(keys, "keys");
        Vld.checkNull(values, "values");
        if(keys.length != values.length)
            throw new IllegalArgumentException("argument must be key/value pairs, so must have equal number of keys/values");

        for(int i=0; i < keys.length; i++){
            put(keys[i], values[i]);
        }
    }

    //allow creating Json with key/value pairs. every other element in the argument are keys or values. all keys must be strings
    //e.g. new Json("a", 1, "b", 2) -> {"a":1, "b":2}.
    public Json(Object ... nv){
        Vld.checkNull(nv, "nvp");

        if (nv.length % 2 != 0) throw new IllegalArgumentException("argument must be key/value pairs");
        for (int i = 0; i < nv.length; i++) {
            //if(!(kv[i] instanceof String)) throw new IllegalArgumentException("keys must be strings");
            put((String) nv[i++], nv[i]);
        }
    }


    public Json(Map<String, String> mp) {
        Vld.checkNull(mp, "json");
        putAll(mp);
    }

    public static String jsonStr(String key, String val){
        Vld.checkNull(key, "key");
        return "{\""+key+"\":\""+escape(val)+"\"}";
    }

    public static String jsonStr(Object ...kv){
        if(kv.length == 0) return "{}";
        if(kv.length % 2 != 0) throw new IllegalArgumentException("argument must be key/value pairs");
        StringBuilder ret = new StringBuilder("{\"");
        boolean st=false;
        for(int i=0; i < kv.length; i++) {
            //if (!(kv[i] instanceof String)) throw new IllegalArgumentException("keys must be strings");
            if (st) ret.append(",\"");
            ret.append(kv[i++]).append("\":");
            if(kv[i] == null) {
                ret.append(kv[i]);
            }else if (kv[i] instanceof String){
                ret.append("\"").append(escape((String)kv[i])).append("\"");
            }else if(kv[i] instanceof Number || kv[i] instanceof Boolean){ //(!Float.isNaN(f) && !Float.isInfinite(f)))
                ret.append(String.valueOf(kv[i]));
            }else {
                ret.append(kv[i].toString());
            }
            st=true;
        }
        ret.append("}");
        return ret.toString();
    }

    public static String jsonArrStr(Object ...kv){
        if(kv.length == 0) return "[]";
        StringBuilder ret = new StringBuilder("[");
        boolean st=true;
        for(int i=0; i < kv.length; i++) {
            if (!st) ret.append(',');
            if(kv[i] == null) {
                ret.append(kv[i]);
            }else if (kv[i] instanceof String){
                ret.append("\"").append(escape((String)kv[i])).append("\"");
            }else if(kv[i] instanceof Number || kv[i] instanceof Boolean){ //(!Float.isNaN(f) && !Float.isInfinite(f)))
                ret.append(String.valueOf(kv[i]));
            }else {
                ret.append(kv[i].toString());
            }
            st=false;
        }
        ret.append("]");
        return ret.toString();
    }

    public static String escape(String str) {
        StringWriter out = new StringWriter(str.length() * 2);
        int sz = str.length();

        for(int i = 0; i < sz; ++i) {
            Character prvch = null;
            //Character pprvch = null;
            Character nextch = null;
            if(i!=0) {
                prvch = str.charAt(i-1);
                if(i+1!=sz) nextch = str.charAt(i+1);
                //if(i - 1 > 0) pprvch = str.charAt(i-2);
            }

            char ch = str.charAt(i);
            /*if(ch > 4095) {
                out.write("\\u" + hex(ch));
            } else if(ch > 255) {
                out.write("\\u0" + hex(ch));
            } else if(ch > 127) {
                out.write("\\u00" + hex(ch));
            } else */
            switch(ch) {
                case '\\':
                    if(nextch != null && nextch == '\\') break;
                case '\"':
                    //if(prvch != null && prvch == '\\') break;
                    out.write("\\"+ch); //'\'
                    break;
                //case '/':  out.write("\\/"); break;
                case '\b': out.write("\\b"); break;
                case '\f': out.write("\\f"); break;
                case '\r': out.write("\\r"); break;
                case '\t': out.write("\\t"); break;
                case '\n': out.write("\\n"); break;
                case '\u000b':
                default:
                    if (ch < 32){
                        if (ch > 15) {
                            out.write("\\u00" + hex(ch));
                        } else {
                            out.write("\\u000" + hex(ch));
                        }
                    }else out.write(ch);
            }
        }
        return out.toString();
    }

    private static String hex(char ch) {
        return Integer.toHexString(ch).toUpperCase(Locale.ENGLISH);
    }

    public static String escape0(String str) {
        return StringEscapeUtils.escapeJava(str);

        /*if(Vld.isEmpty(str)) return str;
         if(false) {//NoP
            final int len = str.length();
            char[] ret = new char[len * 2]; //max is twice as many
            int i = 0, j = 0;
            for (; i < len; i++) {
                char ch = str.charAt(i);
                //this is to avoid multiple time escape
                switch (ch) {
                    case '\\':
                        if (i + 1 >= len || i + 1 < len && str.charAt(i + 1) != 'u')
                            ret[j++] = '\\';
                        break;
                    case '\"':
                        ret[j++] = '\\';
                        break;
                }
             *//*if(ch == '\"' && (i == 0 || str.charAt(i-1) != '\\'))
                ret[j++] = '\\';*//*
                ret[j++] = ch;
            }

            return i == j ? str : new String(ret, 0, j);
        }
        return str;*/
    }

    /*static boolean  isescape(char ch){
        switch (ch){
            case '\"':
            case '\\':
            case '\n':
            case '\r':
            case '\f':
            case '\t':
            case '\b':
            case 'u': return true;
        }
        return false;
    }*/

    public boolean isArray(){
        return array != null;
    }

    private void checkArray(){
        if(!isArray()) throw new IllegalStateException("Not a Json array");
    }

    private void checkObject(){
        if(isArray()) throw new IllegalStateException("Not a Json Object");
    }

    public String getString(String key){
        Object v = get(key);

        if(v == null) return  null;
        if(v instanceof String)
            return (String) v;
        return v.toString();
    }

    public Integer getInt(String key){
        Object v = get(key);

        if(v == null) return  null;
        if(v instanceof Number)
            return ((Number) v).intValue(); //this can truncate if it's a double/float
        else if(v instanceof String)
            return Integer.parseInt((String) v);
        logger.error("Invalid type "+v.getClass());
        throw new NumberFormatException("Invalid type");
    }

    public Long getLong(String key){
        Object v = get(key);

        if(v == null) return  null;
        if(v instanceof Number)
            return ((Number) v).longValue(); //this can truncate if it's a double/float
        else if(v instanceof String)
            return Long.parseLong((String) v);
        logger.error("Invalid type "+v.getClass());
        throw new NumberFormatException("Invalid type");
    }

    public Number getNumber(String key){
        Object v = get(key);

        if(v == null) return  null;
        if(v instanceof Number)
            return (Number)v;
        else if(v instanceof String){
            String sv = (String)v;
            if(sv.indexOf('.') > 0)
                return Double.parseDouble(sv);
            else return Long.parseLong(sv);
        }
        logger.error("Invalid type "+v.getClass());
        throw new NumberFormatException("Invalid type");
    }

    public Boolean getBoolean(String key){
        return (Boolean) get(key);
    }

    public Double getDouble(String key){
        return (Double) get(key);
    }

    public Json getJson(String key){
        checkObject();
        return (Json) super.get(key);
    }

    //get Json object from an array
    public Json getJson(int idx){
        checkArray();
        return (Json) array.get(idx);
    }

    //json array (list of json objects)
    public List<Json> getJsonArray(String key){
        Object o = super.get(key);
        if(o == null) return null;
        if(o instanceof Json && ((Json)o).isArray())
            return (List<Json>)(Object)((Json)o).array;
        throw new IllegalStateException("Not a JSON  array");
    }

    //allow casting to any list type
    public List<Object> getArray(){
        checkArray();
        return array;
    }

    public List<?> getArray(String key){
        checkObject();
        Object o = get(key);
        if(o == null) return null;
        if(o instanceof Json && ((Json)o).isArray()) return ((Json)o).array;
        throw new IllegalStateException("Not an array");
    }

    //TDDO: not just JSON types! this also returns any object stored in the map, before serialization
    @Override
    public Object get(Object key) {
        return super.get(key);
        //Object o = super.get(key);
        //return (o != null && o instanceof Json && ((Json)o).isArray())?((Json)o).array: o;
    }

    //TDDO: not just JSON types! allow storing any object type in the map, before serialization
    public Object put(String key, Object value) {
        checkObject();
        return super.put(key, value);//(value instanceof Json && ((Json) value).isArray())?((Json) value).array:value);
    }

    public Json add(Object o){
        Vld.checkNull(o, "argument");
        checkArray();
        array.add(o);
        return this;
    }

    public Json add(Collection o){
        Vld.checkNull(o, "argument");
        checkArray();
        array.addAll(o);
        return this;
    }

    /*public Json add(Object ...o){
        Vld.checkNull(o, "argument");
        checkArray();
        array.add(o);
        return this;
    }*/

    public Json addAll(Object ...elm) {
        return add(Arrays.asList(elm));
    }


    public Json addAll(Collection c) {
        return add(c);
    }

    public Json addAll(int index, Collection c) {
        checkArray();
        array.addAll(index, c);
        return this;
    }


    public Json sort(Comparator c) {
        checkArray();
        array.sort(c);
        return this;
    }

    //sort json array having strings
    public Json sort(String key) {
        checkArray();
        if(array.isEmpty()) return this;
        if(!(array.get(0) instanceof Json))
            throw new ClassCastException("Cannot cast: "+array.get(0).getClass().getName() +" to Json");

        Comparator c = (o1, o2) ->((Json) o1).getString(key).toLowerCase().compareTo(((Json)o2).getString(key).toLowerCase());

        array.sort(c);
        return this;
    }

    /*
    Comparator c = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
                if(o1 instanceof Json && o2 instanceof Json)
                    return ((String) o1).compareTo((String)o2);
                else if(o1 instanceof String && o2 instanceof String)
                    return ((String) o1).compareTo((String)o2);
                else if(o1 instanceof Number && o2 instanceof Number){
                    double d1 = ((Number) o1).doubleValue();
                    double d2 = ((Number) o2).doubleValue();
                    return d1 > d2? 1: d1 == d2 ? 0 : -1;
                }
            }
        }
     */

    //array merge instead of array addition to the end of another array
    public Json merge(Json other){
        Vld.checkEmpty(other, "merge error input Json");
        if(isArray()){
            if(other.isArray()) {
                for(Object o: other) {
                    if(!contains(o))
                        add(o);
                }
            }else add(other);
        }else if(!other.isArray()) putAll(other);
        else throw new IllegalArgumentException("Json array cannot be merged with a Json object");
        return this;
    }

    public boolean removeAll(Collection c) {
        checkArray();
        return array.removeAll(c);
    }


    public boolean containsAll(Collection c) {
        checkArray();
        return array.containsAll(c);
    }

    public Object get(int i){
        checkArray();
        return array.get(i);
    }


    public Object set(int index, Object element) {
        checkArray();
        return array.set(index, element);
    }

    public void add(int index, Object element) {
        checkArray();
        array.add(index, element);
    }


    public Object remove(int index) {
        checkArray();
        return array.remove(index);
    }

    public void clear() {
        if(isArray())
            array = new ArrayList<>();
        else super.clear();
    }

    public int indexOf(Object o) {
        checkArray();
        return array.indexOf(o);
    }


    public int lastIndexOf(Object o) {
        checkArray();
        return array.lastIndexOf(o);
    }

    //Array can have items of any type
    public Iterator<Object> iterator(){
        checkArray();
        return array.iterator();
    }

    @Override
    public void forEach(Consumer action) {
        checkArray();
        array.forEach(action);
    }

    public List<Object> getList(){
        return array;
    }

    public <T> T[] toArray() {
        checkArray();
        return (T[]) array.toArray();
    }

    public <T> T[] toArray(T[] a) {
        checkArray();
        return array.toArray(a);
    }


    public boolean retainAll(Collection c) {
        checkArray();
        return array.retainAll(c);
    }


    public ListIterator listIterator() {
        checkArray();
        return array.listIterator();
    }


    public ListIterator listIterator(int index) {
        checkArray();
        return array.listIterator(index);
    }


    public List subList(int fromIndex, int toIndex) {
        checkArray();
        return array.subList(fromIndex, toIndex);
    }


    public void replaceAll(UnaryOperator operator) {
        checkArray();
        array.replaceAll(operator);
    }


    public Spliterator spliterator() {
        checkArray();
        return array.spliterator();
    }


    public boolean removeIf(Predicate filter) {
        checkArray();
        return array.removeIf(filter);
    }


    public Stream stream() {
        checkArray();
        return array.stream();
    }


    public Stream parallelStream() {
        checkArray();
        return array.parallelStream();
    }

//    /**
//    @deprecated recommended to use itself instead
//     */
//    @Deprecated
//    public List<Object> getArray() {
//        return array;
//    }
//
//    /**
//     * This returns a array as a list
//     * TODO: replace this with Json. To find all usage - \(List<[a-zA-Z\[\]]+>\).+\.get\(
//     * @deprecated recommended to use getJson(key) instead
//     */
//    @Deprecated
//    public List<Object> getArray(String key){
//        Object v = get(key);
//        return v!=null?(List.class.isAssignableFrom(v.getClass())? (List<Object>)v:null):null;
//    }

//    public Json setArray(List<Object> array) {
//        this.array = array;
//        return this;
//    }

//    public String _toJson() throws IOException {
//        StringWriter sw = new StringWriter();
//        try (JsonGenerator generator = factory.createGenerator(sw)) {
//            if(array != null) toJsonArray(generator, array);
//            else  toJson(generator, this);
//            generator.close();
//        }
//        return sw.toString();
//    }

    public String toJson(boolean ...pretty) throws IOException {
        Encoder jb = new Encoder().startEncode(array != null, array == null);
        if(pretty.length > 0) jb.setPretty();
        toJson(jb);
        return jb.endEncode(array == null);
    }

    public void toJson(Encoder jb) throws IOException {
        if(array != null) jb.toJson(array);
        else  jb.toJson(this);
    }

    public static <T> String toJson(Map<String, T> mp) throws IOException {
        Encoder jb = new Encoder().startEncode();
        for(Map.Entry<String, T> e: mp.entrySet()){
            jb.toJson(e.getKey(), (T)e.getValue());
        }
        return jb.endEncode();
    }

    public static <T> String toJson(List<T> lst) throws IOException {
        Encoder jb = new Encoder().startEncode(true, true);
        for (Object o: lst) {
            jb.toJson(o);
        }
        return jb.endEncode();
    }

    public static <T> String toJson(T [] lst) throws IOException {
        Encoder jb = new Encoder().startEncode(true, true);
        for (Object o: lst) {
            jb.toJson(o);
        }
        return jb.endEncode();
    }

    void toJson(JsonGenerator generator, Json js) throws IOException {
        generator.writeStartObject();
        for (Map.Entry<String, Object> e : js.entrySet()) {
            if(e.getValue() instanceof Json){
                generator.writeFieldName(e.getKey());
                toJson(generator, (Json)e.getValue());
                continue;
            }else if(e.getValue() instanceof List){
                generator.writeFieldName(e.getKey());
                toJsonArray(generator, (List) e.getValue());
                continue;
            }
            generator.writeObjectField(e.getKey(), e.getValue());
        }
        generator.writeEndObject();
    }

    void toJsonArray(JsonGenerator generator, List<Object> lst) throws IOException {
        generator.writeStartArray();
        for (Object o : lst) {
            if(o instanceof Json){
                toJson(generator, (Json)o);
            }else
                generator.writeObject(o);
        }
        generator.writeEndArray();
    }

    public static Json fromJson(byte [] json) throws Exception {
        Vld.checkEmpty(json, "json");
        Json top = null;

        try (JsonParser jp = factory.createParser(json)) {
            top = parse(jp, null);
            jp.close();
        }catch (JsonParseException e){
            logger.info("Failed at:{} - {} - \n[>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), new String(json));
            JsonLocation loc = e.getLocation();
            throw new IllegalArgumentException("Invalid JSON");//e.getOriginalMessage()+" at ln: "+loc.getLineNr()+", col:"+loc.getColumnNr()
        }catch (Exception e){
            logger.info("Failed at:{} - {} - [>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), new String(json));
            throw e;
        }

        return top;
    }

    public static Json fromJson(String json, boolean ... unescape)  {
        Vld.checkEmpty(json, "json");
        Json ret = null;
        //fail fast
        if(!ifJson(json))
            throw new IllegalArgumentException("Invalid JSON");

        //parse it
        boolean scaped = false;
        int loc = json.indexOf("\\\"");
        if(loc > 0 && (loc = json.indexOf("\\\"", loc+1)) > 0){
            scaped = true;
            //logger.warn("Json with escapes");
        }

        try (JsonParser jp = factory.createParser(json)) {
            ret = parse(jp, null);
            jp.close();
        }catch (JsonParseException e){
            //try with unescaping if any
            if(scaped && e.getMessage().indexOf("'\\' (code 92)") > 0 && unescape.length > 0 && unescape[0])
                return unescape(json);
            logger.info("Failed at:{} - {} - \n[>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), json);
            JsonLocation jloc = e.getLocation();
            throw new IllegalArgumentException("Invalid JSON at ln: "+jloc.getLineNr()+", col:"+jloc.getColumnNr());
        }catch (Exception e){
            //try with unescaping if any
            if(scaped && e.getMessage().indexOf("'\\' (code 92)") > 0 && unescape.length > 0 && unescape[0])
                return unescape(json);
            logger.info("Failed at:{} - {} - [>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), json);
            throw new IllegalArgumentException("Invalid JSON");
        }

        return ret;
    }

    static Json unescape(String json)  {
        char fst = json.charAt(0);
        char lst = json.charAt(json.length() - 1);
        if(fst != '{' && fst != '[' && lst != '}' && lst != ']')
            throw new IllegalArgumentException("Invalid JSON");

        final int len = json.length();
        char [] ret = new char[len * 2]; //just some enough cap
        int i=0, j=0;
        for(; i< len; i++){
            char ch = json.charAt(i);
            if(!(ch == '\\' && i + 1 < len && json.charAt(i+1) == '"'))
                ret[j++] = ch;
        }
        return Json.fromJson(new String (ret, 0 ,j));
    }

    public static boolean ifJson(String json){
        if(Vld.isEmpty(json)) return false;
        json = json.trim();
        char fst = json.charAt(0);
        char lst = json.charAt(json.length() - 1);
        return fst == '{' && lst == '}' || fst == '[' && lst == ']';
    }

    public static Json fromYaml(InputStream yaml) throws Exception {
        Vld.checkNull(yaml, "yaml");
        Json top = null;
        try (JsonParser jp = yamlFactory.createParser(yaml)){
            top = parse(jp, null);
            jp.close();
        }catch (JsonParseException e){
            logger.info("Failed at:{} - {} - \n[>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), yaml);
            JsonLocation loc = e.getLocation();
            throw new IllegalArgumentException("Invalid JSON");//e.getOriginalMessage()+" at ln: "+loc.getLineNr()+", col:"+loc.getColumnNr()
        }catch (Exception e){
            logger.info("Failed at:{} - {} - [>>>:\n{}\n:<<<]" ,  CallTracer.caller(), e.getMessage(), yaml);
            throw e;
        }
        return top;
    }

    public static Json fromJson(InputStream json) throws Exception {
        Vld.checkNull(json, "json");
        Json top = null;
        try (JsonParser jp = factory.createParser(json)){
            top = parse(jp, null);
            jp.close();
        }catch (JsonParseException e){
            logger.info("Failed at:{} - {} - \n[>>>:\n{}\n:<<<]" , CallTracer.caller(), e.getMessage(), json);
            JsonLocation loc = e.getLocation();
            throw new IllegalArgumentException("Invalid JSON");//e.getOriginalMessage()+" at ln: "+loc.getLineNr()+", col:"+loc.getColumnNr()
        }catch (Exception e){
            logger.info("Failed at:{} - {} - [>>>:\n{}\n:<<<]" ,  CallTracer.caller(), e.getMessage(), json);
            throw e;
        }
        return top;
    }

    public static Json fromJsonFile(String file) throws Exception {
        Vld.checkEmpty(file, "file");

        InputStream in = ClassLoader.getSystemResourceAsStream(file);
        if (in == null) {
            in = Json.class.getClassLoader().getResourceAsStream(file);
            if (in == null) throw new IllegalArgumentException("File [" + file + "] not found!");
        }
        return fromJson(in);
    }

    public static Json fromYamlFile(String file) throws Exception {
        Vld.checkEmpty(file, "file");

        InputStream in = ClassLoader.getSystemResourceAsStream(file);
        if (in == null) {
            in = Json.class.getClassLoader().getResourceAsStream(file);
            if (in == null) throw new IllegalArgumentException("File [" + file + "] not found!");
        }
        return fromYaml(in);
    }

    static Json parse(JsonParser jp, Json cur) throws IOException {
        String fieldName = null;

        while (!jp.isClosed()) {
            JsonToken jsonToken = jp.nextToken();
        /*    if(start && (jsonToken != JsonToken.START_OBJECT && jsonToken != JsonToken.START_ARRAY))
                throw new IllegalArgumentException("Invalid json. must have a start tag");*/
            if(jsonToken == null) break;

            switch (jsonToken) {
                case START_OBJECT:
                    Json prev = cur;
                    cur = parse(jp, new Json());
                    if(fieldName != null && prev != null) prev.put(fieldName, cur);
                    cur = prev != null? prev: cur;
                    break;
                case END_OBJECT: return cur;
                case START_ARRAY:
                    //TODO: rethink if we should use Arraylist here or a plain object array - perf?
                    Json lst = parseArray(jp, new Json(true));
                    if(fieldName != null) cur.put(fieldName, lst);
                    else if(cur == null) cur=new Json(lst);
                    break;
                case END_ARRAY: break;
                case FIELD_NAME: fieldName = jp.getCurrentName(); break;
                case VALUE_STRING:
                    Vld.checkNull(fieldName, "Invalid JSON, name");
                    cur.put(fieldName, jp.getValueAsString());break;
                case VALUE_NUMBER_INT:
                case VALUE_NUMBER_FLOAT:
                    Vld.checkNull(fieldName, "Invalid JSON, name");
                    cur.put(fieldName, jp.getNumberValue());break;
                case VALUE_TRUE:
                case VALUE_FALSE:
                    Vld.checkNull(fieldName, "Invalid JSON, name");
                    cur.put(fieldName, jp.getBooleanValue());break;
                case VALUE_NULL:
                    Vld.checkNull(fieldName, "Invalid JSON, name");
                    cur.put(fieldName, null);break;
                default:
                    throw new IOException("unexpected json token: "+jsonToken);
            }
        }
        if(cur == null) throw new IllegalArgumentException("Invalid json");
        return cur;
    }

    static Json parseArray(JsonParser jp, Json lst) throws IOException {
        while (!jp.isClosed()){
            JsonToken jsonToken = jp.nextToken();
            switch (jsonToken) {
                case START_OBJECT:
                    lst.add(parse(jp, new Json()));
                    break;
                case END_OBJECT: break;
                case START_ARRAY: lst.add(parseArray(jp, new Json(true))); break;
                case END_ARRAY: return lst;
                case VALUE_STRING: lst.add(jp.getValueAsString());break;
                case VALUE_NUMBER_INT:
                case VALUE_NUMBER_FLOAT: lst.add(jp.getNumberValue());break;
                case VALUE_TRUE:
                case VALUE_FALSE: lst.add(jp.getBooleanValue());break;
                case VALUE_NULL: lst.add(null);break;
            }
        }
        return lst;
    }

    /*
        find keys from top to bottom, listed in an array.
        e.g.
            1. a,b,c from {"a":{"b":{"c":"", ...}, ...}, ...}
            2. a,b[0],c from {"a":{"b":[{"c":"", ...}, ...], ...}
            2. a[0],b[0],c from {"a":[{"b":[{"c":"", ...}, ...], ...},...],...}
            3. a.b[].c from {"a":{"b":[{"c":"", ...}, ...], ...}
            4. a[].b[].c from {"a":[{"b":[{"c":"", ...}, ...], ...},...],...}
            5. a[0],b[] from {"a":[{"b":[{"c":"", ...}, ...], ...},...],...}. Allows [] as terminal key, so array can be accessed
    */
    public Object find(String ... keys){
        return findOrSet(keys, null);
    }

    public Object find(String keyHierarchy){
        Vld.checkEmpty(keyHierarchy, "keys");
        return  find(keyHierarchy.split("\\."));
    }

    public Json findJson(String ... keys){
        Object ret = find(keys);
        return ret instanceof Json?(Json) ret:null;
    }

    public Object set(String keyHierarchy, Object repl){
        Vld.checkEmpty(keyHierarchy, "keyHierarchy");
        return findOrSet(keyHierarchy.split("\\."), repl);
    }
    public Object findOrSet(String [] keys, Object repl){
        if(keys == null || keys.length == 0) throw new IllegalArgumentException("keys must be provided");
        if(isEmpty()) return null;

        Json parent = null;
        String pk = null;
        int pidx = -1;
        Json json=this;
        for (int i=0, len=keys.length;i<len;i++){
            String k=keys[i];
            int idx=-1;
            boolean isarr = k.charAt(k.length() - 1) == ']';
            if(isarr){
                int li = k.lastIndexOf('[');
                if(li < 0) return null;
                String v = k.substring(li+1, k.length() - 1);
                try {
                    if(!v.isEmpty()) idx = Integer.valueOf(v);
                    k = k.substring(0,li);
                } catch (Exception e) {
                    isarr = false; //we do not have an array reference
                }
            }
            if(isarr && idx < 0 && i + 1 != len)
                return null; //old style a[].b is not supported
            if(json.isArray()){
                if (!isarr || json.size() == 0) return null;
                if(idx >= 0) {
                    if (idx > json.size()) return null;
                    Object o = json.get(idx);
                    if (o instanceof Json) { // style: [{}, {}, ...], or [[], [], ...]
                        //now json can be an object so pattern a[x].b, a[x].b[y] are allowed or can be an array so pattern a[x].[y] is allowed
                        parent = json;
                        pidx = idx;
                        json = (Json) o;
                    }else{ // style: [xyz, abc, ...] or there can be an  object e.g. List, Map,  access is not supported
                        if(i + 1 == len){
                            if(repl != null) json.set(idx, repl);
                            return o;
                        } else return null;
                    }
                }else {// allows [] as terminal key, so array can be fully accessed
                    if(i + 1 == len) {
                        if(repl != null && parent!=null) parent.set(idx, repl);
                        return json;
                    } else return null;
                }
            }else {
                Object o = json.get(k);
                if (o == null) return null;
                if (o instanceof Json ) {
                    pk = k;
                    parent = json;
                    json = (Json) o;
                    if(json.isArray()){
                        if (!isarr) return null;
                        if(idx >= 0) {
                            if (idx >= json.size()) return null;
                            o = json.get(idx);
                            if (o == null) return null;
                            if (o instanceof Json) {
                                pidx = idx; pk = null;
                                parent = json;
                                json = (Json) o;
                            }else if (o instanceof List){
                                pidx = idx; pk = null;
                                parent = json;
                                json = new Json((List) o);
                            }else if(i + 1 == len){
                                if(repl!=null) json.set(idx, repl);
                                return o;
                            }else return null;
                        }
                        //json = new Json((List) o);
                    }
                /*} else if (o instanceof List) { //List, Map, access is not supported
                    if (!isarr) return null;
                    if(idx >= 0) {
                        if (idx >= ((List) o).size()) return null;
                        o = ((List) o).get(idx);
                        if (o == null) return null;
                        if (o instanceof Json) json = (Json) o;
                        else if (o instanceof List) json = new Json((List) o);
                        else if(i + 1 == len) return o;
                        else return null;
                        continue;
                    }
                    json = new Json((List) o);*/
                }else{
                    if(i + 1 == len) {
                        if(repl!=null) json.put(k, repl);
                        return o;
                    } else return null;
                }
            }
        }
        if(repl!=null && parent!=null){
            if(pk!=null) parent.put(pk, repl);
            else parent.set(pidx, repl);
        }
        return json;
    }

    @Override
    public String toString() {
        try {
            return toJson();
        } catch (IOException e) {
            logger.info("JSON Serialization", e);
            throw new IllegalStateException(e.getMessage());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Json json = (Json) o;
        return Objects.equals(array, json.array) && Objects.equals(msg, json.msg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), array, msg);
    }

    @Override
    public boolean isEmpty() {
        return isArray()? array.isEmpty(): super.isEmpty();
    }


    public boolean contains(Object o) {
        checkArray();
        return array.contains(o);
    }

    @Override
    public int size() {
        return isArray()? array.size():super.size();
    }

    @Override
    public Object clone() {
        return fromJson(toString());
        /*if(isArray()) {
            Json clone = new Json(true);
            clone.array = new ArrayList<>(array);
            return clone;
        }
        return super.clone();*/
    }

    //use DT class instead

   /* public static String toDateTime(Calendar datetime){
        return DatatypeConverter.printDateTime(datetime);
    }

    public static String toISO8601DateTime(Calendar datetime){
        return DatatypeConverter.printDateTime(datetime);
    }

    public static Calendar fromISO8601DateTime(String datetime){
        return DatatypeConverter.parseDateTime(datetime);
    }

    static final SimpleDateFormat formater=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    static final SimpleDateFormat formaterWithoutZ=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");

    public static String toDateTime(Date datetime){
        return formater.format(datetime);
    }

    public static String toISO8601DateTime(Date datetime){
        return formater.format(datetime);
    }

    public static Date fromDateTime(String datetime) throws Exception {
        return formater.parse(datetime);
    }

    public static String toDateTimeWithZ(Date datetime){
        return formaterWithoutZ.format(datetime)+"Z";
    }*/





    /*--- generator ---*/

    public static class Encoder{

        final byte obj=1;
        final byte arr=2;
        final byte fld=3;
        /**
         * @author: dhonek
         * @date : 3/5/14
         */
        StringWriter sw;
        JsonGenerator builder;
        boolean startedArray = false;
        Object ref = null;
        byte [] lwo=new byte[100];
        int idx=0;

        void setPretty(){
            builder.setPrettyPrinter(new DefaultPrettyPrinter());
        }

        void push(byte itm){
            if(idx != 0 && lwo[idx-1] == fld) return;
            lwo[idx++]=itm;
        }

        void pop(){
            if(idx != 0) lwo[--idx]=0;
        }

        void popitm(int itm){
            if(idx != 0 && lwo[idx - 1]==itm) lwo[--idx]=0;
        }

        boolean ispeek(int itm){
            return idx != 0 && lwo[idx - 1] == itm;
        }

        boolean writeStartIfReq() throws IOException {
            if(!ispeek(obj)) {
                builder.writeStartObject();
                push(obj);
                return true;
            }
            return false;
        }

        void writeEndIfReq(boolean wrote) throws IOException {
            if(wrote) {
                builder.writeEndObject();
                popitm(fld);
                popitm(obj);
            }
        }

        boolean writeStartArrayIfReq() throws IOException {
            if(!ispeek(obj)) {
                builder.writeStartArray();
                push(arr);
                return true;
            }
            return false;
        }

        void writeEndArrayIfReq(boolean wrote) throws IOException {
            if(wrote) {
                builder.writeEndArray();
                popitm(arr);
            }
        }

        public Encoder startEncode() {
            return startEncode(false, true);
        }

        public Encoder startEncode(boolean array, boolean start){
            sw= new StringWriter();
            try {
                builder = factory.createGenerator(sw);
                if(start) {
                    startedArray = array;
                    if (!startedArray) {
                        builder.writeStartObject();
                        push(obj);
                    }else {
                        builder.writeStartArray();
                        push(arr);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public String endEncode() throws IOException{
            return endEncode(true);
        }

        public String endEncode(boolean end) throws IOException{

            if(end) {
                if (!startedArray)
                    builder.writeEndObject();
                else builder.writeEndArray();
                pop();
            }

            builder.close();
            sw.flush();
            return sw.toString();
        }

        public Encoder toJson(String name, String value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeStringField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, int value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Integer value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, long value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Long value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, BigDecimal value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, float value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Float value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, double value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Double value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeNumberField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, boolean value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            builder.writeBooleanField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Boolean value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeBooleanField(name, value);
            push(fld);
            return this;
        }

        public Encoder toJson(String name, Calendar value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            //this could loose some date related data,  like time zone & locale.
            builder.writeObjectField(name, DT.to_iso(value));
            push(fld);
            return this;
        }

        //FIXME: timezone!
        public Encoder toJson(String name, Date value) throws IOException {
            Calendar cal = Calendar.getInstance();
            cal.setTime(value);
            return toJson(name, cal);
        }

        public Encoder toJson(String name, java.sql.Date value) throws IOException {
            Calendar cal = Calendar.getInstance();
            cal.setTime(value);
            return toJson(name, cal);
        }

        public Encoder toJson(String name, java.sql.Time value) throws IOException {
            Calendar cal = Calendar.getInstance();
            cal.setTime(value);
            return toJson(name, cal);
        }

        public Encoder toJson(String name, Timestamp value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            //this could loose some date related data,  like time zone & locale.
            try {
                builder.writeObjectField(name, DT.utcWMZ(value.getTime()));//Type.parse(String.class, value)
            }catch (Exception e){throw new IOException(e.getMessage());}
            push(fld);
            return this;
        }

        public Encoder toJson(String name, org.apache.commons.lang.enums.Enum value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeObjectField(name, value.getName());
            push(fld);
            return this;
        }

        public Encoder toJson(Json value) throws IOException {
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }

            if(value.isArray()) {
                toJson(value.array);
            }else {
                boolean wrote = writeStartIfReq();
                for(Map.Entry<String, Object> e: value.entrySet()){
                    toJson(e.getKey(), e.getValue());
                }
                writeEndIfReq(wrote);
            }

            return this;
        }

        //Exceptional behavior, not writing start/end object tags
        public <T> Encoder toJson(Map<String, T> value) throws IOException {
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }

            boolean wrote = writeStartIfReq();
            for(Map.Entry<String, T> e: value.entrySet()){
                toJson(e.getKey(), (T)e.getValue());
            }
            writeEndIfReq(wrote);
            return this;
        }

        public <T> Encoder toJson(List<T> lst) throws IOException {
            if(lst == null) return this;
            boolean wrote = writeStartArrayIfReq();
            for(T v:lst){
                toJson(v);
            }
            writeEndArrayIfReq(wrote);
            return this;
        }

        public <T> Encoder toJson(Collection<T> lst) throws IOException {
            if(lst == null) return this;
            boolean wrote = writeStartArrayIfReq();
            for(T v:lst){
                toJson(v);
            }
            writeEndArrayIfReq(wrote);
            return this;
        }

        /*public Encoder toJson(String name, Map<String, Entity> value) throws IOException {
                    if(value == null) {
                        //builder.writeNullField(name);
                        return this;
                    }
                    builder.writeFieldName(name);
                    boolean wrote = writeStartArrayIfReq();
                    for(Map.Entry<String, Entity> e:value.entrySet()){
                        boolean wrote2 = writeStartIfReq();
                        e.getValue().toJson(this);
                        writeEndIfReq(wrote2);
                    }
                    writeEndArrayIfReq(wrote);
                    return this;
                }
            */
        public Encoder toJson(String name, Json value) throws IOException {
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeFieldName(name);
            push(fld);
            //        boolean wrote = writeStartIfReq();
            toJson(value);
            //        writeEndIfReq(wrote);

            return this;
        }

        public Encoder toJson(String name, Collection value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeFieldName(name);
            push(fld);
            boolean wrote = writeStartArrayIfReq();
            for(Object lid:value){
                toJson(lid);
            }
            writeEndArrayIfReq(wrote);

            return this;
        }

        public <T> Encoder toJsonArray(String name, List<T> lst) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(lst == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeFieldName(name);
            push(fld);
            return toJson(lst);
        }

        public Encoder toJsonArray(String name, Map<String, String> value) throws IOException {
            if(name == null || name.isEmpty()) throw new IllegalArgumentException();
            if(value == null) {
                //builder.writeNullField(name);
                return this;
            }
            builder.writeFieldName(name);
            push(fld);
            boolean wrote = writeStartArrayIfReq();
            for(Map.Entry<String, String> e:value.entrySet()){
                builder.writeStringField(e.getKey(), e.getValue());
            }
            writeEndArrayIfReq(wrote);
            return this;
        }

        public Encoder toJson(String name, Object value) throws IOException {
            //TODO: is it ok to allow empty string as name? but it's a valid json
            if(name == null) throw new IllegalArgumentException("name cannot be null for value "+value);
            if(value == null) {
                //            builder.writeNullField(name);
                return this;
            }

            if(value instanceof org.apache.commons.lang.enums.Enum) return toJson(name, (org.apache.commons.lang.enums.Enum) value);
                //        else if(value instanceof List<?>) toJson(name, (List<?>) value);
            else if(value instanceof Collection<?>) toJson(name, (Collection<?>)value);
            else if(value instanceof Timestamp) toJson(name, (Timestamp)value);
            else if(value instanceof Json) {
                //avoid repeated self reference which cause stack overflow
                //FIXME:can cause unexpected.
                if(ref == null) ref = value;
                else if(ref == value) return this;
                toJson(name, (Json) value);
                ref = null;
            }else {
                try {
                    Method m = value.getClass().getMethod("toJson", Encoder.class);
                    if (m != null) {
                        builder.writeFieldName(name);
                        push(fld);
                        boolean wrote = writeStartIfReq();
                        m.invoke(value, this);
                        writeEndIfReq(wrote);
                    } else {
                        builder.writeObjectField(name, value);
                        push(fld);
                    }
                } catch (NoSuchMethodException e) {
                    builder.writeObjectField(name, value);
                    push(fld);
                } catch (InvocationTargetException | IllegalAccessException e) {
                    throw new IOException(e);
                }
            }
            return this;
        }

        public Encoder toJson(Object value) throws IOException {
            if(value == null) {
                //builder.writeNull();
                return this;
            }

            if(value instanceof org.apache.commons.lang.enums.Enum)
                builder.writeObject(((org.apache.commons.lang.enums.Enum) value).getName());
                //        else if(value instanceof List) toJson((List<?>) value);
            else if(value instanceof Collection<?>) toJson((Collection<?>)value);
            else if(value instanceof Json) toJson((Json)value);
            else {
                //TODO: assume this is not a collection/array
                try {
                    Method m = value.getClass().getMethod("toJson", Encoder.class);
                    if (m != null) {
                        boolean wrote = writeStartIfReq();
                        m.invoke(value, this);
                        writeEndIfReq(wrote);
                    } else builder.writeObject(value);
                } catch (NoSuchMethodException e) {
                    builder.writeObject(value);
                } catch (InvocationTargetException | IllegalAccessException e) {
                    throw new IOException(e);
                }

            }
            return this;
        }
    }


    /*---- parser ---*/

    public static class Decoder {

        JsonParser parser;
        Object rawjson;
        JsonToken currToken;

        public Decoder parse(String json) throws IOException {
            parser = factory.createParser(json);
            rawjson = json;
            return this;
        }

        public Decoder parse(InputStream json) throws IOException {
            parser = factory.createParser(json);
            rawjson = json;
            return this;
        }

        public void close() throws IOException {
            parser.close();
        }

        public Decoder next() throws IOException {
            currToken = parser.nextToken();
            return this;
        }

        public boolean isClosed() {
            return parser.isClosed();
        }

        public boolean isEOS() {
            return currToken == null;
        }

        public boolean isNextStartObject() throws IOException {
            return parser.nextToken() == JsonToken.START_OBJECT;
        }

        public boolean isNextEndObject() throws IOException {
            return parser.nextToken() == JsonToken.END_OBJECT;
        }

        public boolean isNextStartArray() throws IOException {
            return parser.nextToken() == JsonToken.START_ARRAY;
        }

        public boolean isNextEndArray() throws IOException {
            return parser.nextToken() == JsonToken.END_ARRAY;
        }

        public boolean isStartObject() throws IOException {
            return parser.currentToken() == JsonToken.START_OBJECT;
        }

        public boolean isEndObject() throws IOException {
            return parser.currentToken() == JsonToken.END_OBJECT;
        }

        public boolean isStartArray() throws IOException {
            return parser.currentToken() == JsonToken.START_ARRAY;
        }

        public boolean isEndArray() throws IOException {
            return parser.currentToken() == JsonToken.END_ARRAY;
        }

        public boolean isField() throws IOException {
            return parser.currentToken() == JsonToken.END_ARRAY;
        }

        public String fieldName() throws IOException {
            return parser.getCurrentName();
        }

        public String fieldValueAsString() throws IOException {
            return parser.getValueAsString();
        }

        public Object fieldValue() throws IOException {
            JsonToken jsonToken = parser.getCurrentToken();
            switch (jsonToken) {
                case VALUE_STRING:
                    return parser.getValueAsString();
                case VALUE_NUMBER_INT:
                case VALUE_NUMBER_FLOAT:
                    return parser.getNumberValue();
                case VALUE_TRUE:
                case VALUE_FALSE:
                    return parser.getValueAsBoolean();
                case VALUE_NULL:
                    return null;
            }
            return null;
        }

        public long getCharLocation() {
            JsonLocation loc = parser.getCurrentLocation();
            return loc.getCharOffset();
        }

        public Pair<Integer, Integer> getLocation() {
            JsonLocation loc = parser.getCurrentLocation();
            return new Pair<>(loc.getLineNr(), loc.getColumnNr());
        }
    }
}

//            switch (ch){
//                case '\n': ch = 'n'; break;
//                case '\r': ch = 'r'; break;
//                case '\f': ch = 'f'; break;
//                case '\t': ch = 't'; break;
//                case '\b': ch = 'b'; break;
//                case '\"':
//                case '\\':  break;
//                    /* this is to avoid multiple time escape
//                    if(len == 1
//                        || i+1<len && !isescape(str.charAt(i+1)) && i > 0 && str.charAt(i-1) != '\\'
//                        || i==0 && i+1<len && !isescape(str.charAt(i+1))
//                        || i+1>=len && i > 0 && str.charAt(i-1) != '\\')
//                        ret[j++] = '\\';
//                    ret[j++] = ch;
//                    continue;*/
//                default:
//                    //https://developer.fastly.com/reference/vcl/functions/strings/json-escape/
//                    if( (byte)ch <= 0x1F || (byte)ch == 0x7F || (int)ch == 0x2028 || (int)ch == 0x2029){
//                        String s = String.format("\\u%04X",(int)ch);
//                        int rlen = s.length() + j;
//                        if(rlen > ret.length){
//                            char[] copy = new char[rlen + (ret.length - i)*2];
//                            System.arraycopy(ret, 0, copy, 0, ret.length);
//                            ret = copy;
//                        };
//                        for(int c=0;c<s.length(); c++)
//                            ret[j++] = s.charAt(c);
//                    }else
//                        ret[j++] = ch;
//                    continue;
//            }
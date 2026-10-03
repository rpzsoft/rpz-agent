

package com.rpzsoft.agent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URLClassLoader;
import java.util.*;

public final class Platform {
    private static final Logger logger = LoggerFactory.getLogger(Platform.class);

    static final String WINDOWS = "Windows";

    static TimeZone timeZone = null;
    static Locale locale = null;

    //consider static initialization
    public static String getPID(){
        try {
            return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        }catch(Exception e){
            e.printStackTrace();
        }
        return ManagementFactory.getRuntimeMXBean().getName();
    }

    public static boolean win(){
        return System.getProperty("os.name").startsWith(WINDOWS);
    }

    /** allow spawning service as a separate process */
    /** main process/thread may call runner.waitFor() to wait till the subprocess ends. */
    public static Process spawn(Class clz, boolean waitFor) {
        Process runner = null;
        try {

            //this thread will be local to the parent & will listen on child Services.
            ProcessBuilder pb = new ProcessBuilder(new StringBuilder().append((Platform.win())?"\"":"").append(System.getProperty("java.home")).append(File.separator).append("bin")
                    .append(File.separator).append("java").append((Platform.win())?".exe\"":"").toString(), "-classpath", System.getProperty("java.class.path"),
                    clz.getName());
            pb.inheritIO();
            runner = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook(runner)));

            logger.info(clz.getName() + " is started as a separate instance ...");

            InputStreamReader isr = new InputStreamReader(runner.getErrorStream());
            BufferedReader br = new BufferedReader(isr);
            String line = null;
            while ((line = br.readLine()) != null)
                logger.error(line);
            if(waitFor)
                runner.waitFor();
        } catch (Exception e) {
            logger.error(" spawning failed for process: "+clz.getName(), e);
        }
        return runner;
    }

    /**
     * Gets the load average in the system run queue assigned
     * to the available processors averaged over various periods of time.
     */
    public static int loadAVG(double[] loadavg, int nelems){
        return unsafe.getLoadAverage(loadavg, nelems);
    }

    public static long objectFieldOffset(Field f){
        return unsafe.objectFieldOffset(f);
    }

    public static long staticFieldOffset(Field f){
        return unsafe.staticFieldOffset(f);
    }

    public static void park(boolean isAbsolute, long time){
        unsafe.park(isAbsolute, time);
    }

    public static void unpark(Object thread){
        unsafe.unpark(thread);
    }

    public static long getAndAddLong(Object o, long offset, long val) {
        return unsafe.getAndAddLong(o, offset, val);
    }

    static class ShutdownHook implements Runnable{
        Process runner = null;

        public ShutdownHook(Process runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            runner.destroyForcibly();
        }
    }

    static Unsafe unsafe = null;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to obtain intrinsic for jvm - [System PANIC]!", e);
        }
    }

    public static Class<?> loadClass(String clz) throws ClassNotFoundException {
        return loadClass(clz, Thread.currentThread().getContextClassLoader());
    }

    public static Class<?> loadClass(String clz, ClassLoader clld) throws ClassNotFoundException {
        return Class.forName(clz,true, clld);
    }

    public static int arrayBaseOffset(Class<?> cl){
        return unsafe.arrayBaseOffset(cl);
    }

    public static int arrayIndexScale(Class<?> cl){
        return unsafe.arrayIndexScale(cl);
    }

    /** returns the memory ptr - not the native pointer */
    public static long malloc(long size){
        return unsafe.allocateMemory(size);
    }

    public static void free(long address){
        unsafe.freeMemory(address);
    }

    /**
     * Reallocate memory at 'address' by expanding to given size and existing memory will be relocated at new address
     * returned.
     */
    public static long realloc(long address, long size){
        return unsafe.reallocateMemory(address, size);
    }

    /** Fetches a native pointer from a given memory ptr. */
    public static long get_ptr(long address){return unsafe.getAddress(address);}

    /** Stores a native pointer into a given memory address */
    public static void set_ptr(long address, long ptr){unsafe.putAddress(address, ptr);}

    public static void memset(long address, long size, byte value){
        unsafe.setMemory(address, size, value);
    }

    public static void bzero(long address, long size){
        memset(address, size, (byte)0);
    }

    public static void memcpy(long dest, long src, long size){
        unsafe.copyMemory(src, dest, size);
    }

    //if use memory address the base must be null & offset musdt be the abs memory address.
    public static void memcpy(Object srcBase, long srcOff, Object destBase, long destOff, long bytes){
        /*
            Sets all bytes in a given block of memory to a copy of another
            block.

            This method determines each block's base address by means of two parameters,
            and so it provides (in effect) a double-register addressing mode,
            as discussed in #getInt(Object,long) .  When the object reference is null,
            the offset supplies an absolute base address.


            The transfers are in coherent (atomic) units of a size determined
            by the address and length parameters.  If the effective addresses and
            length are all even modulo 8, the transfer takes place in 'long' units.
            If the effective addresses and length are (resp.) even modulo 4 or 2,
            the transfer takes place in units of 'int' or 'short'.

            public  void copyMemory(long srcAddress, long destAddress, long bytes)
            {
                    copyMemory(null, srcAddress, null, destAddress, bytes);
            }
            Sets all bytes in a given block of memory to a copy of another block. This provides a single-register
            addressing mode, as discussed in #getInt(Object,long) .
            Equivalent to copyMemory(null, srcAddress, null, destAddress, bytes).
         */
        unsafe.copyMemory(srcBase, srcOff, destBase, destOff, bytes);
    }

    public static byte memread(long address){return unsafe.getByte(address);}

    public static int  memread(byte []buf, int nbyte, long address){
        long offset = Platform.arrayBaseOffset(byte[].class);
        memcpy(null, address, buf, offset, nbyte);
        return nbyte;
    }

    public static void memwrite(long address, byte v){unsafe.putByte(address, v);}

    public static int  memwrite(byte []buf, int nbyte, long address){
        long offset = Platform.arrayBaseOffset(byte[].class);
        memcpy(buf, offset, null, address, nbyte);
        return nbyte;
    }

    public static int page_size(){return unsafe.pageSize();}
    public static int addr_size(){return unsafe.addressSize();}

    public static TimeZone getTZ(String datetime){
        if(datetime==null || datetime.isEmpty()){
            throw new IllegalArgumentException("No time zone");
        }
        String tz = null;
        for (int i = datetime.length() - 1; i > 0 ; i--) {
            if (datetime.charAt(i) == '+' || datetime.charAt(i) == '-') {
                String offset = datetime.substring(i);
                if(offset.length() > 6) throw new IndexOutOfBoundsException("missing time zone");
                tz = "GMT"+offset;
                break;
            }else if(datetime.charAt(i) == 'Z'){
                tz="GMT";
                break;
            }
        }
        if(tz == null) throw new IndexOutOfBoundsException("missing time zone");
        return TimeZone.getTimeZone(tz);
    }

    // FIXME:[Warn] when running on multi-region/geography (e.g. for GR/DR availability zones) timezone could be
    // FIXME: different so this could generate different values, so setTimeZone() could be used to set the whole system
    // FIXME: to a single timezone.
    // TODO: add functionality for timezone sync across all instances.
    public static TimeZone getTimeZone(){
        if(timeZone == null) {
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            timeZone = now.getTimeZone();
        }
        return timeZone;
    }

    public static void setTimeZone(TimeZone tz) {
        timeZone = tz;
    }

    public static void setLocale(Locale local) {
        locale = local;
    }

    public static Locale getLocale() {
        if(locale == null){
            locale = Locale.getDefault();
        }
        return locale;
    }

    //usage: dumpClasspath(Foo.class.getClassLoader());
    public static void dumpClasspath(ClassLoader loader){
//            System.err.println("java.class.path: " +System.getProperty("java.class.path"));
        System.err.println("Classloader " + loader + ":");

        if (loader instanceof URLClassLoader)
        {
            URLClassLoader ucl = (URLClassLoader)loader;
            System.err.println("\t" + Arrays.toString(ucl.getURLs()));
        }
        else
            System.err.println("\t(cannot display components as not a URLClassLoader)");

        if (loader.getParent() != null)
            dumpClasspath(loader.getParent());
    }

    /*static {
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                System.loadLibrary("ez-platform");
                return null;
            }});
        init();
    }*/

    //TODO: get statistics of the running service
    public RuntimeMXBean getRuntimeMXBean(){
        return ManagementFactory.getRuntimeMXBean();
    }

    //e.g. way to check if the calling class is secure
    /*public boolean isSafeCaller(){
        Class var0 = Reflection.getCallerClass();
        if(!VM.isSystemDomainLoader(var0.getClassLoader())) {
            throw new SecurityException("Unsafe");
        } else {
            return true;
        }
    }*/

    public static void waitAndListen(final Object obj){
        synchronized (obj) {
            while (true) {
                try {
                    obj.wait();
                } catch (InterruptedException e) {
                    //
                }
            }
        }
    }

    public static int cpus(){
        return Runtime.getRuntime().availableProcessors();
    }

    /*
        with docker we get Container ID is hostname.
        With kubernetes we get Pod's metadata.name as hostname (as observed from within the Pod).
        Pod spec has an optional hostname field, which can be used to specify a different hostname
     */
    public static String getHostname() {
        String hn = "";
        try {
            InetAddress lh = InetAddress.getLocalHost();
            hn = lh.getHostName();
        }catch (Exception e) {
            try {
                try (Scanner s = new Scanner(Runtime.getRuntime().exec("hostname").getInputStream())) {
                    hn = s.hasNext() ? s.next() : "";
                }
            } catch (IOException e1) {
                //
            }
        }
        return hn;
    }

    /*public static void getInterfaces() throws SocketException {
        Enumeration<NetworkInterface> eni = NetworkInterface.getNetworkInterfaces();
        while (eni.hasMoreElements()){
            NetworkInterface netint = eni.nextElement();

            System.out.printf("Display name: %s\n", netint.getDisplayName());
            System.out.printf("Name: %s\n", netint.getName());
            Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
            for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                System.out.printf("InetAddress: %s\n", inetAddress);
                System.out.printf("Hostname: %s\n", inetAddress.getHostName());
            }
            System.out.printf("\n");
        }
    }*/
}

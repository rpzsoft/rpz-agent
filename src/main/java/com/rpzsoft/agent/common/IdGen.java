

package com.rpzsoft.agent.common;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedGenerator;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * generate a UUID and <hash> code that allow assigning the instance to a particular virtual server vnode
 *
 * https://tools.ietf.org/html/rfc4122
 *
 */
public class IdGen {
    static String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static int alphaLen = alpha.length();
    static String prefix;
    public static int NUBER_SERVERS=2;
    static final AtomicLong dd = new AtomicLong(1);
    private volatile long atom = 1L; //N.B. this is in use
    static String ATOM = "atom";
    private static final long atomOffet;

    static Random rnd = new Random();

    static{
        // running on containers & k8s may not give us reliable single MAC address so use mechanism as described in
        // https://tools.ietf.org/html/rfc4122#section-4.5
        //TODO: validate this.
        SecureRandom rnd = new SecureRandom();
        byte [] randomBytes = new byte[6];
        long maca=0;
        rnd.nextBytes(randomBytes);
        maca = randomBytes[0] & 0xFF;
        for (int i = 1; i < 6; ++i) {
            maca = (maca << 8) | (randomBytes[i] & 0xFF);
        }
        /*
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isLoopback()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length == 6) {
                        maca = mac[0] & 0xFF;
                        for (int i = 1; i < 6; ++i) {
                            maca = (maca << 8) | (mac[i] & 0xFF);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        */

        randomBytes = new byte[4];
        rnd.nextBytes(randomBytes);
        long msb = 0;
        long lsb = 0;
        assert randomBytes.length == 4 : "data must be 16 bytes in length";
        for (int i=0; i<2; i++)
            msb = (msb << 2) | (randomBytes[i] & 0xff);
        for (int i=2; i<4; i++)
            lsb = (lsb << 2) | (randomBytes[i] & 0xff);
        prefix = maca + Long.toHexString(msb)+Long.toHexString(lsb)+"-"+ Platform.getPID()+"-";

        try {
            atomOffet = Platform.objectFieldOffset(IdGen.class.getDeclaredField(ATOM));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static final IdGen idg = new IdGen();

    private IdGen() {
    }

    /** length = 42 bytes when Long.MAX_VALUE for atom suffix */
    static StringBuilder uuidsb() {
        return new StringBuilder().append(prefix).append(Long.toHexString(System.nanoTime()
                + Platform.getAndAddLong(idg, atomOffet, 1L)));
    }

    /** length = 42 bytes when Long.MAX_VALUE for atom suffix */
    public static String uuid() {
        return uuidsb().toString();
    }

    //thread-safe
    static final TimeBasedGenerator tmGen = Generators.timeBasedGenerator(EthernetAddress.fromInterface());
    public static String uuid(int type) {
        switch (type){
            case 0: return tmGen.generate().toString();
            default:
                return UUID.randomUUID().toString();
        }
    }

    public static String uuid(String suffix) {
        return uuidsb().append(fnv1a(suffix.getBytes())).toString();
    }

    public static String uuid(byte [] suffix) {
        return uuidsb().append(fnv1a(suffix)).toString();
    }

    //none secure random number
    public static String randomId(){
        return Double.toHexString(1+Math.random()*4294967295L);
    }

    public static String rndStr(int size){
        String ret="";
        for ( int i = 0; i < size; i++ ) {
            ret += alpha.charAt(rnd.nextInt(alphaLen));
        }
        return ret;
    }

    //hash fnv1
    public static long fnv1a(byte [] data){
       long h = 2166136261L;
       for (byte b : data) h = (h ^ b) * 16777619;
       return Math.abs(h);
    }


    public static long vnode(String id){
        long hash = fnv1a(id.getBytes());
        return hash % NUBER_SERVERS;
    }
}

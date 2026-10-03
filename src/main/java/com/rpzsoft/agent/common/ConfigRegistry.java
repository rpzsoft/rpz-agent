

package com.rpzsoft.agent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 
 */
public class ConfigRegistry extends Properties{
    private static Logger logger = LoggerFactory.getLogger(ConfigRegistry.class);

    private static boolean built=false;
    public static final String NOOFTHREADS="threadCount";

    static final ConfigRegistry cr = new ConfigRegistry();

    static final List<String> confs = new ArrayList<>();

    //make this private to avoid constructing it without config
    private ConfigRegistry() {
    }

    private void build(){
		build("app.properties");

        /*final String apphome = System.getProperty("app.home");
        final String confpath = apphome!= null?(apphome.endsWith(File.separator)?apphome:apphome+File.separator):"";
        build(confpath+"app.properties");*/

        String cnf = getProperty("app.conf");
        if(cnf!= null && !cnf.isEmpty()){
            build(cnf);
        }
        built = true;
    }

    public synchronized ConfigRegistry build(String ... conf) {
        if(conf.length == 0) throw new IllegalArgumentException("Config file cannot be null");

        InputStream in = null;
        try{
            for(String cn:conf) {
                if(cn.isEmpty()) continue;
                String [] clst=cn.split(",");
                for(String c:clst) {
                    if(c.isEmpty() || confs.contains(c)) continue;
                    confs.add(c);
                    try {
                        logger.debug("conf: {}", c);
                        in = ClassLoader.getSystemResourceAsStream(c);
                        if (in == null) {
                            in = getClass().getClassLoader().getResourceAsStream(c);
                            if (in == null) throw new IllegalArgumentException("Config file [" + c + "] not found");
                        }
                        load(in);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

        }finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
            }
        }
        return this;
    }

    public int getIntProperty(String prop) {
        return Integer.parseInt(getProperty(prop));
    }
    public int getIntProperty(String prop, String defaultValue) {
        return Integer.parseInt(getProperty(prop, defaultValue));
    }

    public long getLongProperty(String prop) {
        return Long.parseLong(getProperty(prop));
    }

    public long getLongProperty(String prop, String defaultValue) {
        return Long.parseLong(getProperty(prop, defaultValue));
    }

    public boolean getBooleanProperty(String prop) {
        return Boolean.parseBoolean(getProperty(prop));
    }

    /**
     * get default or an extended config instance for the app. e.g. allow extending for a given app/test
     * @return
     */
    public static ConfigRegistry getInstance(String ... conf){
        if(!built) cr.build();
        return conf.length == 0?cr:cr.build(conf);
    }

    public static List<String> parse(String confs, String delimiter) throws IOException {
        InputStream in = null;
        List<String> lines = new ArrayList<>();

        try {
            if(confs.isEmpty()) return new ArrayList<>();
            String [] clst=confs.split(",");
            for(String conf:clst) {
                logger.debug("conf: {}", conf);
                in = ClassLoader.getSystemResourceAsStream(conf);
                if (in == null) {
                    in = ConfigRegistry.class.getClassLoader().getResourceAsStream(conf);
                    if (in == null) throw new IllegalArgumentException("File [" + conf + "] not found!");
                }

                boolean del = delimiter.trim().isEmpty();

                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String li = br.readLine();
                while (li != null) {
                    li = li.trim();
                    if(!li.isEmpty()) {
                        if (del) lines.addAll(Arrays.asList(li.split(delimiter)));
                        else lines.add(li);
                    }
                    li = br.readLine();
                }

                if (lines.size() == 0) {
                    throw new IllegalStateException("Error: in file [" + conf + "] is empty!");
                }
            }

        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                ;
            }

        }
        return lines;
    }
}

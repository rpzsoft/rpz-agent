

package com.rpzsoft.agent.common;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;

import static java.time.format.DateTimeFormatter.*;

/**
 * 
 */
public class DT {

    /*  Upper & lower case 'X', 'XX', 'XXX' will output hour & min like '+01' (unless the minute is non-zero in which
        case the minute is also output, such as '+0130'), '+0130' and '+01:30' (with colon) respectively
        When the offset is zero 'X' (upper case) will output 'Z'
     */
    static final DateTimeFormatter yyyy_MM_dd = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter HH_mm_ss = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final DateTimeFormatter hh_mm_ss_a = DateTimeFormatter.ofPattern("hh:mm:ss a");
    static final DateTimeFormatter ISO8601_SSS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    static final DateTimeFormatter ISO8601_SSSX = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");
    static final DateTimeFormatter ISO8601_SSSxxx = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    static final DateTimeFormatter ISO8601_SSSXXX = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    static final DateTimeFormatter ISO8601_xxx = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    static final DateTimeFormatter ISO8601_XXX = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    static final DateTimeFormatter ISO8601_X = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX");
    static final DateTimeFormatter ISO8601 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    static final DateTimeFormatter ISO8601_SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter ISO8601X_SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX");
    public static final DateTimeFormatter ISO8601_SSSX_SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSX");
    static final DateTimeFormatter ISO8601_nss_SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final DateTimeFormatter ISO8601_OPT = DateTimeFormatter.ofPattern("[yyyy-MM-dd]['T'][ ][HH:mm[:ss]][.SSS][XXX]");

    //timestamp for current time at UTC in millisecond
    public static long utc(){return utcS() * 1000L;}
    public static long utcS(){return OffsetDateTime.now( ZoneOffset.UTC ).toEpochSecond();}

    //timestamp for a given datetime with same at UTC
    public static long to_utc(String datetime) throws Exception {return to_utcS(datetime) * 1000L;}
    public static long to_utcdt(String date, String fmt) throws Exception {return odt(date, fmt).toEpochSecond() * 1000L;}
    public static long to_utcS(String datetime) throws Exception {return odtUTC(datetime).toEpochSecond();}

    //OffsetDateTime instance for a given date-time string i.e. 2007-12-03T10:15:30+01:00
    public static OffsetDateTime odt(){return OffsetDateTime.now( ZoneOffset.UTC );}
    public static OffsetDateTime odt(String datetime){return OffsetDateTime.parse(datetime);}
    public static OffsetDateTime odtUTC(String datetime){return odt(datetime).withOffsetSameInstant(ZoneOffset.UTC);}
    public static OffsetDateTime odt(String datetime, String fmt){return OffsetDateTime.parse(datetime, DateTimeFormatter.ofPattern(fmt));}
    public static OffsetDateTime odt(Instant datetime){return datetime.atOffset(ZoneOffset.UTC);}

    //ISO8601
    //with millisecond part - 2019-12-21T18:26:04.923Z
    public static String utcWM(){
        return ISO8601_SSSxxx.format(OffsetDateTime.now( ZoneOffset.UTC ));
    }
    public static String utcWMZ(){return ISO8601_SSSXXX.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    //milliseconds
    public static String utcWM(long utcMillies){return Instant.ofEpochMilli(utcMillies).atZone(ZoneOffset.UTC).format(ISO8601_SSSxxx);}
    public static String utcWMZ(long utcMillies){return Instant.ofEpochMilli(utcMillies).atZone(ZoneOffset.UTC).format(ISO8601_SSSXXX);}
    public static String utcWOM(long utcMillies){return Instant.ofEpochMilli(utcMillies).atZone(ZoneOffset.UTC).format(ISO8601_xxx);}
    public static String utcWOMZ(long utcMillies){return Instant.ofEpochMilli(utcMillies).atZone(ZoneOffset.UTC).format(ISO8601_XXX);}
    //without millisecond part - 2019-12-21T18:26:04Z
    public static String utcWOM(){
        return ISO8601_xxx.format(OffsetDateTime.now( ZoneOffset.UTC ));
    }
    public static String utcWOMZ(){return ISO8601_XXX.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    public static String utcWOMZ(OffsetDateTime dt){return ISO8601_XXX.format(dt);}
    //without millisecond part & Zone - 2019-12-21T18:26:04
    public static String utcWOMAZ(){return ISO8601.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    public static String utcWOMAZT(){return ISO8601_SQL.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    public static String utcWOMSAZT(){return ISO8601_nss_SQL.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    public static String utc(String fmt){return DateTimeFormatter.ofPattern(fmt).format(OffsetDateTime.now( ZoneOffset.UTC ));}
    public static String utc(long utcMillies, String fmt){return DateTimeFormatter.ofPattern(fmt).format(Instant.ofEpochMilli(utcMillies).atZone(ZoneOffset.UTC));}

    //public static String ldt(String fmt){return DateTimeFormatter.ofPattern(fmt).format(LocalDateTime.now());}


    //local
    /*public static TemporalAccessor odt(String datetime){
        return ISO8601_OPT.parseBest(datetime, LocalDateTime::from, LocalDate::from, LocalTime::from);
    }*/

    //local
    public static TemporalAccessor odt_opt(String datetime){
        return ISO8601_OPT.parseBest(datetime, OffsetDateTime::from, OffsetTime::from);
    }

    public static TemporalAccessor dt_opt(String datetime, String fmt){
        return DateTimeFormatter.ofPattern(fmt).parseBest(datetime, LocalDateTime::from, OffsetDateTime::from);
    }
    public static TemporalAccessor ldt_opt(String datetime){
        return ISO8601_OPT.parseBest(datetime, LocalDateTime::from, LocalDate::from, LocalTime::from);
    }

    public static LocalDateTime ldt(String datetime, String ...fmt){
        DateTimeFormatter dtfm = ISO8601_SSS;
        if(fmt.length > 0) dtfm = DateTimeFormatter.ofPattern(fmt[0]);
        else{
            if(datetime.endsWith("Z")){ //2021-06-19T18:30:00.000Z
                if( datetime.charAt(datetime.length() - 5) == '.')
                    dtfm = ISO8601_SSSX;
                else //'2011-12-03T10:15:30Z'
                    dtfm = ISO8601_X;
            }else{
                int loc = datetime.indexOf('T');
                if(loc > 0) {
                    String suffix = datetime.substring(loc + 1);
                    int milloc = suffix.indexOf('.', loc);
                    if (suffix.matches("[+-]")) {
                        if (milloc > 0) {
                            dtfm = ISO8601_SSSXXX;
                        } else dtfm = ISO8601_XXX;
                    } else if (milloc > 0) {
                        dtfm = ISO8601_SSS;
                    } else dtfm = ISO8601; /*{
                        if (suffix.lastIndexOf(':') == 4)
                            dtfm = ISO8601;
                    }*/
                }

            } //2011-12-03T10:15:30+01:00'
        }
        return LocalDateTime.parse(datetime, dtfm);
    }
    public static LocalDateTime ldtWOMAZT(String datetime){return LocalDateTime.parse(datetime, ISO8601_SQL);}
    public static LocalDate ld(String date, String fmt){return LocalDate.parse(date, DateTimeFormatter.ofPattern(fmt));}
    public static LocalDate ld(String date){return LocalDate.from(ISO_DATE.parse(date));}
    public static LocalTime lt(String time){return LocalTime.from(ISO_TIME.parse(time));}
    //    public static LocalDate ld(String date){return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);}
    //with millisecond part - 2019-12-21T18:26:04.923Z
    public static LocalDateTime ldtWMZ(String datetime){return ISO8601_SSSXXX.parse(datetime, LocalDateTime::from);}
    //public static LocalDateTime ldtWM(String datetime){return ISO8601_SSSxxx.parse(datetime, LocalDateTime::from);}
    //without millisecond part & Zone - 2019-12-21T18:26:04
    public static LocalDateTime ldtWOMZ(String datetime){return ISO8601.parse(datetime, LocalDateTime::from);}
    //public static LocalDateTime ldtWOM(String datetime){return ISO8601_xxx.parse(datetime, LocalDateTime::from);}




    public static String dtWMZ(Instant datetime){return ISO8601_SSSXXX.format(odt(datetime));}

    public static String to_iso(Calendar calendar){
        ZonedDateTime zdt = calendar.toInstant()
                .atZone(calendar.getTimeZone().toZoneId());
        return ISO8601_XXX.format(zdt);
    }
    public static String to_iso(ZonedDateTime zdt){
        return ISO8601_SSSXXX.format(zdt);
    }

    //extract date or time from ISO format e.g. 2021-06-19T18:30:00.000Z
    public static String ex_date(String datetime){return ex_datetime(datetime, true);}
    public static String ex_time(String datetime){return ex_datetime(datetime, false);}
    private static String ex_datetime(String datetimez, boolean date){
        DateTimeFormatter retf = date? yyyy_MM_dd : HH_mm_ss;
        try {
            if(datetimez.endsWith("Z")){ //2021-06-19T18:30:00.000Z
                if( datetimez.charAt(datetimez.length() - 5) == '.')
                    return retf.format(ISO8601_SSSX.parse(datetimez));
                else //'2011-12-03T10:15:30Z'
                    return retf.format(ISO8601_X.parse(datetimez));
            }else //2011-12-03T10:15:30+01:00'
                return retf.format(ISO8601_XXX.parse(datetimez));
        } catch (Exception e) {
            return retf.format(ISO8601.parse(datetimez));
        }
    }

    //sql helper

    public static OffsetDateTime sql_odt(String datetimez){
        return OffsetDateTime.parse(datetimez, ISO8601_SQL);
    }

    //current datetime string - 2021-06-19T18:30:00
    public static String datetime(){ return utcWOMZ();}
    //current date string - 2021-06-19
    public static String date(){ return yyyy_MM_dd.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    //current time string - 18:30:00
    public static String time24h(){ return HH_mm_ss.format(OffsetDateTime.now( ZoneOffset.UTC ));}
    //current time 12h as AM/PM string - 18:30 PM
    public static String time(){ return hh_mm_ss_a.format(OffsetDateTime.now( ZoneOffset.UTC ));}

    //at zone
    public static String datetime_at(String zone){ return ISO8601_XXX.format(OffsetDateTime.now( ZoneId.of(zone)));}
    public static String datetime_at(String zone, String dtformat){ return DateTimeFormatter.ofPattern(dtformat).format(OffsetDateTime.now( ZoneId.of(zone)));}
    public static String date_at(String zone){ return yyyy_MM_dd.format(OffsetDateTime.now(ZoneId.of(zone)));}
    public static String time24h_at(String zone){ return HH_mm_ss.format(OffsetDateTime.now(ZoneId.of(zone)));}
    public static String time_at(String zone){ return hh_mm_ss_a.format(OffsetDateTime.now(ZoneId.of(zone)));}
    //ZonedDateTime.now(ZoneId.of(zone))

}

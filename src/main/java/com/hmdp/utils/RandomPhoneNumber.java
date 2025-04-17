package com.hmdp.utils;

import java.util.*;

public class RandomPhoneNumber {

    //中国移动
    public static final String[] CHINA_MOBILE = {

            "134", "135", "136", "137", "138", "139", "150", "151", "152", "157", "158", "159",
            "182", "183", "184", "187", "188", "178", "147", "172", "198"
    };
    //中国联通
    public static final String[] CHINA_UNICOM = {

            "130", "131", "132", "145", "155", "156", "166", "171", "175", "176", "185", "186", "166"
    };
    //中国电信
    public static final String[] CHINA_TELECOME = {

            "133", "149", "153", "173", "177", "180", "181", "189", "199"
    };

    /**
     * 生成手机号 * @param op 0 移动 1 联通 2 电信
     */
    public static String createMobile(int op) {

        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        String mobile01;//手机号前三位
        int temp;
        switch (op) {
            case 0:
                mobile01 = CHINA_MOBILE[random.nextInt(CHINA_MOBILE.length)];
                break;
            case 1:
                mobile01 = CHINA_UNICOM[random.nextInt(CHINA_UNICOM.length)];
                break;
            case 2:
                mobile01 = CHINA_TELECOME[random.nextInt(CHINA_TELECOME.length)];
                break;
            default:
                mobile01 = "op标志位有误！";
                break;
        }
        if (mobile01.length() > 3) {

            return mobile01;
        }
        sb.append(mobile01);
        //生成手机号后8位
        for (int i = 0; i < 8; i++) {

            temp = random.nextInt(10);
            sb.append(temp);
        }
        return sb.toString();
    }

    /**
     * 随机生成指定的手机号 * @param num 生成个数 *
     * @return
     */
    public static List<String> randomCreatePhone(int num) {

        Set<String> phoneSet = new HashSet<>();
        Random random = new Random();
        while (phoneSet.size() < num) {
            int op = random.nextInt(3);//随机运营商标志位
            phoneSet.add(createMobile(op));
        }
        return new ArrayList<>(phoneSet);
    }
}
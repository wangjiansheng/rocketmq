package org.apache.rocketmq.broker;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * 消息id解析
 * Created by wangjs on 2019/5/13.
 */
public class RocketMsgIdRevers {
    public static void main(String[] args) {

       // byte[] dest = UtilAll.string2bytes("AC101E9200002A9F000000000121C63A");
        byte[] dest =string2bytes("AC101E9200002A9F0000000017F68262");
        //ip的long值
        Long lvalue = 0l;
        lvalue += ((long) (dest[0] & 0xFF) << 24);
        lvalue += ((long) (dest[1] & 0xFF) << 16);
        lvalue += ((long) (dest[2] & 0xFF) << 8);
        lvalue += ((long) (dest[3] & 0xFF) << 0);


        //端口号
        ByteBuffer portBuffer = ByteBuffer.allocate(4);
        portBuffer.put(dest, 4, 4);
        portBuffer.flip();
        //ip+ port
        System.out.println(longToIp(lvalue)+":"+portBuffer.getInt());

        ByteBuffer offsetBuffer = ByteBuffer.allocate(8);
        offsetBuffer.put(dest, 8, 8);
        offsetBuffer.flip();
        System.out.println(" 消息对应物理分区-wroteOffset:"+ offsetBuffer.getLong());

    }

    public static String longToIp(long ip) {
        StringBuilder sb = new StringBuilder();
        //直接右移24位
        sb.append(ip >> 24);
        sb.append(".");
        //将高8位置0，然后右移16
        sb.append((ip & 0x00FFFFFF) >> 16);
        sb.append(".");
        //将高16位置0，然后右移8位
        sb.append((ip & 0x0000FFFF) >> 8);
        sb.append(".");
        //将高24位置0
        sb.append((ip & 0x000000FF));
        return sb.toString();
    }

    public static String getString(ByteBuffer buffer) {
        Charset charset = null;
        CharsetDecoder decoder = null;
        CharBuffer charBuffer = null;
        try {
            charset = Charset.forName("UTF-8");
            decoder = charset.newDecoder();
            //charBuffer = decoder.decode(buffer);//用这个的话，只能输出来一次结果，第二次显示为空
            charBuffer = decoder.decode(buffer.asReadOnlyBuffer());
            return charBuffer.toString();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "";
        }
    }

    public static byte[] string2bytes(String hexString) {
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        hexString = hexString.toUpperCase();
        int length = hexString.length() / 2;
        char[] hexChars = hexString.toCharArray();
        byte[] d = new byte[length];
        for (int i = 0; i < length; i++) {
            int pos = i * 2;
            d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
        }
        return d;
    }

    private static byte charToByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }
}

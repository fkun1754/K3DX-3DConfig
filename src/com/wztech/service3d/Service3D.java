package com.wztech.service3d;

/**
 * 零编译 JNI 桥：直接加载系统 libnative_wz2sf.so 调用 send2sf
 * （厂商导出了 Java_com_wztech_service3d_Service3D_send2sf，类名匹配即可绑定）
 * 接口协议（send2sf(8001, (key<<16)|value)）:
 *   key 100 = 3D开关 (1=开 0=关)
 *   key 101 = 左右交换/视点 (VP01=1, VP02=0)
 *   key 103 = 显示模式 (0=HSBS半幅 1=FSBS全幅 2=2D转3D)
 *   key 105 = 检查, 109 = 检查返回
 */
public class Service3D {

    private static boolean loaded = false;

    /** 加载系统库（64位优先，失败试32位） */
    public static synchronized boolean load() {
        if (loaded) return true;
        try {
            System.load("/system/lib64/libnative_wz2sf.so");
            loaded = true;
        } catch (Throwable t1) {
            try {
                System.load("/system/lib/libnative_wz2sf.so");
                loaded = true;
            } catch (Throwable t2) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** 对应厂商导出的 native 符号 Java_com_wztech_service3d_Service3D_send2sf */
    public static native int send2sf(int a, int b, boolean c);

    /** 3D 总开关 */
    public static void set3DEnabled(boolean on) {
        if (load()) send2sf(8001, (100 << 16) | (on ? 1 : 0), false);
    }

    /** 显示模式: 0=HSBS半幅 1=FSBS全幅 2=2D转3D */
    public static void setDisplayMode(int mode) {
        if (load()) send2sf(8001, (103 << 16) | (mode & 0xFF), false);
    }

    /** 视点/左右: 1=VP01(右左) 0=VP02(左右) */
    public static void setViewPoint(int vp) {
        if (load()) send2sf(8001, (101 << 16) | (vp & 0xFF), false);
    }
}

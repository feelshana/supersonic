package com.tencent.supersonic.common.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ListMergeTool {

    public static void main(String[] args) {
        // 初始集合
        List<String> list = new ArrayList<>(Arrays.asList("zouyizhan", "putao", "liuyuxuan",
                "liaokun_pt", "wangchao_yy1", "xileiyx", "zhuyunchun", "shengyuwei", "kongtianshu",
                "lixia", "dongxiaojian", "wuxingrong", "wencong", "zhaoqin", "jiangjiqipt",
                "RedSeaTest01", "lvhept", "gaomeng", "liyi", "liujuan_wh", "lili_wh", "sunyanqing",
                "jubin", "wangconggang", "chenxuexs", "zhoujianhong", "wangliang3", "taojiawei",
                "libinpt", "zhujiawei", "zhangxuewei", "suyi_hy", "sunlei", "shanlei",
                "miaoxiangbin", "liwenzhou", "lijun", "wangle", "zhuhong", "zhuangwenyun",
                "zhaoqian", "chendifei", "liuming", "xuyue", "zhangyanpeng", "maliyong",
                "liuguoxin", "yuhang", "yuanmin", "zhaoying", "liaozhiyong_ciyuan", "liuyuxing",
                "zhongyong", "guming", "leijie", "jialei", "xujie", "lifang", "fangzhongjun",
                "zhoubing", "liushiming", "fenglin", "wangling", "shenwenhai", "zhangxinpengpt",
                "liqianqian", "dengtingting", "tangshaojie", "wangqiqi_yx", "lilin_sx",
                "dongfangning", "lizhe_sx", "huyi", "xiliang", "lilin", "xiangyang", "yanzhongwei",
                "kuangtiemei", "yangcang", "wanggang", "beiyue", "hesong", "husu", "sunhai",
                "zhanghongying", "xufei", "xiezhongtao"));

        // 待比对的字符串
        String str =
                "zechenchen_sx,chenhao_sx,yangle_xs,guoyi,caojinwei,zhangjian,aiyang,lvfang,zhoulin,zhoulinyx,liubeiyu,haozhaoxs,mashuai,xilei,xileiyx,chenwangdu,zhangqingqing_jl,hexiaorui,duxiaofu,xiezhenxin,tianshu_ll,niulei,chenyunyi,xiaosa,xumeng,tangkunpeng,chenhuiyun,heweilin,yaoxueling,chenling_sx2,lijinzhi,renhuaqiang,guoxiao,likang,zhangwenting,shangyuxiang,nieguoliang,sunjiayue,luxia,lilisx,zongguiqinjc,dengwenbojs,hujin,wanghaoran_yy,huangqiongfeng,tangle_yy,fuqiang";

        // 用 LinkedHashSet 保持插入顺序且自动去重
        Set<String> set = new LinkedHashSet<>(list);

        String[] items = str.split(",");
        int addedCount = 0;
        int skippedCount = 0;

        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (set.contains(trimmed)) {
                System.out.println("[跳过] 已存在: " + trimmed);
                skippedCount++;
            } else {
                set.add(trimmed);
                System.out.println("[新增] 已添加: " + trimmed);
                addedCount++;
            }
        }

        System.out.println("\n===== 统计 =====");
        System.out.println("原始集合大小: " + list.size());
        System.out.println("字符串元素数: " + items.length);
        System.out.println("跳过(已存在): " + skippedCount);
        System.out.println("新增: " + addedCount);
        System.out.println("最终集合大小: " + set.size());
        System.out.println("\n===== 最终集合 =====");
        String result = set.stream().map(s -> "\"" + s + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        System.out.println(result);
    }
}

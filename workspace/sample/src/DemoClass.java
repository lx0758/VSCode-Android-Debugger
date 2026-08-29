package com.example.demo;

/**
 * 调试目标示例代码 (sampleWorkspace/).
 * 你可以替换为真实的 AOSP framework 类来测源解析与断点.
 */
public class DemoClass {
    private String name;
    private int count;

    public DemoClass(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public void increment() {
        count = count + 1;
    }

    public String describe() {
        return name + ":" + count;
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
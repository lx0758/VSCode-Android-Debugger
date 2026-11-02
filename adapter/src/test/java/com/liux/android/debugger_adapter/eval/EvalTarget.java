package com.liux.android.debugger_adapter.eval;

/**
 * The program that the end-to-end evaluation test attaches to.
 *
 * It is compiled with debug information so that the breakpoint in
 * {@link #target} exposes the parameters as local variables.
 *
 * {@link #READY} is a gate: {@code main} waits for the debugger to set
 * it, so the debugger can install its breakpoint before {@code target}
 * is ever entered — no race between "class loaded" and "breakpoint set".
 */
public class EvalTarget {

    /** Set to true by the debugger once its breakpoint is in place. */
    public static volatile boolean READY = false;

    /** The breakpoint lands on the first line of this method. */
    public static void target(Object obj, String s, Integer boxed, int prim) {
        System.out.println("target reached");
    }

    public static void main(String[] args) throws Exception {
        while (!READY) {
            Thread.sleep(20);
        }
        target(new java.util.ArrayList<String>(), "hello", 42, 7);
        // Stay alive so the debugger has something to inspect.
        Thread.sleep(120000);
    }
}

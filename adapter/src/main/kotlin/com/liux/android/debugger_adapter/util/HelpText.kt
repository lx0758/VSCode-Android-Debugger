package com.liux.android.debugger_adapter.util

/**
 * Reference text for the evaluate-expression language. Returned as-is by the
 * `evaluate` handler when the user types `help` / `?` / `?help` in the
 * Debug Console, so they don't have to dig through docs to remember which
 * operators / constructs the parser supports.
 */
object HelpText {

    val EVALUATE_LANGUAGE_REFERENCE: String = """
=== Evaluate 表达式语法参考 ===

【字面量】
  42            int (整数默认推断, 超出范围自动转 long)
  3.14          double
  1.5f          float
  100L          long
  "hello"       字符串 (支持 \n \t \" \\ \')
  'a'           char
  true / false  boolean
  null          空引用

【变量 / 字段】
  url                       当前栈帧局部变量 / 参数
  this.url                  当前实例字段 (含父类)
  req.header.name           嵌套字段链
  Math.PI                   静态字段 (大写开头 → 类名查找)

【方法调用】
  foo.bar()                 无参实例方法
  foo.bar(42, "x")          带参实例方法 (参数类型自动匹配 + 加宽)
  Math.abs(x)               静态方法
  Integer.parseInt(s)       JDK 静态方法
  this.toString()           this 上调方法

【运算符 (按优先级从低到高)】
  =                         赋值 (右结合)
  ?:                        三元
  ||                        逻辑或 (短路)
  &&                        逻辑与 (短路)
  ==  !=                    相等 (基本类型按值, 引用按 ==)
  <  >  <=  >=              关系 (基本类型 + String)
  +  -                      加减 / 字符串拼接
  *  /  %                   乘除模
  !  -  +  ~                一元 (非 / 负 / 正 / 位反)

【构造】
  new ArrayList()                    无参构造
  new HashMap(16)                    带参构造
  new java.util.HashMap<String, Integer>()  FQN

【数组】
  arr[0]                             读
  arr[i + 1]                         表达式下标
  arr[0] = 99                        写
  matrix[i][j]                       多维 (JDI 不支持, 只能嵌套 getValue)

【类型检查】
  obj instanceof String
  obj instanceof java.util.List

【赋值】
  url = "new value"
  this.count = 10
  arr[0] = 5
  url = url + "?"

【示例】
  count > 0 && count < 100                  区间判断
  count > 10 ? "big" : "small"              三元
  url + "?" + count                         字符串拼接
  Math.abs(delta * 2)                       静态方法 + 算术嵌套
  items.size() > 0 ? items.get(0) : null   链式调用
  url == null || url.isEmpty()              短路 OR
  Integer.parseInt(input.trim())            静态转换

【日志点插值 (BP logMessage)】
  "{user} 请求 {count} 次, 耗时 {elapsed}ms"
  单条消息里多个 {expr} 占位都会求值

【暂不支持】(会报错)
  ✗ Lambda / Stream / 控制流 (for / if / while)
  ✗ try / catch / 异常
  ✗ 泛型方法类型推断 (List.<String>foo())
  ✗ 数组字面量 (new int[]{1,2,3})
  ✗ 字符串 switch / 类型模式 (instanceof X y)
  ✗ final 字段 / final 局部变量 (JVM 字节码强制)
  ✗ static final 常量 (编译器内联, 改了也不生效)

【调试小技巧】
  - 没结果? 先 print 局部变量名看是不是命名差异
  - 方法不存在? 用 type 名 + .method() 试, JDI 区分大小写
  - 类型不匹配? 显式强转: ((Number)x).intValue()
  - 想看完整对象: obj.toString() 或展开 Variables 面板
""".trimIndent()
}

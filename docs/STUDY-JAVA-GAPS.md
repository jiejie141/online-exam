# Java 八股补漏（现有文档没覆盖的高频考点）

> 配套 `STUDY-JAVA-BACKEND.md` 使用。那份讲的是**你项目里的技术栈**（Spring Boot / MySQL / Redis / MQ / 并发 / JVM 基础）；
> 这份补的是**面试官一定会问、但你项目里不一定用得上**的通用八股。
>
> 格式统一：**是什么 → 为什么重要 → 面试怎么答（可直接背）**。
> 优先级：★★★★★ 必背 / ★★★★ 高频 / ★★★ 会问

---

## 目录

1. 集合框架（★★★★★）
2. String 与字符串（★★★★）
3. 泛型（★★★）
4. 反射与动态代理（★★★★）
5. Lambda / Stream（★★★）
6. 异常体系（★★★★）
7. 并发补漏：AQS / ThreadLocal / 线程状态（★★★★★）
8. Java 17 新特性 + 虚拟线程（★★★★★）
9. 速答卡：20 题一句话版

---

# 第 1 章 集合框架 ★★★★★

## 1.1 HashMap 底层结构（必背）

**是什么**
- JDK 8：**数组 + 链表 + 红黑树**
- JDK 7 及以前：数组 + 链表（头插法，扩容时可能成环）

**关键参数**

| 参数 | 默认值 | 说明 |
|---|---|---|
| 初始容量 | 16 | 必须是 2 的幂 |
| 负载因子 | 0.75 | 空间与冲突的折中 |
| 树化阈值 | 链表长度 ≥ 8 **且** 数组长度 ≥ 64 | 两个条件都满足才树化 |
| 退化阈值 | 红黑树节点 ≤ 6 | 退化为链表 |
| 扩容 | 容量 × 2 | 元素数 > 容量 × 0.75 |

**为什么容量必须是 2 的幂**
> 索引计算是 `(n - 1) & hash`，只有 n 是 2 的幂时 `n-1` 才是全 1 的掩码，等价于取模但快得多。
> 扩容时元素要么留在原位、要么移动 `oldCap` 距离 —— 也是靠这个性质，不用重新算 hash。

**为什么树化阈值是 8**
> 源码注释里写了：链表长度符合泊松分布，长度到 8 的概率约 0.00000006，正常情况下几乎不会树化。
> 设成 8 是「极端情况下兜底」，不是常态。

**为什么还要判断数组长度 ≥ 64**
> 数组太小时，冲突的根因是「桶太少」，扩容比树化更有效。所以先扩容，实在不行再树化。

**面试怎么答**
> 「JDK 8 的 HashMap 是数组加链表加红黑树。默认容量 16、负载因子 0.75，元素超过容量乘 0.75 就扩容成两倍。
> 容量必须是 2 的幂，因为索引是 (n-1) & hash，这样等价于取模但更快，扩容时元素要么原位要么移动 oldCap。
> 链表长度到 8 且数组长度到 64 才树化 —— 8 是泊松分布算出来的极小概率，64 是因为数组太小时扩容比树化更管用。」

## 1.2 HashMap 线程不安全在哪

**三个具体表现**
1. **JDK 7**：并发扩容会形成**环形链表**，get 时死循环（CPU 100%）
2. **JDK 8**：改成了尾插 + 高低位拆分，不再成环，但**仍会丢数据**（两个线程同时 put 到同一个空桶，后写的覆盖先写的）
3. **size 不准确**、并发修改时可能抛 `ConcurrentModificationException`

**面试怎么答**
> 「JDK 7 并发扩容会成环导致死循环，JDK 8 改尾插之后不会成环了，但依然会丢数据 —— 两个线程同时往同一个空桶 put，后写的覆盖先写的。
> 所以并发场景要用 ConcurrentHashMap，或者用 Collections.synchronizedMap（但那是全表锁）。」

## 1.3 ConcurrentHashMap（JDK 8）★★★★★

**和 JDK 7 的区别（高频对比题）**

| | JDK 7 | JDK 8 |
|---|---|---|
| 锁粒度 | **Segment 分段锁**（默认 16 段） | **CAS + synchronized 锁单个桶头节点** |
| 数据结构 | Segment 数组 + HashEntry 数组 | Node 数组 + 链表 + 红黑树 |
| 并发度 | 固定 16 | 等于桶的数量（更高） |
| 空值 | 不允许 | 不允许 |

**为什么不允许 null 键值**
> 因为并发场景下 `get` 返回 null 无法区分「不存在」和「值就是 null」，
> 而这个区分在单线程 HashMap 里可以靠 `containsKey` 补救，在并发下补救不可靠（中间可能被改）。

**size() 怎么统计**
> 用 `baseCount` + `CounterCell[]` 分散计数（类似 LongAdder），避免所有线程争同一个计数器。

**面试怎么答**
> 「JDK 8 的 ConcurrentHashMap 放弃了分段锁，改成 CAS 加 synchronized 只锁桶的头节点，锁粒度细到单个桶，并发度等于桶数量。
> 计数用的是 baseCount 加 CounterCell 数组的分散累加，避免热点。
> 它不允许 null 键值 —— 因为并发下 get 返回 null 没法区分『不存在』和『值是 null』。」

## 1.4 ArrayList vs LinkedList

| | ArrayList | LinkedList |
|---|---|---|
| 底层 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 头部插入 | O(n)（要移动） | O(1) |
| 内存 | 连续、紧凑 | 每个节点多两个指针，缓存不友好 |
| 实际选择 | **绝大多数场景** | 几乎不用（队列用 ArrayDeque） |

**面试怎么答**
> 「ArrayList 是数组，随机访问 O(1)，中间插入要移动元素；LinkedList 是双向链表，插入 O(1) 但要先遍历找到位置，而且每个节点多两个指针、内存不连续、CPU 缓存不友好。
> 实际上 LinkedList 几乎不用 —— 需要队列用 ArrayDeque，需要列表用 ArrayList。」

## 1.5 fail-fast 与 fail-safe

**是什么**
- **fail-fast**：遍历时结构被修改 → 抛 `ConcurrentModificationException`（ArrayList / HashMap）
- **fail-safe**：遍历的是快照，不抛异常但看不到最新修改（CopyOnWriteArrayList / ConcurrentHashMap 的迭代器）

**modCount 机制**
> 集合内部维护 `modCount`，迭代器创建时记住它；每次 `next()` 检查是否变化，变了就抛异常。

**面试怎么答**
> 「fail-fast 是靠 modCount 实现的：迭代器记住创建时的 modCount，每次 next 检查有没有变，变了就抛 ConcurrentModificationException。
> 所以遍历时删除元素要用迭代器的 remove，或者用 removeIf，不能直接调集合的 remove。
> CopyOnWriteArrayList 是 fail-safe，遍历的是快照，适合读多写少。」

---

# 第 2 章 String 与字符串 ★★★★

## 2.1 String 为什么不可变

**三个原因**
1. `value[]` 是 `final` 的（JDK 9 之后是 `byte[]` + coder）
2. 类本身是 `final`，不能被继承重写
3. 没有暴露修改内部数组的方法

**不可变带来什么**
- **线程安全**（天然）
- **可以缓存在字符串常量池**，复用
- **hashCode 可以缓存**（第一次算完存起来），适合做 HashMap 的 key

**面试怎么答**
> 「String 内部是 final 的字节数组，类也是 final，没有任何方法能改它。
> 不可变带来三个好处：天然线程安全、能放进常量池复用、hashCode 能缓存下来，所以特别适合当 HashMap 的 key。」

## 2.2 字符串常量池与 intern

```java
String a = "abc";              // 常量池
String b = new String("abc");  // 堆里新对象
String c = b.intern();         // 返回常量池里的引用

a == b      // false
a == c      // true
a == b.intern()  // true
```

**JDK 7 之后常量池从方法区移到了堆**
> 因为方法区（永久代）大小固定，容易 OOM；移到堆里可以跟着堆一起扩容和回收。

## 2.3 String / StringBuilder / StringBuffer

| | 可变性 | 线程安全 | 场景 |
|---|---|---|---|
| String | 不可变 | 安全 | 常量、key、少量拼接 |
| StringBuilder | 可变 | **不安全** | 单线程拼接（首选） |
| StringBuffer | 可变 | 安全（synchronized） | 多线程拼接（少见） |

**`+` 拼接的编译优化**
> 循环外的 `a + b + c` 会被编译成 `StringBuilder.append`，性能没问题。
> **但循环内的 `+` 每次都会 new 一个 StringBuilder**，所以要手动提到循环外。

**面试怎么答**
> 「循环外直接用加号没问题，编译器会优化成 StringBuilder；但循环里必须手动提到外面，否则每轮都新建一个 StringBuilder。」

---

# 第 3 章 泛型 ★★★

## 3.1 类型擦除

**是什么**：泛型只存在于编译期，编译后 `List<String>` 和 `List<Integer>` 都是 `List`，类型参数被擦除成它的上界（默认 `Object`）。

**带来的限制**
- 不能 `new T()`、不能 `new T[]`
- 不能对泛型做 `instanceof`
- 静态成员不能用类的类型参数
- 泛型方法重载时擦除后签名可能冲突

**为什么这么设计**
> 为了**向后兼容** —— 泛型是 Java 5 引入的，擦除让老版本编译的字节码和类库还能继续用。

## 3.2 PECS 原则

> **Producer Extends, Consumer Super**
> - 只读取（生产者）用 `? extends T`
> - 只写入（消费者）用 `? super T`

```java
void copy(List<? extends Number> src, List<? super Number> dst) {
    for (Number n : src) dst.add(n);
}
```

**面试怎么答**
> 「泛型是编译期特性，运行时会擦除成上界，所以不能 new T、不能 instanceof。
> 通配符记 PECS：只读用 extends，只写用 super —— 因为 extends 的列表你没法 add（编译器不知道具体是哪个子类），super 的列表你读出来只能是 Object。」

---

# 第 4 章 反射与动态代理 ★★★★

## 4.1 反射

**是什么**：运行时获取类的信息（字段、方法、构造器）并操作它。

**代价**
- 比直接调用慢（有方法查找、参数装箱、访问检查）
- 破坏封装
- 编译期无法检查，容易出错

**为什么框架离不开它**
> Spring 要靠它扫描注解、实例化 Bean、注入依赖；MyBatis 要靠它把 ResultSet 映射成对象。

## 4.2 JDK 动态代理 vs CGLIB ★★★★★

| | JDK 动态代理 | CGLIB |
|---|---|---|
| 前提 | **目标类必须实现接口** | 不需要接口 |
| 原理 | 生成实现同一接口的代理类，`InvocationHandler` 拦截 | 生成**子类**字节码，方法重写拦截 |
| 性能 | 创建快、调用略慢（反射） | 创建慢（生成字节码）、调用快 |
| 限制 | 只能代理接口方法 | **不能代理 final 类 / final 方法** |

**Spring AOP 怎么选**
> 默认：有接口用 JDK 代理，没接口用 CGLIB。
> Spring Boot 2.x 之后**默认全部用 CGLIB**（`proxyTargetClass=true`），避免「注入了接口但想用实现类方法」的坑。

**面试怎么答**
> 「JDK 动态代理要求目标类实现接口，是生成一个实现同接口的类，通过 InvocationHandler 拦截；
> CGLIB 是生成子类重写方法，不需要接口，但不能代理 final 类和 final 方法。
> Spring 默认有接口用 JDK、没接口用 CGLIB，Spring Boot 2 之后统一默认 CGLIB。」

---

# 第 5 章 Lambda / Stream ★★★

## 5.1 函数式接口

> 只有一个抽象方法的接口，可以用 lambda 实现。`@FunctionalInterface` 只是编译期检查。
> 四大内置：`Function<T,R>`、`Consumer<T>`、`Supplier<T>`、`Predicate<T>`。

## 5.2 Stream 的惰性求值

**关键**：中间操作（map / filter / sorted）**不执行**，只有遇到终止操作（collect / forEach / count）才一次性走完。

**好处**：可以短路（`findFirst`、`anyMatch` 找到就停）、可以合并遍历（一次循环完成多个操作）。

**parallelStream 的坑（面试常问）**
> 用的是公共的 `ForkJoinPool.commonPool`，默认线程数是 CPU 核数 - 1。
> ① 里面做 IO 会拖垮整个 commonPool（影响其他并行流）
> ② 有共享可变状态时结果不确定
> ③ 数据量小时并行反而更慢（拆分和合并的开销）
> **结论：默认用串行，确有 CPU 密集且数据量大再考虑并行，而且要自己指定线程池。**

---

# 第 6 章 异常体系 ★★★★

## 6.1 体系结构

```
Throwable
├── Error（不该捕获：OOM、StackOverflow）
└── Exception
    ├── RuntimeException（非受检：NPE、IndexOutOfBounds、IllegalArgument）
    └── 其他（受检：IOException、SQLException）
```

## 6.2 受检 vs 非受检

| | 受检 | 非受检 |
|---|---|---|
| 编译期检查 | 必须 try 或 throws | 不检查 |
| 代表 | IOException | NullPointerException |
| 设计意图 | **调用方能处理的、可恢复的** | **编程错误** |

**面试怎么答**
> 「受检异常是编译器强制处理的，代表调用方有能力恢复的情况，比如 IO；非受检是 RuntimeException，代表编程错误，比如空指针。
> 实际项目里我倾向于把业务异常定义成非受检，避免异常层层 throws 污染签名。」

## 6.3 三个高频细节

**① finally 会覆盖 return**
```java
int f() {
    try { return 1; }
    finally { return 2; }   // 返回 2，而且会吞掉异常
}
```
> 所以 finally 里不要写 return。

**② try-with-resources**
> 自动关闭实现了 `AutoCloseable` 的资源，比 finally 里手写 close 更安全（能正确处理 close 本身抛异常的情况，异常会被 addSuppressed）。

**③ 异常链**
> `throw new ServiceException("下单失败", e)` —— 保留原始堆栈，排查时能一路看到根因。
> **不要 `throw new RuntimeException(e.getMessage())`**，那样会丢掉堆栈。

---

# 第 7 章 并发补漏 ★★★★★

## 7.1 AQS（AbstractQueuedSynchronizer）

**是什么**：JDK 并发包的基石，`ReentrantLock`、`CountDownLatch`、`Semaphore`、`ReadWriteLock` 都基于它。

**核心三件套**
1. **state**（volatile int）：同步状态。ReentrantLock 里表示重入次数，Semaphore 里表示剩余许可
2. **CLH 变体队列**：抢不到锁的线程包装成 Node 入队等待
3. **CAS**：修改 state 和入队都靠 CAS

**两种模式**
- **独占**：`tryAcquire` / `tryRelease`（ReentrantLock）
- **共享**：`tryAcquireShared` / `tryReleaseShared`（CountDownLatch、Semaphore）

**面试怎么答**
> 「AQS 的核心是一个 volatile 的 state 加一个 CLH 双向队列。抢锁就是 CAS 改 state，改成功就拿到锁，失败就包装成 Node 入队，然后 park 等待，前驱释放时 unpark 唤醒。
> 它把『怎么判断能不能拿』抽象成 tryAcquire 交给子类，自己只负责排队和唤醒，所以 ReentrantLock、Semaphore、CountDownLatch 都能复用它。」

## 7.2 ThreadLocal

**结构（JDK 8）**
> 每个 `Thread` 内部有一个 `ThreadLocalMap`，key 是 ThreadLocal 对象（**弱引用**），value 是强引用。

**内存泄漏的原因**
> key 是弱引用，GC 后 key 变 null，但 **value 是强引用**，如果线程一直活着（线程池），value 就永远回收不掉。

**正确用法**
```java
try {
    ctx.set(user);
    // 业务
} finally {
    ctx.remove();   // 必须 remove
}
```

**面试怎么答**
> 「ThreadLocal 的数据存在 Thread 内部的 ThreadLocalMap 里，key 是 ThreadLocal 的弱引用。
> 线程池场景下线程不会销毁，如果 key 被 GC 了但 value 还在，就泄漏了。所以必须在 finally 里 remove。
> 顺带一个坑：Spring Security 的 SecurityContext 就是 ThreadLocal，异步线程里拿不到，要手动传递。」

## 7.3 线程的六种状态

```
NEW → RUNNABLE → BLOCKED / WAITING / TIMED_WAITING → TERMINATED
```

| 状态 | 触发 |
|---|---|
| BLOCKED | 等 synchronized 锁 |
| WAITING | `wait()`、`join()`、`park()`（无超时） |
| TIMED_WAITING | `sleep(n)`、`wait(n)`、`join(n)` |

> 注意：`Thread.sleep` **不释放锁**，`Object.wait` **释放锁**。

## 7.4 volatile 与 happens-before

**volatile 的两个语义**
1. **可见性**：写立刻刷主存，读强制从主存读
2. **禁止重排序**：通过内存屏障实现

**不保证原子性**
> `i++` 是「读-改-写」三步，volatile 管不住。要原子用 `AtomicInteger`（CAS）或加锁。

**happens-before 规则（记 4 条）**
1. 程序顺序：同一线程里前面的操作先于后面的
2. 监视器锁：解锁先于后续加锁
3. volatile：写先于后续的读
4. 线程启动/终止：`start()` 前的操作对新线程可见；线程内所有操作先于 `join()` 返回

## 7.5 CAS 与 ABA

**ABA 场景**：栈顶从 A → B → A，CAS 检查还是 A 就认为没变，但中间状态已经丢了。

**解法**：加版本号（`AtomicStampedReference`）。

---

# 第 8 章 Java 17 新特性 + 虚拟线程 ★★★★★

## 8.1 值得说的四个

| 特性 | 一句话 |
|---|---|
| **record** | 不可变数据载体，自动生成构造器/getter/equals/hashCode/toString |
| **sealed** | 限定谁能继承，配合 switch 模式匹配可穷举 |
| **text block** | 多行字符串，不用拼换行 |
| **switch 模式匹配** | `case Circle c -> ...`，类型判断 + 绑定一步完成 |

## 8.2 虚拟线程（Java 21 正式，面试新热点）★★★★★

**是什么**
> JVM 调度的轻量级线程，不绑定操作系统线程。阻塞时自动让出载体线程（carrier thread）。

**解决什么问题**
> 传统平台线程 1:1 映射到 OS 线程，一个线程约 1MB 栈，几千个就到顶了。
> 虚拟线程可以轻松创建**百万级**，适合**高并发 IO 密集**场景（Web 服务、数据库调用）。

**关键认知（面试容易答错的点）**
1. **不要池化虚拟线程** —— 创建成本极低，池化反而失去意义
2. **CPU 密集型没有收益** —— 它解决的是阻塞等待，不是算力
3. **synchronized 会 pin 住载体线程**（JDK 21 的坑，JDK 24 才解决）→ 用 `ReentrantLock` 替代
4. **ThreadLocal 要慎用** —— 百万线程各存一份会爆内存

**面试怎么答**
> 「虚拟线程是 JVM 调度的，多个虚拟线程复用一个载体线程，阻塞时自动让出，所以能轻松开百万个，适合 IO 密集的高并发服务。
> 它不是银弹：CPU 密集型没收益；不能池化；JDK 21 里 synchronized 会 pin 住载体线程，要用 ReentrantLock；ThreadLocal 也要少用，因为线程数太多。」

---

# 第 9 章 速答卡：20 题一句话版

> 面试前一天只看这一页。

| # | 问题 | 一句话答案 |
|---|---|---|
| 1 | HashMap 结构 | 数组+链表+红黑树，链表≥8 且数组≥64 才树化 |
| 2 | 为什么容量 2 的幂 | (n-1)&hash 等价取模且扩容只需判断高位 |
| 3 | HashMap 线程不安全 | JDK7 成环死循环，JDK8 丢数据 |
| 4 | ConcurrentHashMap 1.8 | CAS + synchronized 锁桶头，不用分段锁 |
| 5 | 为什么不允许 null | 并发下无法区分「不存在」和「值为 null」 |
| 6 | ArrayList vs LinkedList | 数组随机访问快，链表插入快但缓存不友好；实际多用 ArrayList |
| 7 | fail-fast | modCount 检查，遍历时改结构抛 CME |
| 8 | String 不可变 | final 字节数组，好处是线程安全+常量池+hashCode 缓存 |
| 9 | 字符串常量池在哪 | JDK7 后从方法区移到堆 |
| 10 | 泛型擦除 | 编译期特性，运行时擦成上界；为了向后兼容 |
| 11 | PECS | 只读 extends，只写 super |
| 12 | JDK 代理 vs CGLIB | 接口 vs 子类；CGLIB 不能代理 final |
| 13 | Spring 默认用哪个 | Spring Boot 2 起默认 CGLIB |
| 14 | 受检 vs 非受检 | 编译器强制 vs 编程错误 |
| 15 | finally 与 return | finally 里的 return 会覆盖 try 的 |
| 16 | AQS 三件套 | volatile state + CLH 队列 + CAS |
| 17 | ThreadLocal 泄漏 | key 弱引用 value 强引用，线程池里必须 remove |
| 18 | sleep vs wait | sleep 不释放锁，wait 释放锁 |
| 19 | volatile | 可见性 + 禁重排，不保证原子性 |
| 20 | 虚拟线程 | JVM 调度、阻塞让出、别池化、CPU 密集无收益 |

---

## 附：怎么用这份文档

**如果只剩两小时**：只背第 9 章的 20 题速答卡 + 第 1 章的 HashMap / ConcurrentHashMap 两段。

**如果有一天**：按 1 → 7 → 8 → 2 → 4 的顺序（集合、并发、虚拟线程、String、代理），这五章覆盖了 Java 八股的 80%。

**答题通用结构**（和项目题一样）：
> 先给结论 → 再说机制 → 最后说取舍或坑。

例：
> 「ConcurrentHashMap 在 JDK 8 用的是 CAS 加 synchronized 锁桶头节点（结论）。
> 相比 JDK 7 的分段锁，锁粒度从 16 段细到每个桶，并发度更高（机制）。
> 代价是扩容时要协助迁移，实现更复杂（取舍）。」

# 独立测试

这些测试检查本地算法和模拟分派，不能替代游戏真机验证。需要 C11 编译器和 JDK 17+。

## 一次跑完全部

```sh
python tools/run_all_tests.py
```

会依次检查：源码三副本一致性、`profiles/157.json` 与 C 头文件一致、离线与模块的 C 回归、离线与模块的 Java 编译、Java 逻辑回归、模块 NDK 交叉编译。加 `--quick` 跳过 JDK/NDK 部分。

## 单独运行

C 测试（Windows 下 C 编译器可用 MinGW；POSIX 环境为 native 测试加 `-pthread`）：

```sh
gcc -O2 -std=c11 -Wall -Wextra -Werror tests/goal_rules_test.c -o goal-rules-test
./goal-rules-test
gcc -O2 -std=c11 -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-variable tests/native_dispatch_test.c -o native-dispatch-test -pthread
./native-dispatch-test
gcc -O2 -std=c11 -Wall -Wextra -Werror tests/goal_guard177_test.c -o goal-guard177-test
./goal-guard177-test
gcc -O2 -std=c11 -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-variable tests/guard_result_regression.c -o guard-result-test -pthread
./guard-result-test
gcc -O2 -std=c11 -Wall -Wextra -Werror tests/skin_scale_test.c -o skin-scale-test -lm
./skin-scale-test
javac -encoding UTF-8 -d test-classes src/com/phigros/offline/RksPlanner.java src/com/phigros/offline/ControlsConfig.java src/com/phigros/offline/CatalogXmlMerge.java tests/RksPlanner165Test.java tests/ControlsRulesTest.java tests/ControlsModeRegression.java tests/RksCatalogMergeTest.java
java -cp test-classes com.phigros.offline.RksPlanner165Test data/chart-catalog.tsv
java -cp test-classes com.phigros.offline.ControlsRulesTest
java -cp test-classes com.phigros.offline.ControlsModeRegression
java -cp test-classes com.phigros.offline.RksCatalogMergeTest
```

## 各测试覆盖什么

| 测试 | 覆盖 |
|---|---|
| `goal_rules_test` | 目标分数/准确率的判定规划、规则优先级、大小谱面、显式约束 |
| `goal_guard177_test` | 显式规则优先、S/FC/AT 保护、额外保护分数、离目标越远越倾向救判 |
| `native_dispatch_test` | 原生分派：并发规则、自然漏击、结果不被改写、关闭模式、配置快照、真实触摸屏蔽开关 |
| `guard_result_regression` | **判定保护 mode 3 可规划**、`record()` 始终落盘、强制结算覆盖六个展示字段、与自动游玩开关互不影响 |
| `skin_scale_test` | **音符 contain-fit**：两轴都不超原尺寸、同比例完全一致、同包各槽位缩放一致、旧公式溢出边界复现 |
| `GuardAndForcedResultTest` | 判定保护参数校验与隔离、强制结算接受「不可能组合」、准确率往返格式 |

主机 native 测试编译实际分派代码，替换游戏对象与原函数，并排除 Android/JNI/ARM64 安装部分。存档事务与独立编码交叉测试在本地匿名夹具上执行，夹具和手机数据不随源码公开。


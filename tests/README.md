# 独立测试

这些测试检查本地算法和模拟分派，不能替代游戏真机验证。需要 C11 编译器和 JDK 17+。

从仓库根目录运行（Windows 下 C 编译器可用 MinGW；POSIX 环境为 native 测试加 `-pthread`）：

```sh
gcc -O2 -std=c11 -Wall -Wextra -Werror tests/goal_rules_test.c -o goal-rules-test
./goal-rules-test
gcc -O2 -std=c11 -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-variable tests/native_dispatch_test.c -o native-dispatch-test -pthread
./native-dispatch-test
javac -encoding UTF-8 -d test-classes src/com/phigros/offline/RksPlanner.java src/com/phigros/offline/ControlsConfig.java tests/RksPlanner165Test.java tests/ControlsRulesTest.java tests/ControlsModeRegression.java
java -cp test-classes com.phigros.offline.RksPlanner165Test data/chart-catalog.tsv
java -cp test-classes com.phigros.offline.ControlsRulesTest
java -cp test-classes com.phigros.offline.ControlsModeRegression
```

主机 native 测试编译实际分派代码，替换游戏对象与原函数，并排除 Android/JNI/ARM64 安装部分。存档事务与独立编码交叉测试在本地匿名夹具上执行，夹具和手机数据不随源码公开。

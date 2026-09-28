package io.github.jiemo9527.httpshare;

/** 被 LSPosed 自身作用域 Hook 为 true；未启用模块时为 false。 */
public final class ModuleStatus {
    private ModuleStatus() {
    }

    public static boolean isActive() {
        // 防止被 R8/javac 内联：通过字段读取
        return FALSE[0];
    }

    private static final boolean[] FALSE = {false};
}

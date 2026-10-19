import java.util.Objects;

/**
 * 当前运行的应用侧身份。
 *
 * 模型生成的文本和工具参数不能改变这个身份；权限判断使用它。
 */
record AccessContext(String userId) {
    static final String DEFAULT_USER_ID = "demo-user";

    AccessContext {
        Objects.requireNonNull(userId, "userId 不能为空");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId 必须包含有效文本");
        }
    }

    static AccessContext defaultUser() {
        return new AccessContext(DEFAULT_USER_ID);
    }
}

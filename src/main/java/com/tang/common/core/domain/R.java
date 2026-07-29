package com.tang.common.core.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * Unified API envelope (aligned with tang-common-core R&lt;T&gt;).
 */
@Data
public class R<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int SUCCESS = 200;
    public static final int FAIL = 500;

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return rest(SUCCESS, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return rest(SUCCESS, "ok", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return rest(SUCCESS, msg, data);
    }

    public static <T> R<T> fail(String msg) {
        return rest(FAIL, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return rest(code, msg, null);
    }

    public boolean isSuccess() {
        return code == SUCCESS;
    }

    private static <T> R<T> rest(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}

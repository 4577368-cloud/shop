package com.tang.common.core.web.domain;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.HashMap;

/**
 * Lite AjaxResult aligned with tang-common-core for Feign pay channelList responses.
 */
@Data
@Accessors(chain = true)
public class AjaxResult extends HashMap<String, Object> implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String CODE_TAG = "code";
    public static final String MSG_TAG = "msg";
    public static final String DATA_TAG = "data";

    public AjaxResult() {}

    public AjaxResult(int code, String msg, Object data) {
        put(CODE_TAG, code);
        put(MSG_TAG, msg);
        if (data != null) {
            put(DATA_TAG, data);
        }
    }

    public static AjaxResult success(Object data) {
        return new AjaxResult(200, "ok", data);
    }

    public static AjaxResult error(String msg) {
        return new AjaxResult(500, msg, null);
    }

    public int getCode() {
        Object c = get(CODE_TAG);
        return c instanceof Number n ? n.intValue() : 500;
    }

    public String getMsg() {
        Object m = get(MSG_TAG);
        return m == null ? null : String.valueOf(m);
    }

    public Object getData() {
        return get(DATA_TAG);
    }
}

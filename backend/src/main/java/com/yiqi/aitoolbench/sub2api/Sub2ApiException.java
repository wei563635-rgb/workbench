package com.yiqi.aitoolbench.sub2api;

public class Sub2ApiException extends RuntimeException {
    private final int statusCode;

    public Sub2ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}

package com.yiqi.aitoolbench.ai;

public class ProviderGatewayException extends RuntimeException {
    private final Integer upstreamStatus;
    private final String upstreamBody;

    public ProviderGatewayException() {
        super("AI 供应商调用失败，请稍后重试");
        upstreamStatus = null;
        upstreamBody = null;
    }

    public ProviderGatewayException(Throwable cause) {
        super("AI 供应商调用失败，请稍后重试", cause);
        upstreamStatus = null;
        upstreamBody = null;
    }

    public ProviderGatewayException(int upstreamStatus, String upstreamBody) {
        super(upstreamBody);
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public String upstreamBody() {
        return upstreamBody;
    }
}

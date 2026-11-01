package com.brisklauncher.mcl.net;

/** 网络层统一异常：消息已经是可展示给用户的安全文本。 */
public class HttpException extends RuntimeException {

    public HttpException(String message) {
        super(message);
    }

    public HttpException(String message, Throwable cause) {
        super(message, cause);
    }
}

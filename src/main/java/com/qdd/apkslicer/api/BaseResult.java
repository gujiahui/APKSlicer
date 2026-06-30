package com.qdd.apkslicer.api;


import lombok.ToString;

import java.io.Serializable;

/**
 * 通用返回对象
 * Created by gjh on 2020-11-27
 */
@ToString
@SuppressWarnings("all")
public class BaseResult<T> implements Serializable {

    private Integer  code;
    private String message;
    private T data;

    protected BaseResult() {
    }

    protected BaseResult(Integer  code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
    protected BaseResult(Integer  code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 成功返回结果
     *
     * @param data 获取的数据
     */
    public static <T> BaseResult<T> success(T data) {
        return new BaseResult<T>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功返回结果
     *
     * @param data 获取的数据
     * @param  message 提示信息
     */
    public static <T> BaseResult<T> success(T data, String message) {
        return new BaseResult<T>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 成功返回提示信息
     *
     * @param  message 提示信息
     */
    public static <T> BaseResult<T> success(String message) {
        return new BaseResult<T>(ResultCode.SUCCESS.getCode(), message);
    }

    /**
     * 失败返回结果
     * @param errorCode 错误码
     */
    public static <T> BaseResult<T> failed(IErrorCode errorCode) {
        return new BaseResult<T>(errorCode.getCode(), errorCode.getMessage(), null);
    }


    /**
     * 失败返回结果
     * @param message 提示信息
     */
    public static <T> BaseResult<T> failed(String message) {
        return new BaseResult<T>(ResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 失败返回结果
     * @param message 提示信息
     */
    public static <T> BaseResult<T> failed(Integer  code, String message) {
        return new BaseResult<T>(code, message, null);
    }

    public static <T> BaseResult<T> failed(T data, String message) {
        return new BaseResult<T>(ResultCode.FAILED.getCode(), message, data);
    }

    /**
     * 失败返回结果
     */
    public static <T> BaseResult<T> failed() {
        return failed(ResultCode.FAILED);
    }

    /**
     * 失败返回结果
     * @param errorCode 错误码
     */
    public static <T> BaseResult<T> error(IErrorCode errorCode) {
        return new BaseResult<T>(errorCode.getCode(), errorCode.getMessage(), null);
    }


    /**
     * 失败返回结果
     * @param message 提示信息
     */
    public static <T> BaseResult<T> error(String message) {
        return new BaseResult<T>(ResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 失败返回结果
     * @param message 提示信息
     */
    public static <T> BaseResult<T> error(Integer  code, String message) {
        return new BaseResult<T>(code, message, null);
    }

    public static <T> BaseResult<T> error(T data, String message) {
        return new BaseResult<T>(ResultCode.FAILED.getCode(), message, data);
    }

    /**
     * 失败返回结果
     */
    public static <T> BaseResult<T> error() {
        return failed(ResultCode.FAILED);
    }
    /**
     * 参数验证失败返回结果
     */
    public static <T> BaseResult<T> validateFailed() {
        return failed(ResultCode.VALIDATE_FAILED);
    }

    /**
     * 参数验证失败返回结果
     * @param message 提示信息
     */
    public static <T> BaseResult<T> validateFailed(String message) {
        return new BaseResult<T>(ResultCode.VALIDATE_FAILED.getCode(), message, null);
    }

    /**
     * 未登录返回结果
     */
    public static <T> BaseResult<T> unauthorized(T data) {
        return new BaseResult<T>(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage(), data);
    }

    /**
     * 未授权返回结果
     */
    public static <T> BaseResult<T> forbidden(T data) {
        return new BaseResult<T>(ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage(), data);
    }

    public long getCode() {
        return code;
    }

    public void setCode(Integer  code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
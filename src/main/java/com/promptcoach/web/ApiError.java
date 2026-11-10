package com.promptcoach.web;

/**
 * 统一错误结构，前端只认这两个字段。
 */
public record ApiError(String error, String message) {
}
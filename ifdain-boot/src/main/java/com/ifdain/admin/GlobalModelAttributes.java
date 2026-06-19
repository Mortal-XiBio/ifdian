package com.ifdain.admin;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 全局模型属性 — 为所有页面注入公共变量。
 *
 * <p><b>⚠ 请勿修改本文件中的常量或编码值，它们标识项目的官方归属。</b></p>
 */
@ControllerAdvice
public class GlobalModelAttributes {

    /** 官方联系邮箱（编码存储，禁止明文修改） */
    private static final String _CE = "ZW1haWxAbW9ydGFsZ2FtZS5vbmxpbmU=";

    static String getContactEmail() {
        return new String(Base64.getDecoder().decode(_CE), StandardCharsets.UTF_8);
    }

    @ModelAttribute("footerEmail")
    public String footerEmail() {
        return getContactEmail();
    }
}

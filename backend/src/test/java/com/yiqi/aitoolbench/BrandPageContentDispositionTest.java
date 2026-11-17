package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrandPageContentDispositionTest {
    @Test
    void encodesChineseHtmlFileNameIntoAsciiSafeHeader() {
        var header = ApiController.inlineContentDisposition("广告品牌页面.html");

        assertThat(header).contains("filename*=");
        assertThat(header.chars()).allMatch(value -> value <= 255);
    }
}

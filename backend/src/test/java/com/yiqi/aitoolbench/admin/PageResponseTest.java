package com.yiqi.aitoolbench.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void slicesItemsAndReportsPaginationMetadata() {
        PageResponse<Integer> page = PageResponse.of(List.of(1, 2, 3, 4, 5), 2, 2);

        assertThat(page.items()).containsExactly(3, 4);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void normalizesInvalidArgumentsAndCapsPageSize() {
        PageResponse<Integer> page = PageResponse.of(List.of(1, 2, 3), 0, 1000);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(100);
        assertThat(page.items()).containsExactly(1, 2, 3);
    }

    @Test
    void returnsEmptyItemsForPagePastTheEnd() {
        PageResponse<Integer> page = PageResponse.of(List.of(1, 2), 3, 1);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void buildsMetadataForDatabasePageWithoutSlicingAgain() {
        PageResponse<Integer> page = PageResponse.page(List.of(11, 12), 2, 2, 5);

        assertThat(page.items()).containsExactly(11, 12);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }
}

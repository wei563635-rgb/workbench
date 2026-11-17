package com.yiqi.aitoolbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BrandPageRepositoryTest {
    private BrandPageRepository repository;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:brand-pages-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa", "");
        repository = new BrandPageRepository(new JdbcTemplate(dataSource));
    }

    @Test
    void restartingDoesNotRestoreOwningDepartmentAfterAclWasExplicitlyChanged() {
        var page = repository.saveOrReplace("品牌甲", "dept-owner", "file-a", "a.html", "user-1",
                java.util.List.of(), java.util.List.of("user-2"));

        repository = new BrandPageRepository(new JdbcTemplate(dataSource));

        var reloaded = repository.findActiveById(page.id()).orElseThrow();
        assertThat(reloaded.viewerDepartmentIds()).isEmpty();
        assertThat(reloaded.viewerUserIds()).containsExactly("user-2");
    }

    @Test
    void sameBrandCanExistInDifferentDepartmentsAndReplacesOnlyWithinDepartment() {
        var departmentA = repository.saveOrReplace("品牌甲", "dept-a", "file-a", "a.html", "user-1",
                java.util.List.of("dept-a"), java.util.List.of());
        var departmentB = repository.saveOrReplace("品牌甲", "dept-b", "file-b", "b.html", "user-1",
                java.util.List.of("dept-b"), java.util.List.of());
        var replacement = repository.saveOrReplace("品牌甲", "dept-a", "file-a2", "a2.html", "user-2",
                java.util.List.of("dept-a", "dept-c"), java.util.List.of("user-3"));

        assertThat(departmentA.id()).isEqualTo(replacement.id());
        assertThat(departmentB.id()).isNotEqualTo(replacement.id());
        assertThat(repository.search("品牌甲")).extracting(BrandPageRepository.BrandPage::departmentId)
                .containsExactlyInAnyOrder("dept-a", "dept-b");
        assertThat(repository.search("品牌甲", "dept-b"))
                .extracting(BrandPageRepository.BrandPage::departmentId)
                .containsExactly("dept-b");
        assertThat(repository.findActiveById(departmentA.id()).orElseThrow().fileId()).isEqualTo("file-a2");
        assertThat(replacement.viewerDepartmentIds()).containsExactly("dept-a", "dept-c");
        assertThat(replacement.viewerUserIds()).containsExactly("user-3");
    }

    @Test
    void editsMetadataKeepsExistingFileAndRejectsAnotherPageUniqueKey() {
        var first = repository.saveOrReplace("品牌甲", "dept-a", "file-a", "a.html", "user-1",
                java.util.List.of("dept-a"), java.util.List.of());
        repository.saveOrReplace("品牌乙", "dept-b", "file-b", "b.html", "user-1",
                java.util.List.of("dept-b"), java.util.List.of());

        var edited = repository.update(first.id(), "品牌甲新", "dept-c", null, null, "user-2",
                java.util.List.of("dept-c"), java.util.List.of("user-9"));

        assertThat(edited.brandName()).isEqualTo("品牌甲新");
        assertThat(edited.departmentId()).isEqualTo("dept-c");
        assertThat(edited.fileId()).isEqualTo("file-a");
        assertThat(edited.fileName()).isEqualTo("a.html");
        assertThat(edited.createdBy()).isEqualTo("user-1");
        assertThat(edited.viewerDepartmentIds()).containsExactly("dept-c");
        assertThat(edited.viewerUserIds()).containsExactly("user-9");
        assertThatThrownBy(() -> repository.update(first.id(), "品牌乙", "dept-b",
                null, null, "user-2", java.util.List.of("dept-b"), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }
}

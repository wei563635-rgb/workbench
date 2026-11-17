package com.yiqi.aitoolbench;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BrandPageRepository {
    private static final String SUBJECT_DEPARTMENT = "DEPARTMENT";
    private static final String SUBJECT_USER = "USER";
    private final JdbcTemplate jdbc;

    public BrandPageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                create table if not exists brand_html_page (
                    id varchar(120) primary key,
                    brand_name varchar(160) not null,
                    department_id varchar(80),
                    file_id varchar(120) not null,
                    file_name varchar(255) not null,
                    created_by varchar(80) not null,
                    created_at varchar(40) not null,
                    updated_at varchar(40) not null,
                    deleted_at varchar(40)
                )
                """);
        jdbc.execute("alter table brand_html_page add column if not exists department_id varchar(80)");
        jdbc.execute("drop index if exists ux_brand_html_page_name");
        jdbc.execute("""
                create unique index if not exists ux_brand_html_page_department_name
                on brand_html_page (department_id, brand_name)
                """);
        jdbc.execute("""
                create table if not exists brand_html_page_acl (
                    page_id varchar(120) not null,
                    subject_type varchar(20) not null,
                    subject_id varchar(120) not null,
                    created_at varchar(40) not null,
                    primary key (page_id, subject_type, subject_id)
                )
                """);
        jdbc.execute("""
                create index if not exists ix_brand_html_page_acl_subject
                on brand_html_page_acl (subject_type, subject_id, page_id)
                """);
        migrateLegacyDepartmentAccess();
    }

    public List<BrandPage> search(String keyword) {
        return search(keyword, null);
    }

    public List<BrandPage> search(String keyword, String departmentId) {
        var value = keyword == null ? "" : keyword.trim();
        var pattern = "%" + value.replace("%", "\\%").replace("_", "\\_") + "%";
        var normalizedDepartmentId = departmentId == null || departmentId.isBlank() ? null : departmentId.trim();
        if (normalizedDepartmentId == null) {
            return attachAcl(jdbc.query("""
                    select id, brand_name, department_id, file_id, file_name, created_by, created_at, updated_at
                    from brand_html_page
                    where deleted_at is null
                      and lower(brand_name) like lower(?) escape '\\'
                    order by updated_at desc, id desc
                    """, (rs, rowNum) -> map(rs), pattern));
        }
        return attachAcl(jdbc.query("""
                select id, brand_name, department_id, file_id, file_name, created_by, created_at, updated_at
                from brand_html_page
                where deleted_at is null
                  and lower(brand_name) like lower(?) escape '\\'
                  and department_id=?
                order by updated_at desc, id desc
                """, (rs, rowNum) -> map(rs), pattern, normalizedDepartmentId));
    }

    public Optional<BrandPage> findActiveById(String id) {
        var pages = jdbc.query("""
                select id, brand_name, department_id, file_id, file_name, created_by, created_at, updated_at
                from brand_html_page where id=? and deleted_at is null
                """, (rs, rowNum) -> map(rs), id);
        return attachAcl(pages).stream().findFirst();
    }

    public BrandPage saveOrReplace(String brandName, String departmentId, String fileId, String fileName,
                                   String createdBy, Collection<String> viewerDepartmentIds,
                                   Collection<String> viewerUserIds) {
        var now = Instant.now();
        var existing = jdbc.query("""
                select id, brand_name, department_id, file_id, file_name, created_by, created_at, updated_at
                from brand_html_page where brand_name=? and department_id=? and deleted_at is null
                """, (rs, rowNum) -> map(rs), brandName, departmentId).stream().findFirst();
        if (existing.isPresent()) {
            var page = existing.get();
            jdbc.update("""
                    update brand_html_page set file_id=?, file_name=?, created_by=?, updated_at=? where id=?
                    """, fileId, fileName, createdBy, now.toString(), page.id());
            replaceAcl(page.id(), viewerDepartmentIds, viewerUserIds, now);
            return findActiveById(page.id()).orElseThrow();
        }
        var id = "brand_page_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into brand_html_page
                    (id, brand_name, department_id, file_id, file_name, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, brandName, departmentId, fileId, fileName, createdBy, now.toString(), now.toString());
        replaceAcl(id, viewerDepartmentIds, viewerUserIds, now);
        return findActiveById(id).orElseThrow();
    }

    public BrandPage update(String id, String brandName, String departmentId, String fileId, String fileName,
                            String updatedBy, Collection<String> viewerDepartmentIds,
                            Collection<String> viewerUserIds) {
        var current = findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("品牌 HTML 页面不存在"));
        var conflict = jdbc.queryForObject("""
                select count(*) from brand_html_page
                where id<>? and brand_name=? and department_id=? and deleted_at is null
                """, Integer.class, id, brandName, departmentId);
        if (conflict != null && conflict > 0) {
            throw new IllegalArgumentException("该组织部门下已存在同名品牌页面");
        }
        var effectiveFileId = fileId == null ? current.fileId() : fileId;
        var effectiveFileName = fileName == null ? current.fileName() : fileName;
        var now = Instant.now();
        jdbc.update("""
                update brand_html_page
                set brand_name=?, department_id=?, file_id=?, file_name=?, updated_at=?
                where id=? and deleted_at is null
                """, brandName, departmentId, effectiveFileId, effectiveFileName, now.toString(), id);
        replaceAcl(id, viewerDepartmentIds, viewerUserIds, now);
        return findActiveById(id).orElseThrow();
    }

    public void delete(String id) {
        jdbc.update("delete from brand_html_page_acl where page_id=?", id);
        jdbc.update("delete from brand_html_page where id=?", id);
    }

    private BrandPage map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BrandPage(rs.getString("id"), rs.getString("brand_name"), rs.getString("department_id"),
                rs.getString("file_id"), rs.getString("file_name"), rs.getString("created_by"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")),
                List.of(), List.of());
    }

    public record BrandPage(String id, String brandName, String departmentId, String fileId, String fileName,
                            String createdBy, Instant createdAt, Instant updatedAt,
                            List<String> viewerDepartmentIds, List<String> viewerUserIds) {}

    private void migrateLegacyDepartmentAccess() {
        jdbc.update("""
                insert into brand_html_page_acl (page_id, subject_type, subject_id, created_at)
                select page.id, ?, page.department_id, page.updated_at
                from brand_html_page page
                where page.deleted_at is null
                  and page.department_id is not null
                  and page.department_id <> ''
                  and not exists (
                      select 1 from brand_html_page_acl acl
                      where acl.page_id=page.id
                  )
                """, SUBJECT_DEPARTMENT);
    }

    private void replaceAcl(String pageId, Collection<String> viewerDepartmentIds,
                            Collection<String> viewerUserIds, Instant now) {
        jdbc.update("delete from brand_html_page_acl where page_id=?", pageId);
        insertAcl(pageId, SUBJECT_DEPARTMENT, viewerDepartmentIds, now);
        insertAcl(pageId, SUBJECT_USER, viewerUserIds, now);
    }

    private void insertAcl(String pageId, String subjectType, Collection<String> subjectIds, Instant now) {
        if (subjectIds == null) {
            return;
        }
        subjectIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .forEach(subjectId -> jdbc.update("""
                        insert into brand_html_page_acl (page_id, subject_type, subject_id, created_at)
                        values (?, ?, ?, ?)
                        """, pageId, subjectType, subjectId, now.toString()));
    }

    private List<BrandPage> attachAcl(List<BrandPage> pages) {
        if (pages.isEmpty()) {
            return pages;
        }
        var pageIds = pages.stream().map(BrandPage::id).toList();
        var placeholders = String.join(",", java.util.Collections.nCopies(pageIds.size(), "?"));
        var departments = new LinkedHashMap<String, List<String>>();
        var users = new LinkedHashMap<String, List<String>>();
        jdbc.query("""
                        select page_id, subject_type, subject_id
                        from brand_html_page_acl
                        where page_id in (%s)
                        order by subject_type, subject_id
                        """.formatted(placeholders),
                rs -> {
                    var target = SUBJECT_DEPARTMENT.equals(rs.getString("subject_type")) ? departments : users;
                    target.computeIfAbsent(rs.getString("page_id"), ignored -> new ArrayList<>())
                            .add(rs.getString("subject_id"));
                }, pageIds.toArray());
        return pages.stream()
                .map(page -> new BrandPage(page.id(), page.brandName(), page.departmentId(), page.fileId(),
                        page.fileName(), page.createdBy(), page.createdAt(), page.updatedAt(),
                        List.copyOf(departments.getOrDefault(page.id(), List.of())),
                        List.copyOf(users.getOrDefault(page.id(), List.of()))))
                .toList();
    }
}

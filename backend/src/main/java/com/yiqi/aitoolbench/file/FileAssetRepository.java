package com.yiqi.aitoolbench.file;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileAssetRepository {
    private final JdbcTemplate jdbc;

    public FileAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists file_asset (
                    id varchar(120) primary key,
                    project_id varchar(120),
                    run_id varchar(120),
                    result_id varchar(120),
                    storage_key varchar(500) not null,
                    file_name varchar(255) not null,
                    content_type varchar(160),
                    file_size bigint not null,
                    checksum varchar(160),
                    visibility varchar(40) not null,
                    created_by varchar(80) not null,
                    created_at varchar(40) not null,
                    deleted_at varchar(40)
                )
                """);
    }

    public void save(FileAsset asset) {
        jdbc.update("""
                insert into file_asset
                (id, project_id, run_id, result_id, storage_key, file_name, content_type,
                 file_size, checksum, visibility, created_by, created_at, deleted_at)
                values (?, ?, ?, null, ?, ?, ?, ?, null, ?, ?, ?, null)
                """, asset.id(), asset.projectId(), asset.runId(), asset.storageKey(), asset.fileName(),
                asset.contentType(), asset.fileSize(), asset.visibility(), asset.createdBy(),
                asset.createdAt().toString());
    }

    public Optional<FileAsset> findActiveById(String id) {
        return jdbc.query("""
                        select id, project_id, run_id, storage_key, file_name, content_type,
                               file_size, visibility, created_by, created_at
                        from file_asset where id = ? and deleted_at is null
                        """, (rs, row) -> new FileAsset(
                        rs.getString("id"), rs.getString("project_id"), rs.getString("run_id"),
                        rs.getString("storage_key"), rs.getString("file_name"),
                        rs.getString("content_type"), rs.getLong("file_size"),
                        rs.getString("visibility"), rs.getString("created_by"),
                        Instant.parse(rs.getString("created_at"))), id)
                .stream().findFirst();
    }

    public record FileAsset(String id, String projectId, String runId, String storageKey,
                            String fileName, String contentType, long fileSize,
                            String visibility, String createdBy, Instant createdAt) {}
}

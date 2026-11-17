package com.yiqi.aitoolbench.run;

import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ToolRunRepository {
    private final JdbcTemplate jdbc;

    public ToolRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists tool_run (
                    run_id varchar(120) primary key,
                    tool_key varchar(120) not null,
                    user_id varchar(80) not null,
                    project_id varchar(120),
                    status varchar(40) not null,
                    event_type varchar(40) not null,
                    input_summary text,
                    output_summary text,
                    output_data text not null,
                    started_at varchar(40) not null,
                    finished_at varchar(40),
                    department_id varchar(80),
                    input_payload text,
                    result_payload text,
                    error_message text,
                    created_at varchar(40),
                    updated_at varchar(40)
                )
                """);
        addColumnIfMissing("tool_run", "project_id", "varchar(120)");
        addColumnIfMissing("tool_run", "department_id", "varchar(80)");
        addColumnIfMissing("tool_run", "input_payload", "text");
        addColumnIfMissing("tool_run", "result_payload", "text");
        addColumnIfMissing("tool_run", "error_message", "text");
        addColumnIfMissing("tool_run", "created_at", "varchar(40)");
        addColumnIfMissing("tool_run", "updated_at", "varchar(40)");
        jdbc.execute("""
                create table if not exists tool_run_event (
                    id varchar(120) primary key,
                    run_id varchar(120) not null,
                    event_type varchar(80) not null,
                    payload text not null,
                    created_by varchar(80),
                    created_at varchar(40) not null,
                    sort_order int not null
                )
                """);
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var columns = connection.getMetaData().getColumns(null, null, table, column);
            if (!columns.next()) {
                jdbc.execute("alter table " + table + " add column " + column + " " + definition);
            }
        } catch (SQLException ignored) {
            // Metadata checks are best-effort to keep startup idempotent across H2 and PostgreSQL.
        } catch (RuntimeException ignored) {
            // Production service accounts may not own legacy tables. DDL is best-effort only.
        }
    }
}

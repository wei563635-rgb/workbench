package com.yiqi.aitoolbench.result;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectResultRepository {
    private final JdbcTemplate jdbc;

    public ProjectResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createSchema();
    }

    private void createSchema() {
        jdbc.execute("""
                create table if not exists project_result (
                    id varchar(120) primary key,
                    project_id varchar(120) not null,
                    run_id varchar(120) not null,
                    tool_key varchar(120) not null,
                    title varchar(200) not null,
                    summary text,
                    result_snapshot text not null,
                    status varchar(40) not null,
                    submitted_by varchar(80) not null,
                    created_at varchar(40) not null,
                    removed_by varchar(80),
                    removed_at varchar(40),
                    remove_reason text
                )
                """);
    }
}

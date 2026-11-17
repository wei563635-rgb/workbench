package com.yiqi.aitoolbench.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:relay-organization;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RelayOrganizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String token;

    JsonNode request(MockHttpServletRequestBuilder request,Object body) throws Exception {
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    @Test void managedOrganizationAndPrimaryDepartmentDriveQuotaInheritance() throws Exception {
        token=request(post("/api/auth/login"),Map.of("account","admin","password","admin123")).get("accessToken").asText();
        var departments=request(get("/api/admin/organization-departments"),null);
        String root=departments.get(0).get("id").asText();
        String other=departments.get(1).get("id").asText();
        var child=request(post("/api/admin/organization-departments"),Map.of("name","配额子部门","parentId",root));
        String childId=child.get("id").asText();
        request(put("/api/admin/relay/quotas/departments/"+root),Map.of("id",root,"personalLimit",100,"poolLimit",200,"concurrency",2));
        request(put("/api/admin/relay/quotas/departments/"+other),Map.of("id",other,"personalLimit",300,"poolLimit",500,"concurrency",3));
        request(put("/api/admin/users/u_admin"),Map.of("name","系统管理员","roleIds",List.of("admin"),"departmentKeys",List.of(childId,other),"primaryDepartmentId",childId));
        var quotas=request(get("/api/admin/relay/quotas"),null);
        assertThat(quotas.get("departments")).extracting(d->d.get("id").asText()).contains(root,other).doesNotContain("market",childId);
        var employee=quotas.get("employees").get(0);
        assertThat(employee.get("departmentId").asText()).isEqualTo(root);
        assertThat(employee.get("personalLimit").asLong()).isEqualTo(100);
        request(put("/api/admin/users/u_admin"),Map.of("name","系统管理员","roleIds",List.of("admin"),"departmentKeys",List.of(childId,other),"primaryDepartmentId",other));
        employee=request(get("/api/admin/relay/quotas"),null).get("employees").get(0);
        assertThat(employee.get("departmentId").asText()).isEqualTo(other);
        assertThat(employee.get("personalLimit").asLong()).isEqualTo(300);
        assertThat(employee.get("concurrency").asInt()).isEqualTo(3);
    }
}

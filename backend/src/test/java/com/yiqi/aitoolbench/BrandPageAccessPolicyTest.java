package com.yiqi.aitoolbench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrandPageAccessPolicyTest {
    @Test
    void allowsExactDepartmentOrExplicitUserWithoutDepartmentInheritance() {
        assertTrue(BrandPageAccessPolicy.canAccess(false, "user-a", List.of("dept-a", "dept-b"),
                "creator", List.of("dept-b"), List.of()));
        assertTrue(BrandPageAccessPolicy.canAccess(false, "user-a", List.of("dept-other"),
                "creator", List.of(), List.of("user-a")));
        assertFalse(BrandPageAccessPolicy.canAccess(false, "user-a", List.of("dept-parent"),
                "creator", List.of("dept-child"), List.of()));
        assertFalse(BrandPageAccessPolicy.canAccess(false, "user-a", List.of("dept-child"),
                "creator", List.of("dept-parent"), List.of()));
    }

    @Test
    void creatorKeepsAccessWithoutAcl() {
        assertTrue(BrandPageAccessPolicy.canAccess(false, "creator", List.of(),
                "creator", List.of(), List.of()));
        assertFalse(BrandPageAccessPolicy.canAccess(false, "user-a", List.of("dept-a"),
                "creator", List.of(), List.of()));
    }

    @Test
    void unrestrictedAdministratorCanAccessEveryDepartment() {
        assertTrue(BrandPageAccessPolicy.canAccess(true, "admin", List.of(),
                "creator", List.of(), List.of()));
    }
}

/**
 * Unit tests for AntsPlatformClient.updateAgentDisplayName().
 *
 * Tests input validation for the updateAgentDisplayName method.
 * Note: HTTP-level tests would require mocking OkHttp or integration testing.
 */
package com.ants.platform.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

/**
 * Tests for the updateAgentDisplayName method in AntsPlatformClient.
 *
 * <p>These tests focus on input validation. HTTP-level testing would require
 * either mocking OkHttp or integration tests against a real server.
 */
class UpdateAgentDisplayNameTest {

    private AntsPlatformClient client;

    @BeforeEach
    void setUp() {
        // Create a client with minimal setup for validation tests
        // Note: This client won't have a projectId set, so HTTP calls will fail
        // but validation tests will work
        client = AntsPlatformClient.builder()
            .credentials("pk-test", "sk-test")
            .url("http://localhost:3000")
            .build();
    }

    // ========== agentId Validation Tests ==========

    @Test
    void testUpdateAgentDisplayName_NullAgentId() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName(null, "New Display Name");
        }, "Should reject null agentId");
    }

    @Test
    void testUpdateAgentDisplayName_EmptyAgentId() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("", "New Display Name");
        }, "Should reject empty agentId");
    }

    @Test
    void testUpdateAgentDisplayName_InvalidAgentIdTooShort() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8", "New Display Name");
        }, "Should reject agentId shorter than 16 characters");
    }

    @Test
    void testUpdateAgentDisplayName_InvalidAgentIdTooLong() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83a12345", "New Display Name");
        }, "Should reject agentId longer than 16 characters");
    }

    @Test
    void testUpdateAgentDisplayName_InvalidAgentIdNonHex() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83g", "New Display Name");
        }, "Should reject agentId with non-hex characters (g)");
    }

    @Test
    void testUpdateAgentDisplayName_InvalidAgentIdUppercaseHex() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5D15C8E8B4AEE83A", "New Display Name");
        }, "Should reject agentId with uppercase hex (must be lowercase)");
    }

    @Test
    void testUpdateAgentDisplayName_InvalidAgentIdWithSpaces() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15 c8e8 b4ae", "New Display Name");
        }, "Should reject agentId with spaces");
    }

    // ========== displayName Validation Tests ==========

    @Test
    void testUpdateAgentDisplayName_NullDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83a", null);
        }, "Should reject null displayName");
    }

    @Test
    void testUpdateAgentDisplayName_EmptyDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83a", "");
        }, "Should reject empty displayName");
    }

    @Test
    void testUpdateAgentDisplayName_WhitespaceOnlyDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83a", "   ");
        }, "Should reject whitespace-only displayName");
    }

    @Test
    void testUpdateAgentDisplayName_TabsOnlyDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83a", "\t\t");
        }, "Should reject tabs-only displayName");
    }

    // ========== Project ID Validation Tests ==========

    @Test
    void testUpdateAgentDisplayName_NoProjectId() {
        // Create a client that will have null projectId
        // (API fetch fails when no real server is available)
        AntsPlatformClient clientWithoutProject = AntsPlatformClient.builder()
            .credentials("pk-test", "sk-test")
            .url("http://invalid-server:9999")
            .build();

        // The client should have null projectId since the API call failed
        assertNull(clientWithoutProject.getProjectId());

        // Should throw IllegalStateException when trying to update without projectId
        assertThrows(IllegalStateException.class, () -> {
            clientWithoutProject.updateAgentDisplayName("5d15c8e8b4aee83a", "New Name");
        }, "Should throw IllegalStateException when projectId is not available");
    }

    @Test
    void testUpdateAgentDisplayName_WithManuallySetProjectId() {
        // Create a client and manually set projectId
        AntsPlatformClient clientWithProject = AntsPlatformClient.builder()
            .credentials("pk-test", "sk-test")
            .url("http://localhost:3000")
            .build();

        clientWithProject.setProjectId("proj-test-123");

        // Validation should pass (but HTTP call will fail since no real server)
        // We're just verifying that the method doesn't throw validation exceptions
        assertEquals("proj-test-123", clientWithProject.getProjectId());

        // The HTTP call will either fail with network error or return false (401/404)
        // The important thing is we got past the validation (no IllegalArgumentException)
        // In a real test, we'd mock the HTTP client
        try {
            boolean result = clientWithProject.updateAgentDisplayName("5d15c8e8b4aee83a", "QA Agent v2.0");
            // If we get here, HTTP call completed (likely returned 401 unauthorized = false)
            // This is fine - validation passed, which is what we're testing
            assertFalse(result, "Should return false for unauthorized request");
        } catch (Exception e) {
            // Network error is also acceptable - validation still passed
            assertTrue(
                e.getMessage().contains("Network error") ||
                e.getMessage().contains("Failed to connect"),
                "Should fail with network error, not validation error"
            );
        }
    }

    // ========== Valid Input Tests ==========

    @Test
    void testUpdateAgentDisplayName_ValidInputsPassValidation() {
        // Set up a client with a project ID
        client.setProjectId("proj-test-123");

        // These should all pass validation (but fail on HTTP call)
        String[] validAgentIds = {
            "5d15c8e8b4aee83a",
            "0000000000000000",
            "ffffffffffffffff",
            "1234567890abcdef"
        };

        String[] validDisplayNames = {
            "QA Agent v2.0",
            "Customer Support Bot",
            "Sales Assistant",
            "  Trimmed Name  ", // Should be trimmed
            "Agent with unicode: こんにちは"
        };

        for (String agentId : validAgentIds) {
            for (String displayName : validDisplayNames) {
                // Should not throw validation exceptions
                // Will throw network error, which is expected
                try {
                    client.updateAgentDisplayName(agentId, displayName);
                } catch (IllegalArgumentException e) {
                    fail("Valid input should not throw IllegalArgumentException: " +
                        "agentId=" + agentId + ", displayName=" + displayName);
                } catch (IllegalStateException e) {
                    fail("Should not throw IllegalStateException when projectId is set: " +
                        "agentId=" + agentId + ", displayName=" + displayName);
                } catch (Exception e) {
                    // Expected network error - validation passed
                }
            }
        }
    }

    @Test
    void testAgentIdPattern_ExactLength() {
        // Agent ID must be exactly 16 characters
        client.setProjectId("proj-test-123");

        // 15 characters - should fail
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83", "Name");
        });

        // 17 characters - should fail
        assertThrows(IllegalArgumentException.class, () -> {
            client.updateAgentDisplayName("5d15c8e8b4aee83aa", "Name");
        });
    }
}

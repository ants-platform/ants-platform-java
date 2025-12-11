/**
 * Unit tests for AgentIdGenerator.
 *
 * Tests BLAKE2b-64 based agent ID generation matching Python SDK behavior.
 * Formula: agent_id = BLAKE2b-64(agent_name + project_id)
 */
package com.ants.platform.client.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for the AgentIdGenerator class.
 * Ensures deterministic agent ID generation matching Python SDK behavior.
 *
 * <p>Key Design Decision: Include project_id (not org_id) in hash for transfer safety
 * - When projects transfer between organizations, projectId stays constant
 * - organizationId changes, but projectId doesn't
 * - Result: agent_id remains stable across transfers
 */
class AgentIdGeneratorTest {

    @Test
    void testGenerateAgentId_BasicGeneration() {
        String agentId = AgentIdGenerator.generateAgentId(
            "qa-agent",
            "proj-customer-support"
        );

        assertNotNull(agentId);
        assertEquals(16, agentId.length(), "Agent ID should be 16 characters");
        assertTrue(agentId.matches("[0-9a-f]+"), "Agent ID should be hexadecimal");
    }

    @Test
    void testGenerateAgentId_Deterministic() {
        String agentId1 = AgentIdGenerator.generateAgentId(
            "test-agent",
            "proj-456"
        );
        String agentId2 = AgentIdGenerator.generateAgentId(
            "test-agent",
            "proj-456"
        );

        assertEquals(agentId1, agentId2, "Same inputs should produce same agent ID");
    }

    @Test
    void testGenerateAgentId_DifferentAgentNames() {
        String agentId1 = AgentIdGenerator.generateAgentId(
            "agent-a",
            "proj-456"
        );
        String agentId2 = AgentIdGenerator.generateAgentId(
            "agent-b",
            "proj-456"
        );

        assertNotEquals(agentId1, agentId2, "Different agent names should produce different agent IDs");
    }

    @Test
    void testGenerateAgentId_DifferentProject() {
        String agentId1 = AgentIdGenerator.generateAgentId(
            "agent",
            "proj-a"
        );
        String agentId2 = AgentIdGenerator.generateAgentId(
            "agent",
            "proj-b"
        );

        assertNotEquals(agentId1, agentId2, "Different projects should produce different agent IDs");
    }

    @Test
    void testGenerateAgentId_NullAgentName() {
        assertThrows(IllegalArgumentException.class, () -> {
            AgentIdGenerator.generateAgentId(null, "proj-123");
        });
    }

    @Test
    void testGenerateAgentId_EmptyAgentName() {
        assertThrows(IllegalArgumentException.class, () -> {
            AgentIdGenerator.generateAgentId("", "proj-123");
        });
    }

    @Test
    void testGenerateAgentId_NullProjectId() {
        assertThrows(IllegalArgumentException.class, () -> {
            AgentIdGenerator.generateAgentId("agent", null);
        });
    }

    @Test
    void testGenerateAgentId_EmptyProjectId() {
        assertThrows(IllegalArgumentException.class, () -> {
            AgentIdGenerator.generateAgentId("agent", "");
        });
    }

    @Test
    void testGenerateAgentId_SpecialCharacters() {
        // Should handle special characters in inputs
        String agentId = AgentIdGenerator.generateAgentId(
            "agent-with-unicode-\u00e9",
            "proj-with-spaces and chars"
        );

        assertNotNull(agentId);
        assertEquals(16, agentId.length());
        assertTrue(agentId.matches("[0-9a-f]+"));
    }

    @Test
    void testGenerateAgentId_LongInputs() {
        // Should handle very long inputs
        String longAgent = "agent-" + "z".repeat(1000);
        String longProj = "proj-" + "y".repeat(1000);

        String agentId = AgentIdGenerator.generateAgentId(longAgent, longProj);

        assertNotNull(agentId);
        assertEquals(16, agentId.length());
        assertTrue(agentId.matches("[0-9a-f]+"));
    }

    @Test
    void testAgentIdLength_Constant() {
        assertEquals(16, AgentIdGenerator.AGENT_ID_LENGTH, "Agent ID length constant should be 16");
    }

    // Real-world examples from Python SDK docs

    @Test
    void testGenerateAgentId_RealWorldExample() {
        // From SDK_CHANGES.md documentation
        String agentId = AgentIdGenerator.generateAgentId(
            "qa_agent",
            "proj-customer-support"
        );

        assertNotNull(agentId);
        assertEquals(16, agentId.length());
        assertTrue(agentId.matches("[0-9a-f]+"));
    }

    @Test
    void testGenerateAgentId_OrderMatters() {
        // Hash order: agent_name first, then project_id (matching Python SDK)
        // Different order of same strings should produce different results
        String agentId1 = AgentIdGenerator.generateAgentId("abc", "xyz");
        String agentId2 = AgentIdGenerator.generateAgentId("xyz", "abc");

        assertNotEquals(agentId1, agentId2, "Hash order should matter");
    }

    @Test
    void testGenerateAgentId_SameAgentDifferentProjects() {
        // Same agent in different projects should have different IDs
        String agentId1 = AgentIdGenerator.generateAgentId("my-agent", "project-1");
        String agentId2 = AgentIdGenerator.generateAgentId("my-agent", "project-2");

        assertNotEquals(agentId1, agentId2,
            "Same agent in different projects should have different IDs");
    }
}

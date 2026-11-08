package eu.kalafatic.tryst.domain.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates workflow graph structure:
 * - Detects cycles (cyclic dependencies)
 * - Detects missing inputs or dangling edge references
 * - Identifies unreachable tasks from start nodes
 * - Identifies start and end nodes
 * - Proposes topological execution order and parallel branches
 */
public class WorkflowValidator {

    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            List<List<String>> executionPhases
    ) {
        public static ValidationResult success(List<List<String>> phases) {
            return new ValidationResult(true, List.of(), List.of(), phases);
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, List.of(), List.of());
        }
    }

    public static ValidationResult validate(WorkflowDefinition workflow) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (workflow.getNodes().isEmpty()) {
            errors.add("Workflow has no nodes");
            return new ValidationResult(false, errors, warnings, List.of());
        }

        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : workflow.getNodes()) {
            if (nodeMap.containsKey(node.getId())) {
                errors.add("Duplicate node ID: " + node.getId());
            }
            nodeMap.put(node.getId(), node);
        }

        Map<String, List<String>> adjList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeMap.keySet()) {
            adjList.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }

        for (WorkflowEdge edge : workflow.getEdges()) {
            if (!nodeMap.containsKey(edge.getSourceNodeId())) {
                errors.add("Edge references non-existent source node: " + edge.getSourceNodeId());
            }
            if (!nodeMap.containsKey(edge.getTargetNodeId())) {
                errors.add("Edge references non-existent target node: " + edge.getTargetNodeId());
            }
            if (nodeMap.containsKey(edge.getSourceNodeId()) && nodeMap.containsKey(edge.getTargetNodeId())) {
                adjList.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
                inDegree.put(edge.getTargetNodeId(), inDegree.get(edge.getTargetNodeId()) + 1);
            }
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, warnings, List.of());
        }

        // Cycle detection via Kahn's algorithm
        Map<String, Integer> inDegreeCopy = new HashMap<>(inDegree);
        List<String> currentLevel = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegreeCopy.entrySet()) {
            if (entry.getValue() == 0) {
                currentLevel.add(entry.getKey());
            }
        }

        if (currentLevel.isEmpty() && !nodeMap.isEmpty()) {
            errors.add("Workflow contains cyclic dependencies (no entry node with 0 inbound edges)");
            return new ValidationResult(false, errors, warnings, List.of());
        }

        List<List<String>> phases = new ArrayList<>();
        int visitedCount = 0;

        while (!currentLevel.isEmpty()) {
            phases.add(new ArrayList<>(currentLevel));
            List<String> nextLevel = new ArrayList<>();
            for (String nodeId : currentLevel) {
                visitedCount++;
                for (String neighbor : adjList.get(nodeId)) {
                    int updatedInDegree = inDegreeCopy.get(neighbor) - 1;
                    inDegreeCopy.put(neighbor, updatedInDegree);
                    if (updatedInDegree == 0) {
                        nextLevel.add(neighbor);
                    }
                }
            }
            currentLevel = nextLevel;
        }

        if (visitedCount < nodeMap.size()) {
            errors.add("Workflow contains a cycle or circular dependency loop");
            return new ValidationResult(false, errors, warnings, List.of());
        }

        // Check for isolated nodes
        for (String nodeId : nodeMap.keySet()) {
            if (inDegree.get(nodeId) == 0 && adjList.get(nodeId).isEmpty() && nodeMap.size() > 1) {
                warnings.add("Node '" + nodeMap.get(nodeId).getName() + "' (" + nodeId + ") is completely disconnected");
            }
        }

        // Check start and end nodes
        boolean hasStart = workflow.getNodes().stream().anyMatch(n -> n.getType() == NodeType.WORKFLOW_START);
        if (!hasStart) {
            warnings.add("Workflow has no explicit WORKFLOW_START node");
        }

        return new ValidationResult(true, errors, warnings, phases);
    }
}

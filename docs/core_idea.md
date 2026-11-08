# TRYST – Core Architectural Idea

**Status:** Fundamental architectural principle  
**Scope:** Entire TRYST platform  
**Audience:** Developers, AI agents, architects, maintainers and automated development tools

## 1. Fundamental Purpose

TRYST is a comprehensive, modular, asynchronous AI software development platform.

Its fundamental purpose is to enable users to submit software development tasks that are independently prepared, executed, monitored, reviewed, improved and completed by an intelligent, persistent and extensible backend.

The primary product experience is inspired by Google Jules:

1. Select a project and repository.
2. Submit a development task.
3. Let TRYST prepare an isolated execution environment.
4. Let AI analyze, plan and implement the requested changes.
5. Execute builds and tests.
6. Present the results for review.
7. Accept user comments and rework requests.
8. Improve the implementation.
9. Obtain explicit approval.
10. Commit and prepare or perform the publication of changes.

The user does not need to remain connected while work is being performed.

**Asynchronous AI-driven software development is the central architectural purpose of TRYST.**

It is not merely one feature among many.

## 2. One Platform – One Central Architectural Idea

TRYST may contain many modules, services, subsystems, protocols, managers and administrative capabilities.

These capabilities are not independent architectural goals.

Every component must contribute to, enable, strengthen, secure, scale or simplify the central asynchronous development lifecycle.

This includes, but is not limited to:

- Task and workflow orchestration.
- Persistent task and execution sessions.
- User and identity management.
- Repository and version-control management.
- Workspace and sandbox management.
- Resource and execution pools.
- Queueing, scheduling and concurrency.
- AI models, inference and context management.
- AI agents and tool execution.
- MCP integration.
- TEIP and protocol integration.
- Database and persistence infrastructure.
- Administration and configuration.
- Security and permission management.
- Logging, monitoring and diagnostics.
- Recovery and lifecycle management.

These capabilities are essential platform infrastructure when they support the central purpose.

They must not evolve into disconnected products or isolated technical experiments.

## 3. Every Subsystem Must Strengthen Asynchronous Development

Every subsystem must have a clearly identifiable relationship with the asynchronous development lifecycle.

Examples:

| Subsystem | Contribution |
|---|---|
| Task Management | Receives, tracks and coordinates development tasks. |
| Session Management | Maintains persistent and isolated execution contexts. |
| Pool Management | Allocates and releases resources for concurrent tasks. |
| Repository Management | Prepares isolated source environments and manages repository lifecycle. |
| Workspace Management | Preserves task-specific working state. |
| Sandbox Management | Enables safe and isolated execution. |
| AI Management | Provides inference, reasoning, planning and implementation capabilities. |
| MCP | Extends AI access to external tools and resources. |
| TEIP | Provides protocol and tool integration within the platform architecture. |
| Database Management | Persists state, history, context and execution results. |
| Scheduling | Coordinates asynchronous workload execution. |
| Administration | Configures and controls the infrastructure. |
| Security | Protects users, repositories, sessions, tools and execution environments. |
| Monitoring | Makes execution, failures and resource utilization observable. |

A subsystem may provide general-purpose infrastructure, but its integration into TRYST must have a meaningful architectural purpose.

## 4. The Complete Asynchronous Lifecycle Is the Primary Reference

All architectural decisions must be evaluated against the complete development lifecycle:

**SUBMISSION → PREPARATION → EXECUTION → OBSERVATION → REVIEW → REWORK → APPROVAL → COMMIT → PUBLICATION**

This lifecycle must remain reliable even when:

- The user closes the browser.
- Multiple tasks execute concurrently.
- Multiple users work with different projects and repositories.
- AI inference takes a long time.
- A task waits in a queue.
- A task is paused, cancelled or retried.
- A process or service fails.
- The RCP application restarts.
- A task requires multiple review and rework iterations.

Persistence, isolation, recovery, concurrency and observability are fundamental architectural requirements, not optional enhancements.

## 5. Architectural Ownership and Reuse

Each platform capability must have a clear authoritative owner.

Other components must consume its services rather than reimplementing its responsibilities.

AI agents and developers must:

- Inspect the existing implementation before introducing new abstractions.
- Identify authoritative services, managers and persistence models.
- Preserve established contracts and working behavior.
- Reuse existing lifecycle and orchestration mechanisms.
- Avoid duplicate managers and competing implementations.
- Avoid parallel task state machines.
- Avoid duplicate persistence and inconsistent sources of truth.
- Respect OSGi service boundaries and module responsibilities.
- Prefer targeted, incremental improvements.
- Introduce new abstractions only when a demonstrated architectural deficiency requires them.

The existing implementation must be treated as an evolving architecture, not as a greenfield application.

## 6. Mandatory Evaluation of Every Change

Before implementing a new feature or refactoring an existing component, establish:

1. Which part of the asynchronous development lifecycle does this change improve?
2. How does it improve concurrency, isolation, scalability or resource utilization?
3. How does it improve persistence, recovery, reliability or security?
4. How does it improve cooperation between AI, tools, repositories and users?
5. Which existing services and infrastructure can be reused?
6. Does it introduce duplicate responsibilities or another source of truth?
7. Does it preserve the reliability of the complete development lifecycle?

A technically interesting change is not automatically a valuable TRYST improvement.

A change without a meaningful contribution to the platform's central purpose must not take priority over improvements to the asynchronous development architecture.

## 7. Platform Evolution

The platform is expected to grow.

New pools, session capabilities, database services, AI providers, agents, MCP integrations, TEIP protocols, administrative tools and other infrastructure are welcome.

The objective is not to restrict the platform to a minimal Jules clone.

The objective is to build a much more capable platform while maintaining one coherent architectural purpose.

Platform infrastructure should be reusable, modular and independently maintainable, but its integration and evolution must strengthen the central asynchronous development architecture.

Do not remove valuable platform capabilities merely to simplify the primary user workflow.

Do not allow platform capabilities to evolve without considering their impact on the primary workflow.

## 8. Priority Rule

When choosing between competing development tasks, prioritize in this order:

1. Correctness and reliability of the complete asynchronous development lifecycle.
2. Persistence, recovery, isolation and concurrency.
3. Integration and reuse of existing platform infrastructure.
4. Scalability, resource efficiency, security and observability.
5. New platform capabilities that strengthen the central architecture.
6. Independent enhancements that do not materially improve the central purpose.

This is a prioritization principle, not a prohibition against platform development.

## 9. Mandatory Instruction for AI Agents

Every AI agent working on TRYST must read and follow this document before performing architectural analysis, planning, implementation or refactoring.

Agents must not optimize individual modules at the expense of the overall platform.

Agents must not mistake the absence of a visible UI feature for the absence of backend infrastructure, or vice versa.

Agents must trace actual execution paths and verify how components cooperate.

Every significant architectural change must explain:

- Its contribution to the central asynchronous development purpose.
- Its interaction with existing platform components.
- Its impact on lifecycle ownership and persistence.
- Its impact on concurrency, isolation and recovery.
- Its compatibility with the complete development workflow.

If a requested change conflicts with this document, the conflict must be identified before implementation.

## 10. The Governing Principle

**ONE PLATFORM. ONE CENTRAL ARCHITECTURAL IDEA. ALL COMPONENTS COOPERATE TO MAKE ASYNCHRONOUS AI SOFTWARE DEVELOPMENT BETTER.**

TRYST is not simply a web interface for AI coding tasks.

It is not merely a collection of AI agents, tools, protocols, pools or administrative modules.

It is a coherent, persistent, concurrent, extensible and self-managing asynchronous AI software development platform.

Every part of the architecture must contribute to that purpose.

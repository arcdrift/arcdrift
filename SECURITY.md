# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in ArcDrift, please **do not** open a public GitHub issue.

- **GitHub:** Report privately via **Security → Advisories → Report a vulnerability** on the repository.
- **Email:** Send details (reproduction steps, affected version, and impact) to `security@arcdrift.dev`.

We will acknowledge receipt within 48 hours and coordinate a fix and disclosure timeline.

## Security & Privacy Design

- **Local-First Execution:** AST/PSI scanning, cycle detection, rule evaluation, and contract verification run entirely locally with zero telemetry.
- **Sandboxed Agent File I/O:** `AgentToolExecutor.resolveSafeRepoFile` canonicalizes and normalizes all `read_project_file`, `write_project_file`, and refactoring paths to ensure they remain strictly within the configured `projectRoot`.
- **BYO Model Endpoint:** The Self-Healing Agent only sends prompts/tool outputs to the OpenAI-compatible endpoint and API key explicitly configured by the user.

# Agent Directives & Operating Guidelines

## 1. Workspace Boundaries & File Safety
- Never create, modify, or move files/directories outside the workspace root.
- All operations, paths, and outputs must remain strictly within this project repository.

## 2. Python Environment
- Always activate and use the project's virtual environment (`.venv` or `venv`).
- Never run Python commands or install packages into the global system environment.
- If no virtual environment exists, check with the user or initialize one locally in `.venv`.

## 3. Dependency & Tool Discovery First
- Before downloading or installing any new CLI tools, binaries, or libraries:
  1. Inspect the local environment to see if the tool is already available in `$PATH`.
  2. Prefer existing native/installed utilities over fetching new dependencies.
- Ask for explicit user confirmation before installing any external tools or global packages.

## 4. Post-Task Cleanup & Hygiene
- After completing a task or sub-milestone:
  - Identify and immediately delete temporary scripts, test fixtures, or scratch files.
  - Remove unused imports, dead code, and obsolete functions.
  - Ensure the repository remains clean and ready for commit.

## 5. File Architecture & Refactoring Review
- After creating new files during a task, perform an architectural self-review:
  - **Consolidation:** If multiple small files are tightly coupled or redundant, merge them into a single cohesive file.
  - **Separation:** If a file has grown too complex or holds mixed responsibilities, split it cleanly into appropriate modules.
- Maintain a clean, maintainable directory structure following standard project conventions.